import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const repository = 'everyonesoftware/music';
function release(version, { windows = false, android = false, prerelease = false, draft = false, platform = false } = {}) {
  const tag_name = `${platform ? 'windows-v' : 'v'}${version}`;
  const asset = name => ({ name, size: 42, state: 'uploaded', browser_download_url: `https://github.com/${repository}/releases/download/${tag_name}/${name}` });
  const names = [...(windows ? ['MusicWindows-win-x64-Setup.exe'] : []), ...(android ? [`Music-${version}.apk`] : [])];
  return { id: Number(version.split('.').at(-1)), tag_name, prerelease, draft, assets: names.flatMap(name => [asset(name), asset(`${name}.sha256`)]) };
}

test('selects each platform independently, retaining older installers across platform-only releases', async () => {
  const { collectDownloads, renderDownloads, replaceDownloads } = await import('../scripts/current-downloads.mjs');
  const inventory = collectDownloads([
    release('0.1.9', { android: true, windows: true }),
    release('0.1.10', { android: true }),
    release('0.1.12', { windows: true, draft: true }),
  ], repository);
  assert.equal(inventory.android.version, '0.1.10');
  assert.equal(inventory.windows.version, '0.1.9');
  const table = renderDownloads(inventory);
  assert.match(table, /v0\.1\.9\/MusicWindows-win-x64-Setup.exe/);
  assert.match(table, /v0\.1\.10\/Music-0.1.10.apk/);
  assert.match(table, /unsigned preview/i);
  assert.match(table, /No released web app/);
  assert.doesNotMatch(table, /latest\/download/);
  const original = '# Music\n\nExisting documentation.\n';
  const updated = replaceDownloads(original, table, { readme: true });
  assert.match(updated, /^# Music\n\n<!-- current-downloads:start -->/);
  assert.ok(updated.endsWith('Existing documentation.\n'));
  assert.equal(replaceDownloads(updated, table, { readme: true }), updated);
});

test('rejects incomplete, unsafe, draft and generic prerelease downloads; supports explicit Windows previews', async () => {
  const { collectDownloads, replaceDownloads } = await import('../scripts/current-downloads.mjs');
  const old = release('0.1.9', { windows: true, android: true });
  const incomplete = release('0.1.10', { android: true });
  incomplete.assets.pop();
  const unsafe = release('0.1.11', { android: true });
  unsafe.assets[0].browser_download_url = 'https://example.com/fake.apk';
  const preview = release('0.1.12', { windows: true, platform: true, prerelease: true });
  const inventory = collectDownloads([old, incomplete, unsafe, preview, release('0.1.13', { android: true, prerelease: true })], repository);
  assert.equal(inventory.android.version, '0.1.9');
  assert.equal(inventory.windows.version, '0.1.12');
  assert.throws(() => collectDownloads([], '../bad'), /Invalid repository/);
  assert.throws(() => replaceDownloads('<!-- current-downloads:start -->', 'table'), /Malformed/);
  assert.throws(() => replaceDownloads('<!-- current-downloads:end --><!-- current-downloads:start -->', 'table'), /Malformed/);
});

function remote({ windowsOnly = false } = {}) {
  const releases = [...(windowsOnly ? [release('0.1.11', { windows: true })] : []), release('0.1.10', { android: true }), release('0.1.9', { windows: true })];
  let readme = '# Music\n\nKeep README text.\n';
  let body = 'Keep original release notes.';
  let windowsBody = 'Windows-only build; Android unchanged.';
  const writes = [];
  const api = (path, options = {}) => {
    if (options.method) writes.push({ path, ...options });
    if (path === '') return { default_branch: 'main' };
    if (path.startsWith('actions/workflows/')) return [{ workflow_runs: [] }];
    if (path === 'releases?per_page=100') return [releases.map(r => ({ ...r, assets: [] }))];
    if (/^releases\/\d+\/assets/.test(path)) return [releases.find(r => r.id === Number(path.split('/')[1])).assets];
    if (path === 'releases/latest') return { ...releases.find(r => r.id === 10), body, draft: false, prerelease: false };
    if (path === 'releases/11') {
      if (options.method) windowsBody = options.data.body;
      return { ...releases.find(r => r.id === 11), body: windowsBody, draft: false, prerelease: false };
    }
    if (path === 'releases/10') {
      if (options.method) body = options.data.body;
      return { ...releases.find(r => r.id === 10), body, draft: false, prerelease: false };
    }
    if (path.startsWith('contents/README.md')) {
      if (options.method) {
        assert.equal(options.data.branch, 'main');
        assert.equal(options.data.sha, 'original-blob');
        readme = Buffer.from(options.data.content, 'base64').toString();
      }
      return { encoding: 'base64', content: Buffer.from(readme).toString('base64'), sha: 'original-blob' };
    }
    throw new Error('Unexpected API: ' + path);
  };
  return { api, writes, get readme() { return readme; }, get body() { return body; }, get windowsBody() { return windowsBody; } };
}

test('refresh updates both surfaces, preserves prose, reads both back, and is idempotent', async () => {
  const { refreshDownloads } = await import('../scripts/current-downloads.mjs');
  const r = remote();
  const result = refreshDownloads({ repository, api: r.api, publish: true });
  assert.equal(result.inventory.android.version, '0.1.10');
  assert.equal(result.inventory.windows.version, '0.1.9');
  assert.match(r.readme, /Keep README text/);
  assert.match(r.body, /Keep original release notes/);
  assert.equal(r.writes.length, 2);
  assert.equal(r.writes[0].path, 'releases/10');
  assert.deepEqual(Object.keys(r.writes[0].data), ['body']);
  refreshDownloads({ repository, api: r.api, publish: true });
  assert.equal(r.writes.length, 2);
});

test('preview is read-only and active publishers defer writes to avoid installer-attachment races', async () => {
  const { refreshDownloads } = await import('../scripts/current-downloads.mjs');
  const r = remote();
  assert.match(refreshDownloads({ repository, api: r.api }).releaseNotes, /Current downloads/);
  assert.equal(r.writes.length, 0);
  const result = refreshDownloads({ repository, publish: true, api: (path, opts) => path.startsWith('actions/workflows/') ? [{ workflow_runs: [{ status: 'in_progress' }] }] : r.api(path, opts) });
  assert.equal(result.deferred, true);
  assert.equal(r.writes.length, 0);
});

test('observable human body changes before PATCH are rejected without writes', async () => {
  const { refreshDownloads } = await import('../scripts/current-downloads.mjs');
  const r = remote();
  assert.throws(() => refreshDownloads({ repository, publish: true, api: (path, opts) => {
    const response = r.api(path, opts);
    return path === 'releases/10' && !opts?.method ? { ...response, body: 'New human notes' } : response;
  } }), /Release changed/);
  assert.equal(r.writes.length, 0);
});

test('active independent recovery defers download-description writers', async () => {
  const { refreshDownloads } = await import('../scripts/current-downloads.mjs');
  const r = remote();
  const result = refreshDownloads({ repository, publish: true, api: (path, opts) => path.startsWith('actions/workflows/windows-publication-recovery.yml/') ? [{ workflow_runs: [{ status: 'in_progress' }] }] : r.api(path, opts) });
  assert.equal(result.deferred, true);
  assert.equal(r.writes.length, 0);
});

test('readback mismatch is a failure rather than claimed publication success', async () => {
  const { refreshDownloads } = await import('../scripts/current-downloads.mjs');
  const r = remote();
  let patched = false;
  assert.throws(() => refreshDownloads({ repository, publish: true, api: (path, opts) => {
    if (path === 'releases/10' && opts?.method) patched = true;
    if (path === 'releases/10' && !opts?.method && patched) return { body: 'Tampered' };
    return r.api(path, opts);
  } }), /readback/);
  assert.equal(r.writes.length, 1);
});

test('CLI previews via paginated read-only gh calls and writes reviewable files', async t => {
  const { main } = await import('../scripts/current-downloads.mjs');
  const r = remote();
  const directory = mkdtempSync(join(tmpdir(), 'music-download-cli-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const run = (command, args, options) => {
    assert.equal(command, 'gh');
    assert.equal(options.shell, false);
    assert.equal(options.timeout, 60_000);
    assert.ok(!args.includes('--method'));
    const prefix = `repos/${repository}`;
    assert.ok(args[1].startsWith(prefix));
    const path = args[1].slice(prefix.length).replace(/^\//, '');
    if (path.includes('per_page=100')) {
      assert.ok(args.includes('--paginate'));
      assert.ok(args.includes('--slurp'));
    }
    return { status: 0, stdout: JSON.stringify(r.api(path)), stderr: '' };
  };
  const result = main(['--repository', repository, '--output-directory', directory], { run });
  assert.equal(JSON.parse(readFileSync(join(directory, 'downloads.json'))).windows.version, '0.1.9');
  assert.equal(readFileSync(join(directory, 'release-notes.md'), 'utf8'), result.releaseNotes);
  assert.equal(readFileSync(join(directory, 'README.md'), 'utf8'), result.readme);
  assert.equal(r.writes.length, 0);
});

test('CLI rejects malformed and missing options before executing commands', async () => {
  const { main } = await import('../scripts/current-downloads.mjs');
  for (const argv of [[], ['--repository', '../bad', '--publish'], ['--repository', repository], ['--unknown'], ['--repository'], ['--publish', '--publish']]) {
    assert.throws(() => main(argv, { run: () => assert.fail('must not invoke gh') }));
  }
});

test('reusable refresh checks trusted default-branch code without completion-triggered fan-out or personal tokens', () => {
  const workflow = readFileSync(new URL('../.github/workflows/current-downloads.yml', import.meta.url), 'utf8');
  assert.match(workflow, /workflow_call:/);
  assert.match(workflow, /schedule:/);
  assert.doesNotMatch(workflow, /workflow_run:|push:/);
  assert.match(workflow, /ref: \$\{\{ github.event.repository.default_branch \}\}/);
  assert.match(workflow, /persist-credentials: false/);
  assert.match(workflow, /GH_TOKEN: \$\{\{ github.token \}\}/);
  assert.match(workflow, /contents: write/);
  assert.match(workflow, /actions: read/);
  assert.match(workflow, /node --test tests\/current-downloads.test.mjs/);
  assert.match(workflow, /node scripts\/current-downloads.mjs --repository "\$GH_REPO" --publish/);
  assert.doesNotMatch(workflow, /secrets\.|head_sha|download-artifact/);
});

test('Windows-only releases refresh both the newest description and Android-compatible latest without promoting anything', async () => {
  const { refreshDownloads } = await import('../scripts/current-downloads.mjs');
  const r = remote({ windowsOnly: true });
  const result = refreshDownloads({ repository, api: r.api, publish: true });
  assert.equal(result.tag, 'v0.1.11');
  assert.equal(result.inventory.android.version, '0.1.10');
  assert.equal(result.inventory.windows.version, '0.1.11');
  assert.match(r.windowsBody, /Current downloads/);
  assert.match(r.windowsBody, /Windows-only build; Android unchanged/);
  assert.match(r.body, /v0\.1\.11\/MusicWindows/);
  assert.equal(r.writes.length, 3);
  for (const write of r.writes.filter(w => w.path.startsWith('releases/'))) assert.deepEqual(Object.keys(write.data), ['body']);
});

test('Windows feed previews can link their real SHA256SUMS inventory without requiring an invented sidecar', async () => {
  const { collectDownloads } = await import('../scripts/current-downloads.mjs');
  const preview = release('0.1.12', { windows: true, platform: true, prerelease: true });
  preview.assets[1].name = 'SHA256SUMS';
  preview.assets[1].browser_download_url = `https://github.com/${repository}/releases/download/windows-v0.1.12/SHA256SUMS`;
  assert.equal(collectDownloads([preview], repository).windows?.version, '0.1.12');
});
