import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import test from 'node:test';

const script = new URL('../scripts/ensure-windows-main-release.mjs', import.meta.url);
const sha = 'a'.repeat(40);
const otherSha = 'b'.repeat(40);
const repository = 'owner/music';
const root = '/fixture/source';
const tag = 'v0.1.42';
const prefix = `repos/${repository}`;
const releasePath = `${prefix}/releases/tags/${tag}`;
const refPath = `${prefix}/git/ref/tags/${tag}`;
const ok = value => ({ status: 0, stdout: typeof value === 'string' ? value : JSON.stringify(value), stderr: '' });
const missing = () => ({ status: 1, stdout: '', stderr: 'gh: Not Found (HTTP 404)' });
const release = changes => ({ id: 42, tag_name: tag, target_commitish: sha, draft: false, prerelease: false, published_at: '2026-01-01T00:00:00Z', ...changes });

async function load() {
  assert.ok(existsSync(script), 'ensure-windows-main-release.mjs must exist');
  return import(script.href);
}
function fixture({ releases = [ok(release())], ref = ok({ object: { type: 'commit', sha } }), create = ok('created'), tags = {} } = {}) {
  const calls = [];
  const run = (command, args, options) => {
    calls.push({ command, args: [...args], options });
    assert.equal(options.shell, false);
    assert.equal(options.encoding, 'utf8');
    assert.ok(Number.isInteger(options.timeout) && options.timeout > 0 && options.timeout <= 120_000, 'every command must have bounded timeout');
    if (command === 'git') {
      assert.deepEqual(args.slice(0, 2), ['-C', root]);
      const query = args.slice(2).join(' ');
      if (query === 'rev-parse --is-shallow-repository') return ok('false');
      if (query === 'rev-list --count HEAD') return ok('42');
      if (query === 'rev-parse HEAD') return ok(sha);
    }
    if (command === 'gh' && args[0] === 'api') {
      assert.equal(args.length, 2);
      if (args[1] === releasePath) {
        assert.ok(releases.length, 'unexpected extra release read');
        return releases.shift();
      }
      if (args[1] === refPath) return Array.isArray(ref) ? ref.shift() : ref;
      if (Object.hasOwn(tags, args[1])) return tags[args[1]];
    }
    if (command === 'gh' && args[0] === 'release' && args[1] === 'create') return create;
    assert.fail(`Unexpected command: ${command} ${args.join(' ')}`);
  };
  const invoke = async (androidChanged = 'false', argv = ['--repository', repository, '--android-changed', androidChanged]) => {
    const { main } = await load();
    const output = [];
    const result = await main(argv, { root, run, output: text => output.push(text) });
    assert.deepEqual(JSON.parse(output.join('')), result);
    return result;
  };
  return { calls, invoke };
}

test('creates a stable Windows-only main release at the exact source without moving latest', async () => {
  const f = fixture({ releases: [missing(), ok(release())] });
  const result = await f.invoke();
  assert.deepEqual(result, { tag, sha, created: true });
  const writes = f.calls.filter(c => c.command === 'gh' && c.args[0] !== 'api');
  assert.equal(writes.length, 1);
  assert.deepEqual(writes[0].args.slice(0, 3), ['release', 'create', tag]);
  const args = writes[0].args;
  const value = key => args[args.indexOf(key) + 1];
  assert.equal(value('--repo'), repository);
  assert.equal(value('--target'), sha);
  assert.equal(value('--title'), 'Music 0.1.42');
  assert.match(value('--notes'), /Windows build \(unsigned installer\)/);
  assert.match(value('--notes'), /Android unchanged; see Current downloads/);
  assert.ok(args.includes('--latest=false'));
  assert.ok(args.includes('--draft=false'));
  assert.ok(args.includes('--prerelease=false'));
  assert.ok(!args.includes('--latest') && !args.includes('--generate-notes'));
  assert.equal(f.calls.filter(c => c.args[1] === releasePath).length, 2, 'read back exact release after creation');
});

test('existing published stable release is idempotent and never edits metadata or assets', async () => {
  const f = fixture({ releases: [ok(release({ body: 'Keep Android notes', assets: [{ name: 'Music.apk' }] }))] });
  assert.deepEqual(await f.invoke(), { tag, sha, created: false });
  assert.ok(f.calls.every(c => c.command === 'git' || c.args[0] === 'api'));
  assert.ok(f.calls.some(c => c.args[1] === refPath), 'must verify dereferenced tag even when release exists');
});

