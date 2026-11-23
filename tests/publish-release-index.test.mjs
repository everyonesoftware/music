import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { main as publish } from '../scripts/publish-release-index.mjs';

const repo = 'owner/music';
const sha = n => String(n).padStart(40, '0');
const release = n => ({ id: n, tag_name: `v0.1.${n}`, target_commitish: sha(n), draft: false, prerelease: false, published_at: '2026-01-01T00:00:00Z', body: 'Original notes' });
const indexName = 'latest-versions.json';
const base = `https://github.com/${repo}/releases`;
const tagged = `${base}/download/v0.1.3/${indexName}`;
const latestUrl = `${base}/latest/download/${indexName}`;
const cdn = 'https://release-assets.githubusercontent.com/fixture?signature=test';
const setup = ({ latest: latestVersion = 2, corrupt = false, index, rejectPromotion = false, missingWindows = false, changed = 'windows', main = 3, divergent = false, badDigest = false, publicResponse } = {}) => {
  let latest = release(latestVersion), next = 100;
  const writes = [], files = new Map(), releases = [release(3), release(2), release(1)];
  const digest = createHash('sha256').update('x').digest('hex');
  const assets = new Map(releases.map(r => [r.id, []]));
  const add = (n, name, body = Buffer.from('x'), sum) => {
    const id = next++;
    files.set(id, Buffer.from(body));
    assets.get(n).push({ id, name, size: files.get(id).length, state: 'uploaded', ...(sum ? { digest: `sha256:${sum}` } : {}), browser_download_url: `https://github.com/${repo}/releases/download/${release(n).tag_name}/${name}` });
  };
  const requests = [], events = [];
  const fetch = async (url, options) => {
    url = String(url);
    assert.equal(options.redirect, 'manual');
    assert.equal(options.credentials, 'omit');
    assert.equal(new Headers(options.headers).has('authorization'), false);
    assert.equal(new Headers(options.headers).has('cookie'), false);
    assert.ok(options.signal instanceof AbortSignal);
    requests.push(url);
    events.push(url);
    const override = publicResponse?.(url, { requests, latest });
    if (override) return override;
    if (url === latestUrl) {
      assert.equal(latest.id, 3);
      return Response.redirect(tagged);
    }
    if (url === tagged) return Response.redirect(cdn);
    assert.equal(url, cdn);
    return new Response(files.get(assets.get(3).find(asset => asset.name === indexName).id));
  };
  const android = changed === 'windows' ? 2 : 3;
  const windows = changed === 'android' ? 2 : 3;
  const version = `0.1.${android}`;
  const apk = `Music-${version}.apk`;
  add(android, apk, 'x', digest);
  add(android, `${apk}.sha256`, `${digest}  ${apk}\n`);
  add(android, 'release.json', JSON.stringify({ version, tag: `v${version}`, count: android, sha: sha(android), apk, bytes: 1, sha256: digest }));
  const win = ['MusicWindows-win-x64-Setup.exe', `MusicWindows-0.1.${windows}-win-x64-full.nupkg`, 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64'];
  for (const name of win) if (!missingWindows || name !== win[1]) add(windows, name, 'x', badDigest && name === win[1] ? 'a'.repeat(64) : digest);
  add(windows, `${win[0]}.sha256`, `${digest}  ${win[0]}\n`);
  add(windows, 'SHA256SUMS', win.map(name => `${digest}  ${name}\r\n`).join(''));
  add(windows, 'windows-release.json', JSON.stringify({ version: `0.1.${windows}`, tag: `windows-v0.1.${windows}`, count: windows, sha: sha(windows), platform: 'windows-x64', signing: 'unsigned-preview', artifacts: win.map(name => ({ name, bytes: 1, sha256: digest })) }));
  if (index) add(3, indexName, index);
  const ok = body => ({ status: 0, stdout: Buffer.isBuffer(body) ? body : JSON.stringify(body) });
  const run = (command, args) => {
    if (command === 'git') {
      const tail = args.slice(2).join(' ');
      if (tail === 'rev-parse --is-shallow-repository') return { status: 0, stdout: 'false\n' };
      if (tail === 'rev-parse HEAD') return { status: 0, stdout: sha(3) };
      if (tail === 'rev-list --count HEAD') return { status: 0, stdout: '3' };
      if (tail === `merge-base --is-ancestor ${sha(2)} ${sha(3)}`) return { status: 0, stdout: '' };
    }
    assert.equal(command, 'gh');
    if (args[0] === 'release' && args[1] === 'upload') {
      assert.equal(args[2], 'v0.1.3');
      assert.ok(!args.includes('--clobber'));
      const body = readFileSync(args[3]);
      writes.push('upload');
      events.push('upload');
      add(3, indexName, corrupt ? Buffer.alloc(body.length, 120) : body);
      return ok({});
    }
    const path = args[1].replace(`repos/${repo}/`, '');
    if (path === 'releases/latest') return ok(latest);
    if (path === 'releases?per_page=100') return ok([releases.slice(0, 1), releases.slice(1)]);
    if (path === 'releases/tags/v0.1.3') return ok(releases[0]);
    if (path === 'git/ref/heads/main') return ok({ object: { sha: sha(main), type: 'commit' } });
    if (path === `compare/${sha(3)}...${sha(main)}?per_page=1`) return ok({ status: divergent ? 'diverged' : 'ahead', merge_base_commit: { sha: sha(divergent ? 2 : 3) } });
    if (path.startsWith('git/ref/tags/')) return ok({ object: { type: 'commit', sha: sha(Number(path.split('.').at(-1))) } });
    if (/^releases\/\d+\/assets\?/.test(path)) return ok(assets.get(Number(path.split('/')[1])).map(asset => [asset]));
    if (path.startsWith('releases/assets/')) return ok(files.get(Number(path.split('/').at(-1))));
    if (path === 'releases/3' && args.includes('PATCH')) {
      const body = args.find(arg => arg.startsWith('body='))?.slice(5);
      assert.ok(args.includes('make_latest=true'));
      writes.push('promote');
      events.push('promote');
      if (rejectPromotion) return { status: 1, stderr: 'failed PATCH' };
      latest = releases[0] = { ...releases[0], body };
      return ok(latest);
    }
    assert.fail(args.join(' '));
  };
  const required = changed === 'both' ? ['android', 'windows'] : [changed];
  const exec = (platforms = required) => publish(['--repository', repo, '--source', sha(3), '--android-required', String(required.includes('android')), '--windows-required', String(required.includes('windows')), '--android-published', String(platforms.includes('android')), '--windows-published', String(platforms.includes('windows'))], { root: '.', run, fetch, pause: async () => {}, output: () => {} });
  return { exec, writes, requests, events, latest: () => latest };
};

test('verifies anonymous tagged bytes before promotion and the client Latest route after', async () => {
  const fixture = setup();
  await fixture.exec();
  assert.deepEqual(fixture.events, ['upload', tagged, cdn, 'promote', latestUrl, tagged, cdn]);
});

test('authenticated readback cannot substitute for public tagged bytes', async () => {
  const fixture = setup({ publicResponse: url => url === cdn && new Response('wrong bytes') });
  await assert.rejects(async () => fixture.exec(), /tagged.*bytes/i);
  assert.deepEqual(fixture.writes, ['upload']);
  assert.equal(fixture.latest().id, 2);
});

test('public Latest mismatch reports failure without rolling back or reuploading', async () => {
  let mismatch = true;
  const fixture = setup({ publicResponse: (url, { latest }) => mismatch && latest.id === 3 && url === cdn && new Response('wrong bytes') });
  await assert.rejects(async () => fixture.exec(), /Latest.*bytes/);
  assert.deepEqual(fixture.writes, ['upload', 'promote']);
  mismatch = false;
  await fixture.exec();
  assert.deepEqual(fixture.writes, ['upload', 'promote']);
});

test('retries transient public visibility, but persistent errors have a finite budget', async () => {
  const recovering = setup({ publicResponse: (url, { requests }) => url === tagged && requests.length === 1 && new Response(null, { status: 404 }) });
  await recovering.exec();
  assert.deepEqual(recovering.requests.slice(0, 3), [tagged, tagged, cdn]);
  const failing = setup({ publicResponse: () => new Response(null, { status: 503 }) });
  await assert.rejects(async () => failing.exec(), /tagged.*503/i);
  assert.deepEqual(failing.requests, [tagged, tagged, tagged]);
  assert.deepEqual(failing.writes, ['upload']);
});

test('retries a stale public Latest redirect without following the older tag', async () => {
  const older = tagged.replace('/v0.1.3/', '/v0.1.2/');
  const fixture = setup({ publicResponse: (url, { requests }) => url === latestUrl && requests.indexOf(url) === requests.length - 1 && Response.redirect(older) });
  await fixture.exec();
  assert.deepEqual(fixture.requests, [tagged, cdn, latestUrl, latestUrl, tagged, cdn]);
});

test('does not fetch unexpected origins, and rejects oversized public bodies', async () => {
  for (const location of ['https://outside.invalid/asset', 'https://api.github.com/repos/owner/music', 'http://objects.githubusercontent.com/file', 'https://objects.githubusercontent.com:123/file', 'https://creds@objects.githubusercontent.com/file']) {
    const fixture = setup({ publicResponse: () => Response.redirect(location) });
    await assert.rejects(async () => fixture.exec(), /tagged.*redirect/i);
    assert.deepEqual(fixture.requests, [tagged]);
    assert.deepEqual(fixture.writes, ['upload']);
  }
  const oversized = setup({ publicResponse: () => new Response(new Uint8Array(16 * 1024 + 1)) });
  await assert.rejects(async () => oversized.exec(), /tagged.*size/i);
  assert.deepEqual(oversized.writes, ['upload']);
});

test('verified split publications produce one immutable index and compact version-specific links before Latest', async () => {
  const fixture = setup();
  const result = await fixture.exec();
  assert.deepEqual(result.index, { schema: 1, android: 'v0.1.2', windows: 'v0.1.3' });
  assert.deepEqual(fixture.writes, ['upload', 'promote']);
  assert.match(fixture.latest().body, /^Original notes\n/);
  assert.match(fixture.latest().body, /v0\.1\.2\/Music-0\.1\.2\.apk[)]/);
  assert.match(fixture.latest().body, /v0\.1\.3\/MusicWindows-win-x64-Setup\.exe[)]/);
  assert.match(fixture.latest().body, /unsigned/i);
  await fixture.exec();
  assert.deepEqual(fixture.writes, ['upload', 'promote']);
});

