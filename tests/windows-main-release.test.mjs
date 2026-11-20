// Disposable bytes and injected commands only; these are not shipping packages.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync, existsSync, mkdirSync, copyFileSync, symlinkSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { tmpdir } from 'node:os';
import { join, basename } from 'node:path';
import { createHash } from 'node:crypto';
const script = new URL('../scripts/publish-windows-main-release.mjs', import.meta.url);
const sha = 'a'.repeat(40), root = '/fixture/source';
const installer = 'MusicWindows-win-x64-Setup.exe';
const hash = b => createHash('sha256').update(b).digest('hex');
const ok = v => ({ status: 0, stdout: typeof v === 'string' ? v : JSON.stringify(v), stderr: '' });
function fixture(t, changes = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'windows-main-unit-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const names = [`MusicWindows-0.1.43-win-x64-full.nupkg`, installer, 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64'];
  for (const name of names) writeFileSync(join(directory, name), `UNIT ONLY ${name}`);
  const manifest = { count: 43, version: '0.1.43', tag: 'windows-v0.1.43', sha, platform: 'windows-x64', package_id: 'MusicWindows', runtime: 'win-x64', channel: 'win-x64', signing: 'unsigned-preview', artifacts: names.map(name => { const b = readFileSync(join(directory, name)); return { name, bytes: b.length, sha256: hash(b) }; }) };
  writeFileSync(join(directory, 'windows-release.json'), JSON.stringify(manifest, null, 4) + '\n');
  writeFileSync(join(directory, 'SHA256SUMS'), 'UNIT CHECKSUM INVENTORY');
  names.push('windows-release.json', 'SHA256SUMS');
  const original = new Map(names.map(n => [n, readFileSync(join(directory, n))]));
  const release = { id: 43, tag_name: 'v0.1.43', target_commitish: sha, draft: false, prerelease: false, published_at: '2026-01-01T00:00:00Z', name: 'Android title', body: 'Keep Android notes', ...changes.release };
  let present = !changes.missing;
  const assets = [{ id: 1, name: 'Music.apk', size: 4, state: 'uploaded', label: 'Keep' }];
  const content = new Map([[1, Buffer.from('APK!')]]), calls = [], paths = [];
  const run = (cmd, args, opts) => {
    calls.push({ cmd, args, opts });
    assert.equal(opts.shell, false);
    assert.ok(opts.timeout > 0 && opts.timeout <= 120000);
    if (cmd === 'git') {
      const query = args.slice(2).join(' ');
      return ok(({ 'rev-parse --is-shallow-repository': 'false', 'rev-list --count HEAD': '43', [`rev-list --count ${sha}`]: '43', 'rev-parse HEAD': sha, 'status --porcelain --untracked-files=all': changes.dirty ? ' M source' : '' })[query] ?? assert.fail(query));
    }
    if (cmd === 'python3' || cmd === 'python') {
      assert.equal(args[0], join(root, 'scripts/windows-release.py'));
      assert.equal(args[1], 'validate');
      assert.equal(args[args.indexOf('--root') + 1], root);
      assert.deepEqual(readFileSync(join(args[args.indexOf('--directory') + 1], 'windows-release.json')), original.get('windows-release.json'), 'strict Python validator always sees original packaging bytes');
      if (changes.invalid) return { status: 1, stderr: 'Incomplete self-contained Windows payload' };
      return ok(manifest);
    }
    assert.equal(cmd, 'gh');
    changes.onNetwork?.({ directory, calls, release, assets, content });
    if (args[0] === 'release') {
      if (args[1] === 'create') {
        assert.equal(args[2], 'v0.1.43');
        assert.ok(args.includes('--latest=false') && args.includes('--prerelease=false'));
        present = true;
        return ok('created');
      }
      assert.deepEqual(args.slice(0, 3), ['release', 'upload', 'v0.1.43']);
      assert.ok(!args.includes('--clobber'));
      for (const path of args.slice(3, args.indexOf('--repo'))) {
        assert.ok(!path.startsWith(directory + '/'));
        paths.push(path);
        const bytes = readFileSync(path), id = assets.length + 1;
        assets.push({ id, name: basename(path), size: bytes.length, state: 'uploaded' });
        content.set(id, bytes);
      }
      changes.afterUpload?.({ release, assets, content });
      return ok('');
    }
    const path = args[1];
    if (path.endsWith('/releases/tags/v0.1.43')) return present ? ok(release) : { status: 1, stderr: 'HTTP 404' };
    if (path.endsWith('/git/ref/tags/v0.1.43')) return ok({ object: { type: 'commit', sha: changes.tagSha || sha } });
    if (path.endsWith('/releases/43/assets')) { assert.ok(args.includes('--paginate')); return ok([assets.slice(0, 1), assets.slice(1)]); }
    const id = Number(path.split('/').at(-1));
    assert.ok(content.has(id), path);
    writeFileSync(opts.stdio[1], content.get(id));
    return ok('');
  };
  const invoke = async (android = 'true', argv) => {
    assert.ok(existsSync(script), 'stable main publisher must exist');
    const { main } = await import(script.href);
    const arguments_ = argv || ['--directory', directory, '--repository', 'owner/music', '--android-changed', android, ...(changes.changelog ? ['--changelog', 'true'] : [])];
    const changelog = { schema: 1, platform: 'windows', head: { version: manifest.version, sha }, releases: [{ version: manifest.version, sha, commits: [] }] };
    return main(arguments_, { root, run, output: () => {}, generate: () => ({ changelog, publishedManifest: changes.publishedOriginal ? manifest : null }) });
  };
  return { directory, names, original, manifest, release, assets, content, calls, paths, invoke };
}