for (const [name, changes] of [
  ['different target SHA', { target_commitish: otherSha }],
  ['branch target instead of SHA', { target_commitish: 'main' }],
  ['draft', { draft: true }],
  ['prerelease', { prerelease: true }],
  ['unpublished', { published_at: null }],
  ['wrong tag', { tag_name: 'v0.1.41' }],
  ['invalid identity', { id: 0 }],
  ['missing stable state', { prerelease: undefined }],
]) {
  test(`rejects existing release with ${name} without writing`, async () => {
    const f = fixture({ releases: [ok(release(changes))] });
    await assert.rejects(f.invoke(), /release.*(?:source|state|identity|published)/i);
    assert.ok(f.calls.every(c => c.command === 'git' || c.args[0] === 'api'));
  });
}

test('dereferences nested annotated tags to the exact commit', async () => {
  const inner = 'c'.repeat(40);
  const f = fixture({
    ref: ok({ object: { type: 'tag', sha: otherSha } }),
    tags: {
      [`${prefix}/git/tags/${otherSha}`]: ok({ object: { type: 'tag', sha: inner } }),
      [`${prefix}/git/tags/${inner}`]: ok({ object: { type: 'commit', sha } }),
    },
  });
  assert.equal((await f.invoke()).created, false);
  assert.equal(f.calls.filter(c => c.args[1]?.startsWith(`${prefix}/git/tags/`)).length, 2);
});

for (const [name, ref, tags = {}] of [
  ['wrong commit', ok({ object: { type: 'commit', sha: otherSha } })],
  ['tree', ok({ object: { type: 'tree', sha } })],
  ['missing object', ok({})],
  ['unsafe annotation SHA', ok({ object: { type: 'tag', sha: '../../releases/latest' } })],
  ['annotated wrong commit', ok({ object: { type: 'tag', sha: otherSha } }), { [`${prefix}/git/tags/${otherSha}`]: ok({ object: { type: 'commit', sha: otherSha } }) }],
  ['cyclic annotation', ok({ object: { type: 'tag', sha: otherSha } }), { [`${prefix}/git/tags/${otherSha}`]: ok({ object: { type: 'tag', sha: otherSha } }) }],
]) {
  test(`rejects tag with ${name} without writing`, async () => {
    const f = fixture({ ref, tags });
    await assert.rejects(f.invoke(), /tag.*(?:commit|chain|object|SHA)/i);
    assert.ok(f.calls.every(c => c.command === 'git' || c.args[0] === 'api'));
  });
}

for (const [name, response] of [
  ['wrong source', ok(release({ target_commitish: otherSha }))],
  ['draft', ok(release({ draft: true }))],
  ['prerelease', ok(release({ prerelease: true }))],
  ['missing release', missing()],
  ['network failure', { status: 1, stderr: 'connection reset' }],
]) {
  test(`fails closed on creation readback: ${name}`, async () => {
    const f = fixture({ releases: [missing(), response] });
    await assert.rejects(f.invoke(), /release|GitHub/i);
    assert.equal(f.calls.filter(c => c.args[0] === 'release').length, 1);
  });
}

test('creation reads back the tag SHA, not only target_commitish', async () => {
  const f = fixture({ releases: [missing(), ok(release())], ref: [missing(), ok({ object: { type: 'commit', sha: otherSha } })] });
  await assert.rejects(f.invoke(), /tag.*commit/i);
});

test('rejects a conflicting pre-existing tag before creating the missing release', async () => {
  const f = fixture({ releases: [missing(), ok(release())], ref: ok({ object: { type: 'commit', sha: otherSha } }) });
  await assert.rejects(f.invoke(), /tag.*commit/i);
  assert.ok(f.calls.every(c => c.command === 'git' || c.args[0] === 'api'));
});

test('missing tag is optional before creation but mandatory on readback', async () => {
  const f = fixture({ releases: [missing(), ok(release())], ref: [missing(), missing()] });
  await assert.rejects(f.invoke(), /GitHub.*404/i);
  assert.equal(f.calls.filter(c => c.args[0] === 'release').length, 1);
});

