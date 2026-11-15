// All binary/release data below are unit fixtures, never production artifacts.
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync, symlinkSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, join } from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { deriveSource, validateLocal, attachWindowsInstaller, main } from '../scripts/attach-windows-installer.mjs';

const sha = 'a'.repeat(40);
const source = { count: 26, version: '0.1.26', tag: 'windows-v0.1.26', sha };
const installer = 'MusicWindows-win-x64-Setup.exe';
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const ok = data => ({ status: 0, stdout: typeof data === 'string' ? data : JSON.stringify(data), stderr: '' });
function gitRun(overrides = {}) {
  return (command, args, options) => {
    assert.equal(command, 'git');
    assert.equal(options.shell, false);
    const key = args.slice(2).join(' ');
    const values = { 'rev-parse --is-shallow-repository': 'false', 'rev-list --count HEAD': '26', 'rev-parse HEAD': sha, ...overrides };
    assert.ok(key in values, key);
    return ok(values[key]);
  };
}
function fixture(t) {
  const directory = mkdtempSync(join(tmpdir(), 'shared-release-unit-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const bytes = Buffer.from('UNIT FIXTURE ONLY: not an executable');
  const manifest = { ...source, platform: 'windows-x64', package_id: 'MusicWindows', runtime: 'win-x64', channel: 'win-x64', signing: 'unsigned-preview', artifacts: [{ name: installer, bytes: bytes.length, sha256: hash(bytes) }, { name: 'other.nupkg', bytes: 10, sha256: 'b'.repeat(64) }] };
  const save = () => writeFileSync(join(directory, 'windows-release.json'), JSON.stringify(manifest));
  writeFileSync(join(directory, installer), bytes);
  save();
  return { directory, bytes, manifest, save };
}

function remote(f, options = {}) {
  const assets = [{ id: 1, name: 'Music.apk', size: 4, label: 'Android', state: 'uploaded' }];
  const content = new Map([[1, Buffer.from('APK!')]]);
  const release = { id: 101, tag_name: 'v0.1.26', target_commitish: sha, draft: false, prerelease: false, name: 'Music 0.1.26', body: 'Android release notes', assets: [] };
  const calls = [];
  let views = 0;
  const run = (command, args, opts) => {
    assert.equal(command, 'gh');
    assert.equal(opts.shell, false);
    calls.push(args);
    if (args[0] === 'release') {
      assert.deepEqual(args.slice(0, 3), ['release', 'upload', 'v0.1.26']);
      assert.ok(!args.includes('--clobber'));
      for (const path of args.slice(3, args.indexOf('--repo'))) {
        const name = basename(path);
        assert.ok([installer, installer + '.sha256'].includes(name));
        assert.ok(!assets.some(a => a.name === name));
        const bytes = readFileSync(path);
        const id = assets.length + 1;
        assets.push({ id, name, size: bytes.length, state: 'uploaded' });
        content.set(id, bytes);
      }
      options.afterUpload?.({ release, assets, content });
      return ok('');
    }
    assert.equal(args[0], 'api');
    const path = args[1];
    if (path.endsWith('/releases/tags/v0.1.26')) {
      views++;
      if (views <= (options.missing || 0)) return { status: 1, stderr: 'gh: Not Found (HTTP 404)', stdout: '' };
      options.onView?.({ views, release, assets });
      return ok({ ...release, assets });
    }
    if (path.endsWith('/releases/101/assets')) return ok([assets]);
    if (path.endsWith('/git/ref/tags/v0.1.26')) return ok({ object: { type: 'tag', sha: 'b'.repeat(40) } });
    if (path.endsWith('/git/tags/' + 'b'.repeat(40))) return ok({ object: { type: 'commit', sha: options.tagSha || sha } });
    const id = Number(path.split('/').at(-1));
    assert.ok(content.has(id), path);
    assert.ok(args.includes('Accept: application/octet-stream'));
    writeFileSync(opts.stdio[1], options.download ? options.download(content.get(id), id) : content.get(id));
    return ok('');
  };
  return { assets, content, release, calls, run, get views() { return views; } };
}

test('delayed main release receives only installer/checksum and preserves Android and metadata', async t => {
  const f = fixture(t);
  const r = remote(f, { missing: 2 });
  const android = structuredClone(r.assets[0]);
  const metadata = structuredClone(r.release);
  let now = 0;
  const result = await attachWindowsInstaller({ directory: f.directory, repository: 'owner/music', source, run: r.run, clock: () => now, sleep: async ms => { now += ms; }, pollMs: 10, timeoutMs: 100 });
  assert.deepEqual(result.uploaded, [installer, installer + '.sha256']);
  assert.equal(result.tag, 'v0.1.26');
  assert.deepEqual(r.assets[0], android);
  assert.deepEqual(r.release, metadata);
  assert.equal(r.content.get(r.assets.find(a => a.name.endsWith('.sha256')).id).toString(), `${hash(f.bytes)}  ${installer}\n`);
  assert.equal(r.views >= 4, true);
  assert.equal(existsSync(join(f.directory, installer + '.sha256')), false);
  for (const call of r.calls.filter(a => a[0] === 'release')) for (const path of call.slice(3, call.indexOf('--repo'))) assert.equal(existsSync(path), false, 'temporary upload paths cleaned');
});

test('source derives exact full-history version and local installer integrity', t => {
  assert.deepEqual(deriveSource('/checkout', { run: gitRun() }), source);
  const f = fixture(t);
  const local = validateLocal(f.directory, source);
  assert.equal(local.sha256, hash(f.bytes));
  assert.equal(local.bytes, f.bytes.length);
  assert.equal(local.checksum, `${hash(f.bytes)}  ${installer}\n`);
});

const attach = (f, r, extra = {}) => attachWindowsInstaller({ directory: f.directory, repository: 'owner/music', source, run: r.run, ...extra });
const writes = r => r.calls.filter(args => args[0] === 'release');
function existing(r, name, bytes) {
  const id = r.assets.length + 1;
  r.assets.push({ id, name, size: bytes.length, state: 'uploaded' });
  r.content.set(id, bytes);
}

test('CLI derives source with git, attaches with injected runner, and prints result', async t => {
  const f = fixture(t);
  const r = remote(f);
  let output = '';
  const result = await main(['--directory', f.directory, '--repository', 'owner/music'], {
    root: '/checkout', run: (cmd, args, opts) => cmd === 'git' ? gitRun()(cmd, args, opts) : r.run(cmd, args, opts),
    output: text => { output += text; },
  });
  assert.equal(result.sha, sha);
  assert.deepEqual(JSON.parse(output), result);
  assert.equal(writes(r).length, 1);
});

test('direct CLI invocation exits nonzero with actionable missing-option error', () => {
  const script = fileURLToPath(new URL('../scripts/attach-windows-installer.mjs', import.meta.url));
  const result = spawnSync(process.execPath, [script], { encoding: 'utf8', shell: false });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /--directory and --repository are required/);
  assert.equal(result.stdout, '');
});

test('CLI refuses unknown, duplicate, missing and unsafe options without invoking git/network', async () => {
  for (const argv of [[], ['--wat'], ['--directory'], ['--directory', 'x', '--repository', 'a/b', '--directory', 'y'], ['--directory', 'x', '--repository', '-bad/repo']]) {
    let calls = 0;
    await assert.rejects(main(argv, { run: () => { calls++; throw new Error('must not execute'); } }));
    assert.equal(calls, 0);
  }
});

for (const [field, value] of [['sha', 'b'.repeat(40)], ['count', 27], ['version', '0.1.27'], ['tag', 'windows-v0.1.27'], ['platform', 'linux'], ['runtime', 'win-arm64'], ['channel', 'other'], ['package_id', 'Other'], ['signing', 'signed']]) {
  test(`reject manifest ${field} mismatch before network`, async t => {
    const f = fixture(t);
    f.manifest[field] = value;
    f.save();
    const r = remote(f);
    await assert.rejects(attach(f, r), /metadata mismatch/);
    assert.equal(r.calls.length, 0);
  });
}

for (const variant of ['bytes', 'hash', 'missing', 'duplicate', 'traversal', 'symlink', 'directory', 'manifest-symlink', 'directory-symlink']) {
  test(`reject local ${variant} before network`, async t => {
    const f = fixture(t);
    const path = join(f.directory, installer);
    if (variant === 'bytes') f.manifest.artifacts[0].bytes++;
    if (variant === 'hash') writeFileSync(path, Buffer.alloc(f.bytes.length));
    if (variant === 'missing') f.manifest.artifacts.shift();
    if (variant === 'duplicate') f.manifest.artifacts.push(f.manifest.artifacts[0]);
    if (variant === 'traversal') f.manifest.artifacts[0].name = '../' + installer;
    f.save();
    if (variant === 'symlink' || variant === 'directory') {
      rmSync(path);
      symlinkSync(variant === 'directory' ? f.directory : join(f.directory, 'windows-release.json'), path);
    }
    if (variant === 'manifest-symlink') {
      rmSync(join(f.directory, 'windows-release.json'));
      symlinkSync(path, join(f.directory, 'windows-release.json'));
    }
    if (variant === 'directory-symlink') {
      const alias = f.directory + '-link';
      symlinkSync(f.directory, alias);
      t.after(() => rmSync(alias));
      f.directory = alias;
    }
    const r = remote(f);
    await assert.rejects(attach(f, r));
    assert.equal(r.calls.length, 0);
  });
}

test('git rejects shallow/missing HEAD, malformed counts and out-of-range versions', () => {
  for (const [key, value] of [['rev-parse --is-shallow-repository', 'true'], ['rev-list --count HEAD', '0'], ['rev-list --count HEAD', '65535'], ['rev-list --count HEAD', '26junk'], ['rev-list --count HEAD', '1.5'], ['rev-parse HEAD', 'abc']]) assert.throws(() => deriveSource('/checkout', { run: gitRun({ [key]: value }) }));
  assert.throws(() => deriveSource('/checkout', { run: () => ({ status: 128, stderr: 'no HEAD' }) }), /git failed/);
  assert.equal(deriveSource('/checkout', { run: gitRun({ 'rev-list --count HEAD': '65534' }) }).version, '0.1.65534');
  assert.equal(deriveSource('/checkout', { run: gitRun({ 'rev-list --count HEAD': '1' }) }).version, '0.1.1');
});

test('realistic download counters do not prevent immutable same-byte retry', async t => {
  const f = fixture(t), r = remote(f);
  existing(r, installer, f.bytes);
  r.assets[1].download_count = 0;
  const base = r.run;
  r.run = (cmd, args, opts) => {
    const result = base(cmd, args, opts);
    if (args[1]?.endsWith('/releases/assets/2')) r.assets[1].download_count++;
    return result;
  };
  assert.deepEqual((await attach(f, r)).uploaded, [installer + '.sha256']);
  assert.deepEqual((await attach(f, r)).uploaded, []);
  assert.equal(writes(r).length, 1);
});

for (const [phase, view] of [['before upload', 2], ['after upload', 3], ['during final readback', 4]]) {
  test(`concurrent Android download_count change ${phase} is allowed`, async t => {
    const f = fixture(t);
    const r = remote(f, { onView: ({ views, assets }) => { if (views === view) assets[0].download_count++; } });
    Object.assign(r.assets[0], { digest: 'sha256:' + hash(Buffer.from('APK!')), download_count: 10 });
    const android = structuredClone(r.assets[0]);
    const metadata = structuredClone(r.release);
    assert.deepEqual((await attach(f, r)).uploaded, [installer, installer + '.sha256']);
    assert.deepEqual(r.assets[0], { ...android, download_count: 11 });
    assert.deepEqual(r.release, metadata);
    assert.equal(writes(r).length, 1);
  });

  for (const [field, value] of [['id', 999], ['name', 'changed.apk'], ['size', 5], ['digest', 'sha256:' + '0'.repeat(64)], ['state', 'new'], ['label', 'edited']]) {
    test(`concurrent Android ${field} mutation ${phase} is rejected despite download_count change`, async t => {
      const f = fixture(t);
      const r = remote(f, { onView: ({ views, assets }) => {
        if (views === view) {
          assets[0].download_count++;
          assets[0][field] = value;
        }
      } });
      Object.assign(r.assets[0], { digest: 'sha256:' + hash(Buffer.from('APK!')), download_count: 10 });
      await assert.rejects(attach(f, r), /changed/);
      assert.equal(writes(r).length, view === 2 ? 0 : 1);
    });
  }
}

for (const name of [installer, installer + '.sha256']) {
  for (const complete of [false, true]) {
    const mode = complete ? 'idempotent retry' : 'missing counterpart repair';
    for (const [field, value] of [['label', 'edited'], ['digest', 'sha256:' + '0'.repeat(64)], ['state', 'new'], ['name', 'renamed.bin'], ['size', 999], ['id', 999]]) {
      test(`preexisting ${name} ${field} mutation after preupload is rejected during ${mode}`, async t => {
        const f = fixture(t);
        const r = remote(f, { onView: ({ views, assets }) => {
          if (views === 3) {
            const asset = assets.find(a => a.name === name);
            asset.download_count++;
            asset[field] = value;
          }
        } });
        for (const owned of [installer, installer + '.sha256']) {
          if (complete || owned === name) existing(r, owned, owned === installer ? f.bytes : Buffer.from(`${hash(f.bytes)}  ${installer}\n`));
        }
        const asset = r.assets.find(a => a.name === name);
        Object.assign(asset, { label: 'original', digest: 'sha256:' + hash(r.content.get(asset.id)), download_count: 10 });
        await assert.rejects(attach(f, r), /Existing immutable asset changed/);
        assert.equal(r.views, 3, 'reject the postupload snapshot before byte readback');
        assert.equal(writes(r).length, complete ? 0 : 1);
      });
    }

    test(`preexisting ${name} download_count change after preupload is allowed during ${mode}`, async t => {
      const f = fixture(t);
      const r = remote(f, { onView: ({ views, assets }) => {
        if (views === 3) assets.find(a => a.name === name).download_count++;
      } });
      for (const owned of [installer, installer + '.sha256']) {
        if (complete || owned === name) existing(r, owned, owned === installer ? f.bytes : Buffer.from(`${hash(f.bytes)}  ${installer}\n`));
      }
      const asset = r.assets.find(a => a.name === name);
      Object.assign(asset, { label: 'original', digest: 'sha256:' + hash(r.content.get(asset.id)), download_count: 10 });
      const before = structuredClone(asset);
      assert.deepEqual((await attach(f, r)).uploaded, complete ? [] : [name === installer ? installer + '.sha256' : installer]);
      assert.deepEqual(asset, { ...before, download_count: 11 });
      assert.equal(writes(r).length, complete ? 0 : 1);
    });
  }
}

test('same bytes retry is immutable and performs no second upload', async t => {
  const f = fixture(t), r = remote(f);
  await attach(f, r);
  const ids = r.assets.map(a => a.id);
  assert.deepEqual((await attach(f, r)).uploaded, []);
  assert.deepEqual(r.assets.map(a => a.id), ids);
  assert.equal(writes(r).length, 1);
});

test('existing installer is downloaded and verified before missing checksum repair', async t => {
  const f = fixture(t), r = remote(f);
  existing(r, installer, f.bytes);
  assert.deepEqual((await attach(f, r)).uploaded, [installer + '.sha256']);
  assert.equal(r.assets.find(a => a.name === installer).id, 2);
  const download = r.calls.findIndex(a => a[1]?.endsWith('/releases/assets/2'));
  assert.ok(download >= 0 && download < r.calls.findIndex(a => a[0] === 'release'));
});

for (const name of [installer, installer + '.sha256']) {
  test(`conflicting existing ${name} fails before any write`, async t => {
    const f = fixture(t), r = remote(f);
    const bytes = name === installer ? Buffer.alloc(f.bytes.length) : Buffer.from(`${'0'.repeat(64)}  ${installer}\n`);
    existing(r, name, bytes);
    await assert.rejects(attach(f, r), /conflict/);
    assert.equal(writes(r).length, 0);
  });
}

test('post-upload download tamper is detected and retry never overwrites', async t => {
  const f = fixture(t), r = remote(f, { afterUpload: ({ content }) => content.set(2, Buffer.alloc(f.bytes.length)) });
  await assert.rejects(attach(f, r), /conflict/);
  await assert.rejects(attach(f, r), /conflict/);
  assert.equal(writes(r).length, 1);
});

test('local installer tamper while waiting fails before upload', async t => {
  const f = fixture(t), r = remote(f, { missing: 1 });
  let now = 0;
  await assert.rejects(attach(f, r, { clock: () => now, sleep: async ms => { now += ms; writeFileSync(join(f.directory, installer), Buffer.alloc(f.bytes.length)); } }), /hash mismatch/);
  assert.equal(writes(r).length, 0);
});

test('targetCommitish and dereferenced tag must each match the exact source SHA', async t => {
  for (const variant of ['target', 'tag']) {
    const f = fixture(t), r = remote(f, { tagSha: variant === 'tag' ? 'c'.repeat(40) : sha });
    if (variant === 'target') r.release.target_commitish = 'main';
    await assert.rejects(attach(f, r), /commit mismatch/);
    assert.equal(writes(r).length, 0);
  }
});

for (const state of ['missing', 'draft']) {
  test(`${state} release wait is bounded to ten minutes with injected time`, async t => {
    const f = fixture(t), r = remote(f, state === 'missing' ? { missing: 10000 } : {});
    if (state === 'draft') r.release.draft = true;
    let now = 0, sleeps = 0;
    await assert.rejects(attach(f, r, { clock: () => now, sleep: async ms => { now += ms; sleeps++; } }), /Timed out/);
    assert.equal(now, 600_000);
    assert.equal(sleeps, 120);
    assert.equal(writes(r).length, 0);
  });
}

test('draft may become published, but draft on completion is refused', async t => {
  const f = fixture(t);
  const r = remote(f, { onView: ({ views, release }) => { release.draft = views === 1 || views >= 4; } });
  let now = 0;
  await assert.rejects(attach(f, r, { clock: () => now, sleep: async ms => { now += ms; } }), /draft mismatch/);
});

for (const stderr of ['HTTP 401: bad credentials', 'HTTP 403: denied', 'HTTP 429: rate limited', 'HTTP 500: server', 'network connection failed', 'release not found', 'HTTP 4040']) {
  test(`non-404 failure is immediate: ${stderr}`, async t => {
    const f = fixture(t);
    let calls = 0;
    await assert.rejects(attachWindowsInstaller({ directory: f.directory, repository: 'o/r', source, run: () => { calls++; return { status: 1, stderr }; }, sleep: () => assert.fail('must not retry') }), /GitHub command failed/);
    assert.equal(calls, 1);
  });
}

test('waiting network calls are time-bounded by the remaining ten-minute budget', async t => {
  const f = fixture(t), r = remote(f, { missing: 10000 });
  let now = 0;
  const base = r.run;
  r.run = (cmd, args, opts) => {
    assert.ok(opts.timeout > 0 && opts.timeout <= 25 - now);
    now += Math.min(opts.timeout, 7);
    return base(cmd, args, opts);
  };
  await assert.rejects(attach(f, r, { clock: () => now, sleep: async ms => { now += ms; }, timeoutMs: 25, pollMs: 10 }), /Timed out/);
  assert.equal(now, 25);
  assert.equal(r.views, 2);
});

test('malformed remote asset IDs cannot become API or temporary-file paths', async t => {
  for (const id of [-1, '../escape', null]) {
    const f = fixture(t), r = remote(f);
    r.assets[0].id = id;
    await assert.rejects(attach(f, r), /Invalid remote asset/);
    assert.equal(writes(r).length, 0);
  }
});

test('invalid release shape is not treated as a draft and retried', async t => {
  const f = fixture(t), r = remote(f);
  r.release.draft = null;
  let now = 0;
  await assert.rejects(attach(f, r, { clock: () => now, sleep: async ms => { now += ms; }, timeoutMs: 100, pollMs: 10 }), /Invalid release/);
  assert.equal(now, 0);
});

test('completion rejects owned asset metadata mutation after byte readback', async t => {
  const f = fixture(t);
  const r = remote(f, { onView: ({ views, assets }) => { if (views === 4) assets.find(a => a.name === installer).size++; } });
  await assert.rejects(attach(f, r), /changed during readback/);
});

test('partial upload failure is repaired on retry without replacing installer', async t => {
  const f = fixture(t), r = remote(f);
  const base = r.run;
  let first = true;
  r.run = (cmd, args, opts) => {
    if (args[0] === 'release' && first) {
      first = false;
      base(cmd, [...args.slice(0, 4), ...args.slice(args.indexOf('--repo'))], opts);
      return { status: 1, stderr: 'network interrupted after installer upload' };
    }
    return base(cmd, args, opts);
  };
  await assert.rejects(attach(f, r), /network interrupted/);
  assert.equal(r.assets.length, 2);
  assert.deepEqual((await attach(f, r)).uploaded, [installer + '.sha256']);
  assert.equal(r.assets.find(a => a.name === installer).id, 2);
});

test('lightweight commit tags and paginated Android assets are supported', async t => {
  const f = fixture(t), r = remote(f);
  for (let id = 2; id <= 35; id++) r.assets.push({ id, name: `android-${id}.apk`, size: id, label: `label-${id}` });
  const original = structuredClone(r.assets);
  const base = r.run;
  r.run = (cmd, args, opts) => {
    if (args[1]?.includes('/git/ref/')) return ok({ object: { type: 'commit', sha } });
    if (args[1]?.endsWith('/releases/101/assets')) { assert.ok(args.includes('--paginate')); return ok([r.assets.slice(0, 30), r.assets.slice(30)]); }
    return base(cmd, args, opts);
  };
  await attach(f, r);
  assert.deepEqual(r.assets.slice(0, 35), original);
});

for (const change of ['metadata', 'android', 'tag', 'target']) {
  test(`post-upload ${change} mutation fails readback`, async t => {
    const f = fixture(t);
    const r = remote(f, { afterUpload: ({ release, assets }) => {
      if (change === 'metadata') release.body = 'edited';
      if (change === 'android') assets[0].id = 999;
      if (change === 'target') release.target_commitish = 'd'.repeat(40);
    } });
    if (change === 'tag') {
      const base = r.run;
      r.run = (cmd, args, opts) => writes(r).length && args[1]?.includes('/git/tags/') ? ok({ object: { type: 'commit', sha: 'd'.repeat(40) } }) : base(cmd, args, opts);
    }
    await assert.rejects(attach(f, r), /changed|mismatch/);
  });
}
