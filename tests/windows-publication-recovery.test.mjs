import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync, readFileSync, mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

// Exercise real Git history and the recovery orchestration; GitHub is the boundary.
test('failed main creation is repaired from original preview at same SHA without packaging', async t => {
  const { recoverWindowsPublications } = await import('../scripts/recover-windows-publication.mjs');
  const { decideFromBaseline } = await import('../scripts/selective-builds.mjs');
  const root = mkdtempSync(join(tmpdir(), 'music-recovery-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const git = (...args) => {
    const r = spawnSync('git', ['-C', root, ...args], { encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr); return r.stdout.trim();
  };
  git('init'); git('config', 'user.email', 'test@example.com'); git('config', 'user.name', 'Test');
  writeFileSync(join(root, 'README.md'), '# Music\n'); git('add', '.'); git('commit', '-m', 'source');
  const sha = git('rev-parse', 'HEAD');
  const preview = { id: 1, tag_name: 'windows-v0.1.1', target_commitish: sha, draft: false, prerelease: true };
  let mainExists = false;
  let complete = false;
  let tampered = false;
  let wrongSource = false;
  let failCreate = true;
  let failAttach = false;
  const events = [];
  const api = (path) => {
    if (path.startsWith('actions/workflows/')) return [{ workflow_runs: [] }];
    if (path === 'releases?per_page=100') return [[preview]];
    if (path === 'releases/tags/windows-v0.1.1') return preview;
    if (path === 'git/ref/tags/windows-v0.1.1') return { object: { type: 'commit', sha } };
    if (path === 'releases/1/assets?per_page=100') return [names.map((name, i) => ({ id: i + 1, name, size: 1, state: 'uploaded' }))];
    if (path === 'releases/tags/v0.1.1') return mainExists ? { id: 2, draft: false, prerelease: false, target_commitish: sha } : null;
    if (path === 'git/ref/tags/v0.1.1') return { object: { type: 'commit', sha } };
    if (path === 'releases/2/assets?per_page=100') return [complete ? [{ name: names[0], size: 1, state: 'uploaded' }, { name: names[0] + '.sha256', size: 99, state: 'uploaded' }] : []];
    throw Error(path);
  };
  const names = ['MusicWindows-win-x64-Setup.exe', 'MusicWindows-0.1.1-win-x64-full.nupkg', 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64', 'windows-release.json', 'SHA256SUMS'];
  const run = (command, args, options) => {
    if (command === 'git') return spawnSync(command, args, options);
    events.push([command, args]);
    assert.ok(!args.some(a => /build-windows|manifest/.test(a)));
    if (command === 'gh') {
      const destination = args[args.indexOf('--dir') + 1]; mkdirSync(destination);
      const bytes = Buffer.from('original verified installer fixture');
      writeFileSync(join(destination, names[0]), tampered ? Buffer.from('tampered download') : bytes);
      writeFileSync(join(destination, 'windows-release.json'), JSON.stringify({ count: 1, version: '0.1.1', tag: preview.tag_name, sha: wrongSource ? 'f'.repeat(40) : sha, platform: 'windows-x64', package_id: 'MusicWindows', runtime: 'win-x64', channel: 'win-x64', signing: 'unsigned-preview', artifacts: [{ name: names[0], bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') }] }));
    }
    if (command === 'python3') {
      const sourceRoot = args[args.indexOf('--root') + 1];
      assert.equal(spawnSync('git', ['-C', sourceRoot, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).stdout.trim(), sha);
    }
    return { status: 0, stdout: '', stderr: '' };
  };
  const ensure = async (args, opts) => { assert.equal(gitAt(opts.root), sha); events.push(['ensure']); if (failCreate) throw Error('main creation failed'); mainExists = true; };
  const gitAt = path => spawnSync('git', ['-C', path, 'rev-parse', 'HEAD'], { encoding: 'utf8' }).stdout.trim();
  const attach = async ({ source }) => { assert.equal(source.sha, sha); events.push(['attach']); if (failAttach) throw Error('attachment failed'); };
  const options = { root, repository: 'owner/repo', api, run, ensure, attach, androidChanged: () => false };
  await assert.rejects(recoverWindowsPublications(options), /main creation failed/);
  assert.equal(mainExists, false);
  assert.equal(decideFromBaseline({ root, component: 'windows', baseline: sha }).build, false);
  failCreate = false; events.length = 0;
  await recoverWindowsPublications(options);
  assert.ok(mainExists);
  assert.deepEqual(events.map(e => e[0]), ['gh', 'python3', 'ensure', 'attach']);
  failAttach = true;
  await assert.rejects(recoverWindowsPublications(options), /attachment failed/);
  failAttach = false; events.length = 0;
  // A failed attachment is repaired on a later documentation-only HEAD, not relabelled.
  writeFileSync(join(root, 'README.md'), '# Music\nDocs\n'); git('add', '.'); git('commit', '-m', 'docs');
  events.length = 0;
  await recoverWindowsPublications(options);
  assert.deepEqual(events.map(e => e[0]), ['gh', 'python3', 'ensure', 'attach']);
  assert.notEqual(git('rev-parse', 'HEAD'), sha);
  assert.equal(decideFromBaseline({ root, component: 'windows', baseline: sha }).build, false);
  tampered = true;
  await assert.rejects(recoverWindowsPublications(options), /Installer size\/hash mismatch/);
  tampered = false; wrongSource = true;
  await assert.rejects(recoverWindowsPublications(options), /source metadata mismatch/);
  wrongSource = false;
  await assert.rejects(recoverWindowsPublications({ ...options, run: (cmd, args, opts) => cmd === 'python3' ? { status: 1, stderr: 'tampered inventory' } : run(cmd, args, opts) }), /tampered inventory/);
  const count = events.filter(e => e[0] === 'attach').length;
  assert.equal(count, 1);
  await assert.rejects(recoverWindowsPublications({ ...options, api: path => path === 'git/ref/tags/windows-v0.1.1' ? { object: { type: 'commit', sha: 'f'.repeat(40) } } : api(path) }), /tag\/source/);
  let previewTagReads = 0;
  await assert.rejects(recoverWindowsPublications({ ...options, api: path => path === 'git/ref/tags/windows-v0.1.1' && ++previewTagReads > 1 ? { object: { type: 'commit', sha: 'f'.repeat(40) } } : api(path) }), /tag\/source/);
  complete = true; events.length = 0;
  await recoverWindowsPublications(options);
  assert.deepEqual(events, [], 'complete attachment needs metadata reads only');
  for (const workflow of ['android-release.yml', 'windows-release.yml']) {
    const result = await recoverWindowsPublications({ ...options, api: path => path.startsWith(`actions/workflows/${workflow}/`) ? [{ workflow_runs: [{ status: 'in_progress' }] }] : api(path) });
    assert.equal(result.deferred, true);
    assert.deepEqual(events, []);
  }
  await assert.rejects(recoverWindowsPublications({ ...options, api: path => path === 'releases?per_page=100' ? [[{ ...preview, draft: true }]] : api(path) }), /Conflicting\/draft/);
  await assert.rejects(recoverWindowsPublications({ ...options, api: path => path === 'releases/tags/v0.1.1' ? { draft: true } : api(path) }), /Conflicting\/draft/);
  mainExists = false; complete = false;
  await assert.rejects(recoverWindowsPublications({ ...options, androidChanged: () => true }), /recover Android first/);
  assert.deepEqual(events, []);
});

test('recovery is independent of native affectedness and shares the Android stable publisher lock', () => {
  const text = readFileSync(new URL('../.github/workflows/windows-publication-recovery.yml', import.meta.url), 'utf8');
  assert.match(text, /workflow_call:/);
  assert.doesNotMatch(text, /workflow_run:|push:/);
  assert.match(text, /schedule:/);
  assert.match(text, /group: audio-apk-publication/);
  assert.match(text, /\nconcurrency:\n  group: music-windows-release-publication\n  cancel-in-progress: false/);
  assert.equal((text.match(/queue: max/g) ?? []).length, 2);
  for (const file of ['android-release.yml', 'windows-release.yml']) {
    assert.match(readFileSync(new URL(`../.github/workflows/${file}`, import.meta.url), 'utf8'), /queue: max/);
  }
  assert.doesNotMatch(text, /needs:|outputs.windows|build-windows-installer/);
  assert.match(text, /node scripts\/recover-windows-publication.mjs/);
  const scripts = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).scripts;
  for (const command of ['test', 'test:release-selection']) assert.ok(scripts[command].includes('tests/windows-publication-recovery.test.mjs'));
});