for (const [name, response] of [
  ['network', { status: 1, stderr: 'connection refused' }],
  ['auth', { status: 1, stderr: 'HTTP 401: Bad credentials' }],
  ['permission', { status: 1, stderr: 'HTTP 403: Forbidden' }],
  ['timeout even with 404 stderr', { status: null, error: new Error('ETIMEDOUT'), stderr: 'HTTP 404' }],
  ['bare not found without HTTP status', { status: 1, stderr: 'not found' }],
  ['successful null JSON', ok(null)],
  ['successful malformed JSON', ok('{broken')],
]) {
  test(`never treats ${name} as optional missing release`, async () => {
    const f = fixture({ releases: [response] });
    await assert.rejects(f.invoke());
    assert.ok(f.calls.every(c => c.command === 'git' || c.args[0] === 'api'));
  });
}

for (const argv of [
  [], ['--repository', repository], ['--android-changed', 'false'],
  ['--repository', repository, '--android-changed', 'False'],
  ['--repository', repository, '--android-changed', 'false', '--android-changed', 'true'],
  ['--repository', repository, '--android-changed', 'true', '--unknown', 'value'],
  ['--repository', repository, '--android-changed'],
  ['--repository', repository, '--android-changed', 'true', 'extra'],
  ...['--help', '-owner/repo', 'owner/repo;echo pwn', 'owner/repo/extra', '../repo', 'owner/..', 'owner/repo\n', 'owner/repo?x=1'].map(repo => ['--repository', repo, '--android-changed', 'true']),
]) {
  test(`rejects unsafe or incomplete CLI before running commands: ${JSON.stringify(argv)}`, async () => {
    const f = fixture();
    await assert.rejects(f.invoke('false', argv), /Usage|repository|android-changed|required/i);
    assert.deepEqual(f.calls, []);
  });
}

test('creates a missing tag and release and verifies both after publication', async () => {
  const f = fixture({ releases: [missing(), ok(release())], ref: [missing(), ok({ object: { type: 'commit', sha } })] });
  assert.deepEqual(await f.invoke(), { tag, sha, created: true });
  const index = f.calls.findIndex(c => c.args[0] === 'release');
  assert.ok(f.calls.slice(index + 1).some(c => c.args[1] === releasePath));
  assert.ok(f.calls.slice(index + 1).some(c => c.args[1] === refPath));
});

for (const [name, response] of [
  ['authentication', { status: 1, stderr: 'HTTP 401: Bad credentials' }],
  ['network', { status: 1, stderr: 'connection refused' }],
]) {
  test(`preflight tag ${name} failure cannot trigger release creation`, async () => {
    const f = fixture({ releases: [missing()], ref: response });
    await assert.rejects(f.invoke(), /GitHub command failed/);
    assert.ok(f.calls.every(c => c.command === 'git' || c.args[0] === 'api'));
  });
}

for (const response of [
  { status: 1, stderr: 'release already exists' },
  { status: null, error: new Error('ETIMEDOUT') },
]) {
  test(`creation failure is reported, never retried or overwritten: ${response.stderr || response.error.message}`, async () => {
    const f = fixture({ releases: [missing()], create: response });
    await assert.rejects(f.invoke(), /GitHub release creation failed/);
    assert.equal(f.calls.filter(c => c.args[0] === 'release').length, 1);
  });
}

test('existing release with missing tag is rejected, not repaired', async () => {
  const f = fixture({ ref: missing() });
  await assert.rejects(f.invoke(), /GitHub.*404/);
  assert.ok(f.calls.every(c => c.command === 'git' || c.args[0] === 'api'));
});

test('bounds otherwise valid acyclic annotated tag traversal', async () => {
  const annotations = Array.from({ length: 17 }, (_, i) => (i + 1).toString(16).padStart(40, '0'));
  const f = fixture({
    ref: ok({ object: { type: 'tag', sha: annotations[0] } }),
    tags: Object.fromEntries(annotations.map((annotation, i) => [`${prefix}/git/tags/${annotation}`,
      ok({ object: i + 1 === annotations.length ? { type: 'commit', sha } : { type: 'tag', sha: annotations[i + 1] } })])),
  });
  await assert.rejects(f.invoke(), /tag chain/);
  assert.equal(f.calls.filter(c => c.args[1]?.startsWith(`${prefix}/git/tags/`)).length, 16);
});

test('Android changed is a complete no-op, leaving Android publication in charge', async () => {
  const f = fixture();
  assert.deepEqual(await f.invoke('true'), { skipped: true, reason: 'Android changed' });
  assert.deepEqual(f.calls, []);
});