test('failed eligibility, partial Windows inventory and conflicting/readback index leave Latest unchanged', async () => {
  for (const [options, platforms, error, writes] of [
    [{}, ['android'], /required Windows/i, []],
    [{ missingWindows: true }, ['windows'], /verified.*Windows/i, []],
    [{ badDigest: true }, ['windows'], /digest conflict.*nupkg/i, []],
    [{ index: '{"different":1}\n' }, ['windows'], /index.*conflict/i, []],
    [{ corrupt: true }, ['windows'], /index.*conflict/i, ['upload']],
    [{ rejectPromotion: true }, ['windows'], /failed PATCH/i, ['upload', 'promote']],
  ]) {
    const fixture = setup(options);
    await assert.rejects(fixture.exec(platforms), error);
    assert.deepEqual(fixture.writes, writes);
    assert.equal(fixture.latest().tag_name, 'v0.1.2');
  }
});

test('a rerun resumes promotion with existing exact index bytes; older runs do not write', async () => {
  const recovering = setup({ index: '{"schema":1,"android":"v0.1.2","windows":"v0.1.3"}\n' });
  await recovering.exec();
  assert.deepEqual(recovering.writes, ['promote']);
  const stale = setup({ latest: 4 });
  assert.equal((await stale.exec()).skipped, true);
  assert.deepEqual(stale.writes, []);
});

