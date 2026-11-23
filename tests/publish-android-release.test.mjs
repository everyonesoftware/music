import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync, writeSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { main } from '../scripts/publish-android-release.mjs';

const repo = 'owner/music', sha = 'a'.repeat(40), tag = 'v0.1.3', apk = 'Music-0.1.3.apk';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const missing = { status: 1, stderr: 'HTTP 404' };
const ok = body => ({ status: 0, stdout: JSON.stringify(body) });
function setup(t, { published = false, conflict = false, badLocal = false, partial = false } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'music-android-publish-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const bytes = Buffer.from('packaging fixture'), sha256 = hash(bytes);
  const files = new Map([[apk, bytes], [`${apk}.sha256`, Buffer.from(`${sha256}  ${apk}\n`)], ['release.json', Buffer.from(JSON.stringify({ count: 3, version: '0.1.3', sha, tag, apk, bytes: bytes.length, sha256, changelog: { example: true } }))]]);
  for (const [name, data] of files) writeFileSync(join(dir, name), badLocal && name === apk ? 'bad' : data);
  let inventory = new Map(published ? files : partial ? [[apk, bytes]] : []), release = published || partial ? { id: 1, tag_name: tag, target_commitish: sha, draft: !published, prerelease: false, published_at: '2026-01-01T00:00:00Z' } : null;
  if (conflict) inventory.set('release.json', Buffer.alloc(files.get('release.json').length, 120));
  const writes = [];
  const assets = () => [...inventory].map(([name, data], i) => ({ id: i + 1, name, state: 'uploaded', size: data.length, digest: `sha256:${hash(data)}`, browser_download_url: `https://github.com/${repo}/releases/download/${tag}/${name}` }));
  const run = (cmd, args, opts) => {
    if (cmd === 'python3') return spawnSync(cmd, args, opts);
    if (cmd === 'git') {
      const map = { 'rev-parse --is-shallow-repository': 'false', 'rev-parse HEAD': sha, 'rev-list --count HEAD': '3' };
      return { status: 0, stdout: map[args.slice(2).join(' ')] };
    }
    assert.equal(cmd, 'gh');
    if (args[0] === 'api') {
      const path = args[1].replace(`repos/${repo}/`, '');
      if (path === `releases/tags/${tag}`) return release?.draft === false ? ok(release) : missing;
      if (path === 'releases?per_page=100') return ok([[], release ? [release] : []]);
      if (path === `git/ref/tags/${tag}`) return release?.draft === false ? ok({ object: { sha, type: 'commit' } }) : missing;
      if (path === 'releases/1/assets?per_page=100') return ok([assets()]);
      if (path.startsWith('releases/assets/')) {
        const name = assets().find(a => a.id === Number(path.split('/').at(-1)))?.name;
        if (opts.stdio) { writeSync(opts.stdio[1], inventory.get(name)); return { status: 0 }; }
        return { status: 0, stdout: inventory.get(name) };
      }
    }
    const [noun, action, releaseTag] = args;
    assert.equal(noun, 'release');
    assert.equal(releaseTag, tag);
    assert.ok(!args.includes('--latest') && !args.includes('--clobber'));
    writes.push(action);
    if (action === 'create') {
      assert.ok(args.includes('--draft') && args.includes('--latest=false'));
      assert.equal(args[args.indexOf('--notes') + 1], '');
      release = { id: 1, tag_name: tag, target_commitish: sha, draft: true, prerelease: false };
    } else if (action === 'upload') {
      for (const path of args.slice(3, args.indexOf('--repo'))) {
        const name = basename(path);
        assert.ok(!inventory.has(name), name);
        inventory.set(name, readFileSync(path));
      }
    } else if (action === 'edit') {
      assert.ok(args.includes('--draft=false') && args.includes('--latest=false'));
      release = { ...release, draft: false, published_at: '2026-01-01T00:00:00Z' };
    } else assert.fail(args.join(' '));
    return ok({});
  };
  return { writes, exec: () => main(['--directory', dir, '--repository', repo], { run, output: () => {} }) };
}

test('Android publishes verified draft bytes without touching Latest; identical rerun makes no writes', t => {
  const f = setup(t);
  assert.equal(f.exec().tag, tag);
  assert.deepEqual(f.writes, ['create', 'upload', 'edit']);
  f.exec();
  assert.deepEqual(f.writes, ['create', 'upload', 'edit']);
});

test('Android resumes a matching partial draft without replacing assets', t => {
  const f = setup(t, { partial: true });
  f.exec();
  assert.deepEqual(f.writes, ['upload', 'edit']);
});

test('Android rejects mismatched published bytes and invalid local packaging before any writes', t => {
  for (const [options, error] of [[{ published: true, conflict: true }, /digest conflict.*release.json/], [{ badLocal: true }, /APK integrity checksum or size mismatch/]]) {
    const f = setup(t, options);
    assert.throws(f.exec, error);
    assert.deepEqual(f.writes, []);
  }
});