for (const variant of ['missing-package', 'modified-package', 'extra-file', 'symlink-package', 'duplicate-inventory', 'traversal-inventory']) {
  test(`rejects unsafe local ${variant} before network even with validator boundary injected`, async t => {
    const f = fixture(t);
    const packagePath = join(f.directory, f.names[0]);
    if (variant === 'missing-package') rmSync(packagePath);
    if (variant === 'modified-package') writeFileSync(packagePath, 'changed');
    if (variant === 'extra-file') writeFileSync(join(f.directory, 'extra'), 'changed');
    if (variant === 'symlink-package') { rmSync(packagePath); symlinkSync(join(f.directory, installer), packagePath); }
    if (variant === 'duplicate-inventory') f.manifest.artifacts[0] = f.manifest.artifacts[1];
    if (variant === 'traversal-inventory') f.manifest.artifacts[0].name = '../bad';
    if (variant.includes('inventory')) writeFileSync(join(f.directory, 'windows-release.json'), JSON.stringify(f.manifest));
    await assert.rejects(f.invoke());
    assert.ok(f.calls.every(c => c.cmd !== 'gh'));
  });
}

test('real unchanged Python validator rejects a malformed package before any GitHub call', async t => {
  const f = fixture(t);
  const checkout = mkdtempSync(join(tmpdir(), 'windows-main-clean-source-'));
  t.after(() => rmSync(checkout, { recursive: true, force: true }));
  mkdirSync(join(checkout, 'scripts'));
  copyFileSync(fileURLToPath(new URL('../scripts/windows-release.py', import.meta.url)), join(checkout, 'scripts/windows-release.py'));
  const git = args => {
    const result = spawnSync('git', ['-C', checkout, ...args], { encoding: 'utf8', shell: false, timeout: 120000 });
    assert.equal(result.status, 0, result.stderr);
    return result.stdout.trim();
  };
  git(['init', '--quiet']);
  git(['add', '.']);
  git(['-c', 'user.name=Release Unit Test', '-c', 'user.email=unit@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture validator']);
  const manifest = { ...f.manifest, count: 1, version: '0.1.1', tag: 'windows-v0.1.1', sha: git(['rev-parse', 'HEAD']) };
  writeFileSync(join(f.directory, 'windows-release.json'), JSON.stringify(manifest));
  const calls = [];
  const { main } = await import(script.href);
  await assert.rejects(main(['--directory', f.directory, '--repository', 'owner/music', '--android-changed', 'false'], {
    root: checkout,
    output: () => {},
    run: (command, args, options) => {
      calls.push(command);
      assert.notEqual(command, 'gh', 'no live network permitted');
      return spawnSync(command, args, options);
    },
  }), /Missing required Windows asset|Unexpected, nested, or symlink asset/);
  assert.ok(calls.includes(process.platform === 'win32' ? 'python' : 'python3'));
});
test('Windows-only release notes describe unsigned installer without preview branding', async t => {
  const f = fixture(t, { missing: true });
  await f.invoke('false');
  const create = f.calls.find(c => c.args[0] === 'release' && c.args[1] === 'create');
  assert.ok(create);
  const notes = create.args[create.args.indexOf('--notes') + 1];
  assert.match(notes, /unsigned installer/i);
  assert.doesNotMatch(notes, /preview/i);
});