test('a shared release indexes the same stable tag for both verified platform publications', async () => {
  const shared = setup({ changed: 'both' });
  assert.deepEqual((await shared.exec()).index, { schema: 1, android: 'v0.1.3', windows: 'v0.1.3' });
  assert.deepEqual(shared.writes, ['upload', 'promote']);
  const noNativeSuccess = setup({ changed: 'both' });
  assert.equal((await noNativeSuccess.exec([])).skipped, true);
  assert.deepEqual(noNativeSuccess.writes, []);
});

test('an Android-only release indexes the unchanged Windows feed even if main has advanced', async () => {
  const f = setup({ changed: 'android', main: 4 });
  assert.deepEqual((await f.exec()).index, { schema: 1, android: 'v0.1.3', windows: 'v0.1.2' });
  assert.deepEqual(f.writes, ['upload', 'promote']);
  const diverged = setup({ main: 4, divergent: true });
  await assert.rejects(diverged.exec(), /not on remote main/);
  assert.deepEqual(diverged.writes, []);
});

test('the actual release-index CLI rejects invalid input before any command', () => {
  const result = spawnSync(process.execPath, ['scripts/publish-release-index.mjs', '--repository', '../music'], { cwd: new URL('../', import.meta.url), encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Invalid repository/);
});
