import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';

// Raw affectedness is publication safety data, not manual job selection.
test('CI selector keeps raw affectedness when a manual Windows run gates Android off', async () => {
  const { main } = await import('../scripts/ci-selection.mjs');
  const calls = [];
  const result = main(['--repository', 'owner/music', '--target', 'windows', '--trimmed', 'false'], {
    select(argv) {
      calls.push(argv);
      return { android: { build: true }, windows: { build: false } };
    }, output: () => {},
  });
  assert.deepEqual(result, { android: false, windows: true, android_changed: true, windows_changed: false, web: false });
  assert.deepEqual(calls, [['--repository', 'owner/music', '--component', 'windows', '--force', 'none']]);
});

test('auto follows affectedness; explicit targets force only requested native jobs and maintenance gates all builds', async () => {
  const { main } = await import('../scripts/ci-selection.mjs');
  for (const android of [true, false]) for (const windows of [true, false]) for (const web of [true, false]) {
    for (const target of ['auto', 'all', 'android', 'windows', 'maintenance']) {
      const calls = [];
      const actual = main(['--repository', 'owner/music', '--target', target, '--trimmed', 'false'], {
        select(argv) {
          calls.push(argv);
          assert.equal(argv.at(-1), 'none');
          return argv.includes('web') ? { web: { build: web } } : { android: { build: android }, windows: { build: windows } };
        }, output: () => {},
      });
      assert.deepEqual(actual, {
        android: target === 'auto' ? android : ['all', 'android'].includes(target),
        windows: target === 'auto' ? windows : ['all', 'windows'].includes(target),
        android_changed: android, windows_changed: windows,
        web: ['auto', 'all'].includes(target) && web,
      });
      assert.equal(calls.length, ['auto', 'all'].includes(target) ? 2 : 1);
    }
  }
});

test('trimmed windows and all force Windows only without browser discovery or loss of raw Android safety data', async () => {
  const { main } = await import('../scripts/ci-selection.mjs');
  for (const target of ['windows', 'all']) {
    const result = main(['--repository', 'owner/music', '--target', target, '--trimmed', 'true'], {
      select(argv) {
        assert.ok(!argv.includes('web'));
        return { android: { build: true }, windows: { build: false } };
      }, output: () => {},
    });
    assert.deepEqual(result, { android: false, windows: true, android_changed: true, windows_changed: false, web: false });
  }
});

test('malformed and incompatible options fail before dependency calls or file I/O', async () => {
  const { main } = await import('../scripts/ci-selection.mjs');
  const valid = ['--repository', 'owner/music'];
  for (const argv of [
    [], ['--repository', '../bad'], [...valid, '--unknown', 'x'], [...valid, '--target'],
    [...valid, '--target', 'web'], [...valid, '--trimmed', 'yes'], [...valid, '--trimmed', 'TRUE'],
    [...valid, '--target', 'auto', '--target', 'windows'], [...valid, '--github-output', ''],
    ...['auto', 'android', 'maintenance'].map(target => [...valid, '--target', target, '--trimmed', 'true']),
  ]) {
    assert.throws(() => main([...argv, '--github-output', '/not-a-real-directory/outputs'], {
      select: () => assert.fail('selection must not run'), run: () => assert.fail('commands must not run'), output: () => assert.fail('no output'),
    }), /Invalid|Trimmed/);
  }
});

test('default auto appends exactly five boolean outputs and emits matching final JSON', async t => {
  const { main } = await import('../scripts/ci-selection.mjs');
  const root = mkdtempSync(join(tmpdir(), 'music-ci-selection-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const path = join(root, 'outputs');
  writeFileSync(path, 'existing=value\n');
  const logs = [];
  const result = main(['--repository', 'owner/music', '--github-output', path], {
    select: argv => argv.includes('web') ? { web: { build: true } } : { android: { build: false }, windows: { build: true } },
    output: text => logs.push(text),
  });
  assert.deepEqual(result, { android: false, windows: true, android_changed: false, windows_changed: true, web: true });
  assert.equal(readFileSync(path, 'utf8'), 'existing=value\nandroid=false\nwindows=true\nandroid_changed=false\nwindows_changed=true\nweb=true\n');
  assert.deepEqual(logs.map(text => JSON.parse(text)), [result]);
});

test('real Git and paginated gh reads preserve skipped-platform affectedness with forced Windows', async t => {
  const { main } = await import('../scripts/ci-selection.mjs');
  const root = mkdtempSync(join(tmpdir(), 'music-ci-selection-git-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const git = (...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' }).trim();
  git('init', '--quiet'); git('config', 'user.name', 'Unit Fixture'); git('config', 'user.email', 'fixture@example.invalid');
  writeFileSync(join(root, 'README.md'), 'base'); git('add', '.'); git('commit', '--quiet', '-m', 'base');
  const sha = git('rev-parse', 'HEAD');
  mkdirSync(dirname(join(root, 'android/player.kt')), { recursive: true });
  writeFileSync(join(root, 'android/player.kt'), 'unpublished Android change');
  git('add', '.'); git('commit', '--quiet', '-m', 'Android input');
  const winNames = ['MusicWindows-win-x64-Setup.exe', 'MusicWindows-0.1.43-win-x64-full.nupkg', 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64'];
  const artifacts = winNames.map(name => ({ name, bytes: 42, sha256: 'b'.repeat(64) }));
  const manifests = [
    { count: 43, version: '0.1.43', tag: 'v0.1.43', sha, apk: 'Music-0.1.43.apk', bytes: 42, sha256: 'b'.repeat(64) },
    { count: 43, version: '0.1.43', tag: 'windows-v0.1.43', sha, platform: 'windows-x64', signing: 'unsigned-preview', artifacts },
  ];
  const assets = [
    ['Music-0.1.43.apk', 'Music-0.1.43.apk.sha256', 'release.json'],
    [...winNames, 'windows-release.json', 'SHA256SUMS'],
  ].map((names, index) => names.map((name, i) => ({ name, id: index * 100 + i + 1, state: 'uploaded', size: ['windows-release.json', 'release.json'].includes(name) ? 1500 : 42 })));
  const releases = manifests.map((m, i) => ({ id: i + 1, tag_name: m.tag, target_commitish: sha, prerelease: i === 1, draft: false }));
  const run = (command, args, options) => {
    if (command === 'git') return spawnSync(command, args, options);
    assert.equal(command, 'gh'); assert.equal(options.shell, false);
    const endpoint = args[1].replace('repos/owner/music/', '');
    let data;
    if (endpoint === 'releases?per_page=100') data = [[releases[0]], [releases[1]]];
    else if (/^releases\/[12]\/assets\?/.test(endpoint)) data = [assets[Number(endpoint.split('/')[1]) - 1]];
    else if (endpoint.startsWith('releases/assets/')) data = manifests[Number(endpoint.split('/').at(-1)) > 100 ? 1 : 0];
    else if (endpoint.startsWith('git/ref/tags/')) data = { object: { type: 'commit', sha } };
    else assert.fail('Unexpected endpoint ' + endpoint);
    return { status: 0, stdout: JSON.stringify(data) };
  };
  assert.deepEqual(main(['--repository', 'owner/music', '--target', 'windows'], { root, run, output: () => {} }), {
    android: false, windows: true, android_changed: true, windows_changed: false, web: false,
  });
});