test('publishes all seven original inventory files plus installer checksum to stable main, idempotently', async t => {
  const f = fixture(t), metadata = structuredClone(f.release);
  const result = await f.invoke();
  assert.equal(result.tag, 'v0.1.43');
  assert.deepEqual([...result.uploaded].sort(), [...f.names, installer + '.sha256'].sort());
  for (const [name, bytes] of f.original) assert.deepEqual(f.content.get(f.assets.find(a => a.name === name).id), bytes);
  assert.deepEqual(f.release, metadata);
  assert.deepEqual(f.assets[0], { id: 1, name: 'Music.apk', size: 4, state: 'uploaded', label: 'Keep' });
  assert.deepEqual((await f.invoke()).uploaded, []);
  assert.ok(f.paths.every(p => !existsSync(p)));
  assert.ok(f.calls.findIndex(c => c.cmd.startsWith('python')) < f.calls.findIndex(c => c.cmd === 'gh'));
});
for (const [label, changes, pattern] of [
  ['dirty checkout', { dirty: true }, /clean Git checkout/],
  ['invalid full payload', { invalid: true }, /Incomplete self-contained/],
  ['missing Android release', { missing: true }, /existing published stable/],
  ['draft', { release: { draft: true } }, /published stable/],
  ['prerelease', { release: { prerelease: true } }, /published stable/],
  ['unpublished', { release: { published_at: null } }, /published stable/],
  ['branch target', { release: { target_commitish: 'main' } }, /commit mismatch/],
  ['wrong tag SHA', { tagSha: 'b'.repeat(40) }, /tag commit mismatch/],
]) {
  test(`fails closed for ${label} without any release write`, async t => {
    const f = fixture(t, changes);
    await assert.rejects(f.invoke(), pattern);
    assert.equal(f.calls.filter(c => c.cmd === 'gh' && c.args[0] === 'release').length, 0);
    if (changes.invalid || changes.dirty) assert.ok(f.calls.every(c => c.cmd !== 'gh'));
    if (changes.missing) assert.equal(f.calls.filter(c => c.cmd === 'gh').length, 1, 'no waiting/polling');
  });
}

test('invalid full package blocks Windows-only release creation too', async t => {
  const f = fixture(t, { invalid: true, missing: true });
  await assert.rejects(f.invoke('false'), /Incomplete self-contained/);
  assert.ok(f.calls.every(c => c.cmd !== 'gh'));
});

test('installer-only stable main release gains the missing feed without replacing installer assets', async t => {
  const f = fixture(t);
  const installerBytes = f.original.get(installer);
  const checksum = Buffer.from(`${hash(installerBytes)}  ${installer}\n`);
  for (const [name, bytes] of [[installer, installerBytes], [installer + '.sha256', checksum]]) {
    const id = f.assets.length + 1;
    f.assets.push({ id, name, size: bytes.length, state: 'uploaded', label: 'existing' });
    f.content.set(id, bytes);
  }
  const before = structuredClone(f.assets);
  const result = await f.invoke();
  assert.deepEqual(result.uploaded.sort(), f.names.filter(n => n !== installer).sort());
  assert.deepEqual(f.assets.slice(0, before.length), before);
});

