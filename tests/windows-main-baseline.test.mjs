import test from 'node:test';
import assert from 'node:assert/strict';
import { publishedBaseline } from '../scripts/selective-builds.mjs';

function publication(count, { stable = true, sha = 'a'.repeat(40), manifestTag } = {}) {
  const version = `0.1.${count}`;
  const tag = `${stable ? '' : 'windows-'}v${version}`;
  const id = count * 10 + (stable ? 1 : 2);
  const artifacts = ['MusicWindows-win-x64-Setup.exe', `MusicWindows-${version}-win-x64-full.nupkg`, 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64'].map(name => ({ name, bytes: 42, sha256: 'b'.repeat(64) }));
  const manifest = { count, version, tag: manifestTag ?? `windows-v${version}`, sha, platform: 'windows-x64', signing: 'unsigned-preview', artifacts };
  const assets = [...artifacts.map(a => a.name), 'windows-release.json', 'SHA256SUMS', ...(stable ? ['MusicWindows-win-x64-Setup.exe.sha256'] : [])].map((name, i) => ({ id: id * 100 + i, name, state: 'uploaded', size: name === 'windows-release.json' ? 1500 : 42 }));
  return { release: { id, tag_name: tag, draft: false, prerelease: !stable, target_commitish: sha }, manifest, assets, tagObject: { type: 'commit', sha } };
}
function fixture(...items) {
  const calls = [];
  const api = path => {
    calls.push(path);
    if (path === 'releases?per_page=100') return items.map(item => [item.release]);
    for (const item of items) {
      if (path === `releases/${item.release.id}/assets?per_page=100`) return [item.assets.slice(0, 2), item.assets.slice(2)];
      if (path === `releases/assets/${item.assets.find(a => a.name === 'windows-release.json')?.id}`) return item.manifest;
      if (path === `git/ref/tags/${item.release.tag_name}`) return { object: item.tagObject };
    }
    throw new Error('Unexpected endpoint: ' + path);
  };
  return { api, calls };
}

test('Windows baseline accepts a stable full feed with legacy packaging metadata and verifies the actual stable tag', () => {
  const item = publication(44), remote = fixture(item);
  assert.equal(publishedBaseline('windows', remote), item.manifest.sha);
  assert.ok(remote.calls.includes('git/ref/tags/v0.1.44'));
  assert.ok(!remote.calls.includes('git/ref/tags/windows-v0.1.44'));
});

test('stable Windows inventory requires the installer checksum sidecar before advancing', () => {
  const old = publication(43, { stable: false, sha: 'c'.repeat(40) });
  const next = publication(44);
  next.assets = next.assets.filter(a => !a.name.endsWith('.exe.sha256'));
  assert.equal(publishedBaseline('windows', fixture(next, old)), old.manifest.sha);
});

test('Windows picks highest numeric complete version and prefers stable main on ties regardless of API order', () => {
  const legacy = publication(99, { stable: false, sha: 'c'.repeat(40) });
  const stable = publication(99);
  const older = publication(98, { sha: 'd'.repeat(40) });
  for (const items of [[legacy, stable, older], [older, stable, legacy]]) {
    assert.equal(publishedBaseline('windows', fixture(...items)), stable.manifest.sha);
  }
  const newer = publication(100, { stable: false, sha: 'e'.repeat(40) });
  assert.equal(publishedBaseline('windows', fixture(stable, newer)), newer.manifest.sha);
});

test('draft, prerelease-main, installer-only and incomplete stable inventories preserve the legacy bridge baseline', () => {
  for (const change of [
    item => { item.release.draft = true; },
    item => { item.release.prerelease = true; },
    item => { item.assets = item.assets.filter(a => a.name.includes('Setup.exe')); },
    item => { item.assets = item.assets.filter(a => a.name !== 'RELEASES-win-x64'); },
    item => { item.assets.find(a => a.name.endsWith('.nupkg')).state = 'new'; },
    item => { item.assets.find(a => a.name === 'SHA256SUMS').size = 0; },
    item => { item.assets.push({ ...item.assets[0] }); },
  ]) {
    const old = publication(43, { stable: false, sha: 'c'.repeat(40) });
    const next = publication(44);
    change(next);
    assert.equal(publishedBaseline('windows', fixture(next, old)), old.manifest.sha);
  }
});

test('stable manifests may identify actual stable tag but mismatched source, tags and artifact metadata fail closed', () => {
  const stableTag = publication(44, { manifestTag: 'v0.1.44' });
  assert.equal(publishedBaseline('windows', fixture(stableTag)), stableTag.manifest.sha);
  for (const change of [
    item => { item.manifest.tag = 'windows-v0.1.43'; },
    item => { item.manifest.sha = 'c'.repeat(40); },
    item => { item.manifest.count = 45; },
    item => { item.manifest.version = '0.1.45'; },
    item => { item.tagObject.sha = 'c'.repeat(40); },
    item => { item.manifest.artifacts[0].bytes += 1; },
    item => { item.manifest.artifacts[0].sha256 = 'bad'; },
    item => { item.manifest.signing = 'signed'; },
  ]) {
    const item = publication(44);
    change(item);
    assert.throws(() => publishedBaseline('windows', fixture(item)), /mismatch|Invalid Windows/);
  }
  const legacy = publication(43, { stable: false, manifestTag: 'v0.1.43' });
  assert.throws(() => publishedBaseline('windows', fixture(legacy)), /metadata mismatch/);
});