test('source build mutations after validation cannot affect uploaded snapshot bytes', async t => {
  const f = fixture(t, { onNetwork: ({ directory }) => writeFileSync(join(directory, 'releases.win-x64.json'), 'MUTATED LIVE BUILD') });
  await f.invoke();
  const asset = f.assets.find(a => a.name === 'releases.win-x64.json');
  assert.deepEqual(f.content.get(asset.id), f.original.get(asset.name));
});

for (const name of ['releases.win-x64.json', 'windows-release.json', 'SHA256SUMS']) {
  test(`conflicting existing ${name} blocks all uploads`, async t => {
    const f = fixture(t), bytes = Buffer.alloc(f.original.get(name).length);
    f.assets.push({ id: 2, name, size: bytes.length, state: 'uploaded' });
    f.content.set(2, bytes);
    await assert.rejects(f.invoke(), /conflict/);
    assert.equal(f.calls.filter(c => c.args[0] === 'release').length, 0);
  });
}

test('postupload package corruption is detected; retry never clobbers immutable bytes', async t => {
  const f = fixture(t, { afterUpload: ({ assets, content }) => {
    const asset = assets.find(a => a.name.endsWith('.nupkg'));
    content.set(asset.id, Buffer.alloc(asset.size));
  } });
  await assert.rejects(f.invoke(), /conflict/);
  await assert.rejects(f.invoke(), /conflict/);
  assert.equal(f.calls.filter(c => c.args[0] === 'release').length, 1);
});

for (const change of ['body', 'android', 'feed']) {
  test(`postupload ${change} mutation is detected`, async t => {
    const f = fixture(t, { afterUpload: ({ release, assets, content }) => {
      if (change === 'body') release.body = 'Changed';
      if (change === 'android') assets[0].label = 'Changed';
      if (change === 'feed') {
        const asset = assets.find(a => a.name === 'releases.win-x64.json');
        content.set(asset.id, Buffer.alloc(asset.size));
      }
    } });
    await assert.rejects(f.invoke(), /changed|conflict/);
  });
}

// Minimal stored-ZIP writer for structural, explicitly non-runnable fixtures.
function fixtureZip(entries) {
  const chunks = [], directory = []; let offset = 0;
  for (const [name, value] of Object.entries(entries)) {
    const bytes = Buffer.from(value), filename = Buffer.from(name);
    let crc = 0xffffffff;
    for (const byte of bytes) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0); }
    crc = (crc ^ 0xffffffff) >>> 0;
    const header = Buffer.alloc(30); header.writeUInt32LE(0x04034b50); header.writeUInt16LE(20, 4); header.writeUInt32LE(crc, 14); header.writeUInt32LE(bytes.length, 18); header.writeUInt32LE(bytes.length, 22); header.writeUInt16LE(filename.length, 26);
    const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6); central.writeUInt32LE(crc, 16); central.writeUInt32LE(bytes.length, 20); central.writeUInt32LE(bytes.length, 24); central.writeUInt16LE(filename.length, 28); central.writeUInt32LE(offset, 42);
    chunks.push(header, filename, bytes); directory.push(central, filename); offset += header.length + filename.length + bytes.length;
  }
  const central = Buffer.concat(directory), end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50); end.writeUInt16LE(Object.keys(entries).length, 8); end.writeUInt16LE(Object.keys(entries).length, 10); end.writeUInt32LE(central.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...chunks, central, end]);
}

test('real strict Python validates original ZIP/feed and Node publishes enriched bytes without changing that validator', async t => {
  const temporary = mkdtempSync(join(tmpdir(), 'music-real-enriched-')); t.after(() => rmSync(temporary, { recursive: true, force: true }));
  const checkout = join(temporary, 'source'), directory = join(temporary, 'package'); mkdirSync(join(checkout, 'scripts'), { recursive: true }); mkdirSync(directory);
  copyFileSync(fileURLToPath(new URL('../scripts/windows-release.py', import.meta.url)), join(checkout, 'scripts/windows-release.py'));
  const execute = (cmd, args, options = {}) => { const r = spawnSync(cmd, args, { encoding: 'utf8', ...options }); assert.equal(r.status, 0, r.stderr); return r.stdout.trim(); };
  execute('git', ['-C', checkout, 'init', '-q']); execute('git', ['-C', checkout, 'add', '.']); execute('git', ['-C', checkout, '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture']);
  const sourceSha = execute('git', ['-C', checkout, 'rev-parse', 'HEAD']);
  const pe = Buffer.alloc(128); pe.write('MZ'); pe.writeUInt32LE(64, 60); pe.write('PE\0\0', 64); pe.writeUInt16LE(0x8664, 68);
  writeFileSync(join(directory, installer), pe);
  const entries = { 'MusicWindows.nuspec': '<package><metadata><id>MusicWindows</id><version>0.1.1</version><channel>win-x64</channel><mainExe>Music.exe</mainExe><os>win</os><rid>win-x64</rid></metadata></package>', 'lib/app/Music.exe': pe, 'lib/app/Music.runtimeconfig.json': JSON.stringify({ runtimeOptions: { includedFrameworks: [{ name: 'Microsoft.NETCore.App', version: '10.0.15' }] } }), 'lib/app/Music.deps.json': JSON.stringify({ runtimeTarget: { name: '.NETCoreApp,Version=v10.0/win-x64' }, libraries: { 'Music/0.1.1': {} } }) };
  for (const name of ['Music.dll', 'coreclr.dll', 'hostfxr.dll', 'Microsoft.UI.Xaml.dll', 'Microsoft.WindowsAppRuntime.dll']) entries['lib/app/' + name] = 'EXPLICIT NON-RUNNABLE UNIT FIXTURE';
  const name = 'MusicWindows-0.1.1-win-x64-full.nupkg', bytes = fixtureZip(entries), sha1 = createHash('sha1').update(bytes).digest('hex');
  writeFileSync(join(directory, name), bytes);
  writeFileSync(join(directory, 'releases.win-x64.json'), JSON.stringify({ Assets: [{ PackageId: 'MusicWindows', Version: '0.1.1', Type: 'Full', FileName: name, Size: bytes.length, SHA1: sha1, SHA256: hash(bytes) }] }));
  writeFileSync(join(directory, 'assets.win-x64.json'), JSON.stringify([{ RelativeFileName: installer, Type: 'Installer' }, { RelativeFileName: name, Type: 'Full' }]));
  writeFileSync(join(directory, 'RELEASES-win-x64'), `${sha1} ${name} ${bytes.length}\n`);
  const python = process.platform === 'win32' ? 'python' : 'python3';
  const args = [join(checkout, 'scripts/windows-release.py'), 'manifest', '--root', checkout, '--directory', directory];
  execute(python, args);
  const original = readFileSync(join(directory, 'windows-release.json'));
  const source = { count: 1, version: '0.1.1', tag: 'windows-v0.1.1', sha: sourceSha };
  const changelog = { schema: 1, platform: 'windows', head: { version: source.version, sha: sourceSha }, releases: [{ version: source.version, sha: sourceSha, commits: [] }] };
  writeFileSync(join(directory, 'windows-release.json'), JSON.stringify({ ...JSON.parse(original), changelog }));
  const rejectsEnriched = spawnSync(python, [args[0], 'validate', ...args.slice(2)], { encoding: 'utf8' });
  assert.notEqual(rejectsEnriched.status, 0, 'prove legacy equality really rejects an additive field');
  writeFileSync(join(directory, 'windows-release.json'), original);
  const release = { id: 1, tag_name: 'v0.1.1', target_commitish: sourceSha, draft: false, prerelease: false, published_at: '2026-01-01T00:00:00Z' }, assets = [], content = new Map(); let validations = 0;
  const run = (cmd, commandArgs, options) => {
    if (cmd !== 'gh') { if (cmd === python) { validations++; assert.deepEqual(readFileSync(join(commandArgs.at(-1), 'windows-release.json')), original); } return spawnSync(cmd, commandArgs, options); }
    if (commandArgs[0] === 'release') { for (const path of commandArgs.slice(3, commandArgs.indexOf('--repo'))) { const b = readFileSync(path), id = assets.length + 1; assets.push({ id, name: basename(path), size: b.length, state: 'uploaded' }); content.set(id, b); } return ok(''); }
    if (commandArgs[1].endsWith('/releases/tags/v0.1.1')) return ok(release);
    if (commandArgs[1].endsWith('/git/ref/tags/v0.1.1')) return ok({ object: { type: 'commit', sha: sourceSha } });
    if (commandArgs[1].endsWith('/releases/1/assets')) return ok([assets]);
    const id = Number(commandArgs[1].split('/').at(-1)); assert.ok(content.has(id)); writeFileSync(options.stdio[1], content.get(id)); return ok('');
  };
  const { attachWindowsInstaller } = await import('../scripts/attach-windows-installer.mjs');
  await attachWindowsInstaller({ directory, repository: 'owner/music', source, root: checkout, run, includeUpdateFeed: true, changelog });
  assert.equal(validations, 1);
  assert.deepEqual(JSON.parse(content.get(assets.find(a => a.name === 'windows-release.json').id)), { ...JSON.parse(original), changelog });
  assert.deepEqual(readFileSync(join(directory, 'windows-release.json')), original);
});

test('new publication enriches only staged manifest after strict original-byte Python validation and verifies enriched readback', async t => {
  const f = fixture(t, { changelog: true });
  await f.invoke();
  const remote = JSON.parse(f.content.get(f.assets.find(a => a.name === 'windows-release.json').id));
  assert.deepEqual(remote, { ...f.manifest, changelog: { schema: 1, platform: 'windows', head: { version: f.manifest.version, sha }, releases: [{ version: f.manifest.version, sha, commits: [] }] } });
  for (const [name, bytes] of f.original) {
    assert.deepEqual(readFileSync(join(f.directory, name)), bytes);
    if (name !== 'windows-release.json') assert.deepEqual(f.content.get(f.assets.find(a => a.name === name).id), bytes);
  }
  assert.deepEqual((await f.invoke()).uploaded, []);
});

test('complete pre-changelog publication is never retroactively enriched', async t => {
  const f = fixture(t, { changelog: true, publishedOriginal: true });
  await f.invoke();
  assert.deepEqual(f.content.get(f.assets.find(a => a.name === 'windows-release.json').id), f.original.get('windows-release.json'));
});

test('corrupt enriched manifest readback fails without clobbering published assets', async t => {
  const f = fixture(t, { changelog: true, afterUpload: ({ assets, content }) => { const a = assets.find(a => a.name === 'windows-release.json'); content.set(a.id, Buffer.alloc(a.size)); } });
  await assert.rejects(f.invoke(), /conflict/);
  await assert.rejects(f.invoke(), /conflict/);
  assert.equal(f.calls.filter(c => c.args[0] === 'release').length, 1);
});

for (const argv of [[], ['--directory', 'x', '--repository', '../repo', '--android-changed', 'true'], ['--directory', 'x', '--repository', 'o/r', '--android-changed', 'True'], ['--directory', 'x', '--repository', 'o/r', '--android-changed', 'false', '--android-changed', 'true'], ['--wat', 'x']]) {
  test(`rejects invalid CLI before local/network commands: ${JSON.stringify(argv)}`, async t => {
    const f = fixture(t);
    await assert.rejects(f.invoke('true', argv));
    assert.deepEqual(f.calls, []);
  });
}
