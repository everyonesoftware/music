import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildChangelog, discoverPublications, enrichManifest, generateChangelog } from '../scripts/release-changelog.mjs';

function history(t) {
  const root = mkdtempSync(join(tmpdir(), 'music-changelog-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const git = (...args) => {
    const r = spawnSync('git', ['-C', root, ...args], { encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
    return r.stdout.trim();
  };
  git('init', '-q'); git('config', 'user.name', 'Fixture'); git('config', 'user.email', 'fixture@example.invalid'); git('config', 'commit.gpgsign', 'false');
  const commit = (path, subject) => {
    mkdirSync(join(root, path, '..'), { recursive: true });
    writeFileSync(join(root, path), subject); git('add', '-A'); git('commit', '-qm', subject);
    return { version: `0.1.${git('rev-list', '--count', 'HEAD')}`, sha: git('rev-parse', 'HEAD') };
  };
  return { root, git, commit };
}

test('complete cumulative platform segments start at the earliest verified publication, not arbitrary tags', t => {
  const f = history(t);
  f.commit('README.md', 'unpublished past');
  const first = f.commit('android/app/a', '[verified] feat(android): Add playlists');
  f.git('tag', 'v0.1.2');
  const fix = f.commit('android/app/b', '[verified] fix(player): Keep queue position');
  const other = f.commit('apps/windows/a', 'feat(windows): Windows navigation');
  const second = f.commit('docs/release.md', 'docs: Explain releases');
  const head = f.commit('shared/schema', 'feat: Shared support');
  const result = buildChangelog({ root: f.root, platform: 'android', head, publications: [second, first] });
  assert.deepEqual(result, { schema: 1, platform: 'android', head, releases: [
    { ...first, commits: [] },
    { ...second, commits: [
      { sha: fix.sha, subject: '[verified] fix(player): Keep queue position', kind: 'fix', text: 'Keep queue position' },
      { sha: other.sha, subject: 'feat(windows): Windows navigation', kind: null, text: null },
      { sha: second.sha, subject: 'docs: Explain releases', kind: null, text: null },
    ] },
    { ...head, commits: [{ sha: head.sha, subject: 'feat: Shared support', kind: 'improvement', text: 'Shared support' }] },
  ] });
});
export { history };

test('retains the original first line verbatim, not Git pretty-printed subject paragraphs', t => {
  const f = history(t), first = f.commit('android/a', 'first');
  f.git('commit', '--allow-empty', '--cleanup=verbatim', '-qm', '[verified] fix: Preserve subject  \ncontinuation line\n\nBody');
  const head = { version: '0.1.2', sha: f.git('rev-parse', 'HEAD') };
  const result = buildChangelog({ root: f.root, platform: 'android', head, publications: [first] });
  assert.equal(result.releases[1].commits[0].subject, '[verified] fix: Preserve subject  ');
  assert.equal(result.releases[1].commits[0].text, 'Preserve subject');
});

test('normalizes summary CR and NUL before trimming while preserving raw Git subjects for both consumers', t => {
  const f = history(t), first = f.commit('shared/schema', 'first');
  const subjects = ['fix: Original\rcarriage', 'fix: Original\0nul', '[verified] fix: \0Original\rcarriage\0 '];
  const texts = ['Original carriage', 'Original nul', 'Original carriage'];
  const shas = subjects.map((subject, index) => {
    // Git's commit CLI cannot accept NUL arguments; write a real raw commit
    // object with a relevant shared-path diff instead of mocking Git output.
    writeFileSync(join(f.root, 'shared/schema'), `revision ${index}`);
    f.git('add', 'shared/schema');
    const raw = `tree ${f.git('write-tree')}\nparent ${f.git('rev-parse', 'HEAD')}\nauthor Fixture <fixture@example.invalid> 1700000000 +0000\ncommitter Fixture <fixture@example.invalid> 1700000000 +0000\n\n${subject}\nbody ignored\n`;
    const result = spawnSync('git', ['-C', f.root, 'hash-object', '--literally', '-t', 'commit', '-w', '--stdin'], { input: raw, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    const sha = result.stdout.trim();
    f.git('reset', '--hard', sha);
    assert.equal(f.git('cat-file', 'commit', sha).split('\n\n')[1].split('\n')[0], subject);
    return sha;
  });
  const head = { version: '0.1.4', sha: shas.at(-1) };
  for (const platform of ['android', 'windows']) {
    const changelog = buildChangelog({ root: f.root, platform, head, publications: [first] });
    // Exercise the serialized wire payload, including JSON control escaping.
    const payload = JSON.parse(enrichManifest({ manifest: { ...head, count: 4 }, changelog, root: f.root, platform }));
    const commits = payload.changelog.releases[1].commits;
    assert.deepEqual(commits.map(c => c.subject), subjects);
    assert.deepEqual(commits.map(c => c.sha), shas);
    // Producer-to-consumer parity: Android validLine rejects LF/CR/NUL;
    // Windows SingleLine rejects LF/CR. Raw subject details only reject LF.
    for (const commit of commits) {
      assert.equal(commit.kind, 'fix', 'relevant changes must not be silently skipped');
      assert.ok(commit.text.trim().length > 0 && !/[\n\r\0]/.test(commit.text), 'Android summary contract');
      assert.ok(commit.text.trim().length > 0 && !/[\n\r]/.test(commit.text), 'Windows summary contract');
      assert.ok(!commit.subject.includes('\n'), 'both consumers accept original first-line details');
    }
    assert.deepEqual(commits.map(c => c.text), texts);
  }
});

test('merge ancestry includes side-branch commits once in topological order and rename source paths', t => {
  const f = history(t), first = f.commit('android/a', 'first');
  f.git('checkout', '-qb', 'side');
  const side = f.commit('android/side', 'feat: Side branch');
  f.git('checkout', '-q', '-');
  const main = f.commit('apps/windows/a', 'feat: Windows only');
  f.git('merge', '--no-ff', '-qm', 'Merge playback', 'side');
  const merge = f.git('rev-parse', 'HEAD');
  f.git('mv', 'android/a', 'apps/windows/moved'); f.git('commit', '-qm', 'fix: Move source');
  const head = { version: '0.1.5', sha: f.git('rev-parse', 'HEAD') };
  const result = buildChangelog({ root: f.root, platform: 'android', head, publications: [first] });
  const commits = result.releases[1].commits;
  assert.equal(commits.length, 4);
  assert.equal(new Set(commits.map(c => c.sha)).size, 4);
  assert.ok(commits.findIndex(c => c.sha === side.sha) < commits.findIndex(c => c.sha === merge));
  assert.equal(commits.find(c => c.sha === main.sha).kind, null);
  assert.equal(commits.at(-1).kind, 'fix');
});

test('rejects shallow, missing, conflicting, divergent and misversioned publication evidence', t => {
  const f = history(t), first = f.commit('android/a', 'first'), head = f.commit('android/b', 'second');
  const build = publications => buildChangelog({ root: f.root, platform: 'android', head, publications });
  assert.throws(() => buildChangelog({ root: f.root, platform: 'android', head: { ...head, version: head.version + '\n' }, publications: [first] }), /version/);
  assert.throws(() => build([{ ...first, version: '0.1.3' }]), /newer/);
  assert.throws(() => build([{ ...first, sha: '0'.repeat(40) }]), /git failed/);
  assert.throws(() => build([first, { ...first, sha: head.sha }]), /Conflicting/);
  f.git('checkout', '-qb', 'side', first.sha);
  const divergent = f.commit('android/c', 'divergent');
  assert.throws(() => build([divergent]), /Conflicting/);
  f.git('checkout', '-q', '-');
  writeFileSync(join(f.root, '.git/shallow'), first.sha + '\n');
  assert.throws(() => build([first]), /Full history/);
});

test('real CLI enriches the workflow Android directory before unchanged Python validation, with only read-only GitHub calls', t => {
  const f = history(t), first = f.commit('android/a', 'first'), head = f.commit('android/b', 'fix: Playback');
  const directory = join(f.root, 'dist'); mkdirSync(directory);
  const { createHash } = process.getBuiltinModule('node:crypto');
  const apk = `Music-${head.version}.apk`, sha256 = createHash('sha256').update('APK').digest('hex');
  const manifest = { ...head, tag: `v${head.version}`, count: 2, apk, bytes: 3, sha256 };
  writeFileSync(join(directory, apk), 'APK'); writeFileSync(join(directory, apk + '.sha256'), `${sha256}  ${apk}\n`);
  writeFileSync(join(directory, 'release.json'), JSON.stringify(manifest));
  const bin = join(f.root, 'bin'); mkdirSync(bin);
  const fixture = publicationApi([first]);
  const responses = {};
  for (const path of ['releases?per_page=100', 'releases/1/assets?per_page=100', 'releases/assets/100', `git/ref/tags/v${first.version}`]) responses[`repos/owner/music/${path}`] = fixture.api(path);
  writeFileSync(join(bin, 'responses.json'), JSON.stringify(responses));
  writeFileSync(join(bin, 'gh'), '#!/usr/bin/env node\n' + `const fs = require('node:fs'); const responses=JSON.parse(fs.readFileSync(${JSON.stringify(join(bin, 'responses.json'))})); const args=process.argv.slice(2); if(args[0]!=='api'||!(args[1] in responses)||args.includes('--method')) process.exit(2); process.stdout.write(JSON.stringify(responses[args[1]]));`, { mode: 0o755 });
  const command = readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8').match(/^          (node scripts\/release-changelog\.mjs --platform android[^\n]+)$/m)?.[1];
  assert.ok(command, 'exercise the actual workflow CLI, not a separately maintained argument list');
  const shell = command.replace('node scripts/release-changelog.mjs', `${JSON.stringify(process.execPath)} ${JSON.stringify(fileURLToPath(new URL('../scripts/release-changelog.mjs', import.meta.url)))}`).replace('dist/android', JSON.stringify(directory)) + ` --root ${JSON.stringify(f.root)}`;
  const result = spawnSync('bash', ['-euo', 'pipefail', '-c', shell], { encoding: 'utf8', env: { ...process.env, GH_REPO: 'owner/music', PATH: bin + ':' + process.env.PATH } });
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(JSON.parse(readFileSync(join(directory, 'release.json'))).changelog, buildChangelog({ root: f.root, platform: 'android', head, publications: [first] }));
  assert.equal(readFileSync(join(directory, apk), 'utf8'), 'APK');
  assert.equal(readFileSync(join(directory, apk + '.sha256'), 'utf8'), `${sha256}  ${apk}\n`);
});

test('enrichment is additive, compact and refuses corrupt history or oversized complete manifests', t => {
  const f = history(t), first = f.commit('android/a', 'first'), head = f.commit('android/b', 'fix: Second');
  const changelog = buildChangelog({ root: f.root, platform: 'android', head, publications: [first] });
  const manifest = { ...head, count: 2, tag: 'v0.1.2', apk: 'Music-0.1.2.apk', bytes: 10, sha256: 'a'.repeat(64) };
  const bytes = enrichManifest({ manifest, changelog, root: f.root, platform: 'android' });
  assert.deepEqual(JSON.parse(bytes), { ...manifest, changelog });
  assert.equal(bytes, JSON.stringify({ ...manifest, changelog }) + '\n');
  assert.equal(Object.hasOwn(manifest, 'changelog'), false);
  const bad = structuredClone(changelog); bad.releases[1].commits[0].subject = 'invented';
  assert.throws(() => enrichManifest({ manifest, changelog: bad, root: f.root, platform: 'android' }), /history/);
  assert.throws(() => enrichManifest({ manifest: { ...manifest, huge: 'x'.repeat(1024 * 1024) }, changelog, root: f.root, platform: 'android' }), /size|MiB/);
  assert.throws(() => enrichManifest({ manifest: { ...manifest, huge: 'x'.repeat(65536) }, changelog, root: f.root, platform: 'android', compatibilityLimit: 65536 }), /compatibility|64/);
});

function publicationApi(entries, mutate = () => {}) {
  const calls = [];
  const releases = entries.map((e, i) => ({ id: i + 1, tag_name: `v${e.version}`, target_commitish: e.sha, draft: false, prerelease: false }));
  const api = (path) => {
    calls.push(path);
    if (path === 'releases?per_page=100') return releases.map(r => [r]);
    const match = /releases\/(\d+)\/assets\?per_page=100/.exec(path);
    if (match) {
      const i = Number(match[1]) - 1, e = entries[i];
      const assets = [{ id: 100 + i, name: 'release.json', size: 300, state: 'uploaded' }, { id: 200 + i, name: `Music-${e.version}.apk`, size: 10, state: 'uploaded' }, { id: 300 + i, name: `Music-${e.version}.apk.sha256`, size: 100, state: 'uploaded' }];
      mutate('assets', assets, i);
      return [assets];
    }
    const asset = /releases\/assets\/(\d+)/.exec(path);
    if (asset) {
      const i = Number(asset[1]) - 100, e = entries[i];
      const manifest = { ...e, tag: `v${e.version}`, count: Number(e.version.slice(4)), apk: `Music-${e.version}.apk`, bytes: 10, sha256: 'a'.repeat(64) };
      mutate('manifest', manifest, i);
      return manifest;
    }
    const ref = releases.find(r => path === `git/ref/tags/${r.tag_name}`);
    if (ref) { const value = { object: { type: 'commit', sha: ref.target_commitish } }; mutate('ref', value, ref.id - 1); return value; }
    assert.fail(path);
  };
  return { api, calls };
}

test('discovery verifies all actual complete publications, paginates and caches reads', t => {
  const f = history(t), first = f.commit('android/a', 'first'), second = f.commit('android/b', 'second');
  const fixture = publicationApi([second, first]);
  assert.deepEqual(discoverPublications({ platform: 'android', api: fixture.api }), [first, second]);
  assert.equal(new Set(fixture.calls).size, fixture.calls.length);
});

test('a historical rerun computes only boundaries through its own immutable offered head', t => {
  const f = history(t), first = f.commit('android/a', 'first'), head = f.commit('android/b', 'second'), newer = f.commit('android/c', 'newer');
  const fixture = publicationApi([newer, head, first]);
  const result = generateChangelog({ root: f.root, platform: 'android', head, repository: 'owner/music', api: fixture.api });
  assert.deepEqual(result.changelog.releases.map(r => r.version), [first.version, head.version]);
  assert.equal(result.publishedManifest.sha, head.sha);
  assert.throws(() => generateChangelog({ root: f.root, platform: 'android', head, repository: 'owner/music\n', api: fixture.api }), /repository/);
});

test('publication readers accept cumulative manifests up to 1 MiB, measuring UTF-8 bytes', t => {
  const f = history(t), first = f.commit('android/a', 'first');
  const fixture = publicationApi([first], (kind, value) => { if (kind === 'assets') value[0].size = 70000; if (kind === 'manifest') value.padding = 'x'.repeat(66000); });
  assert.deepEqual(discoverPublications({ platform: 'android', api: fixture.api }), [first]);
  const tooLarge = publicationApi([first], (kind, value) => { if (kind === 'manifest') value.padding = '🎵'.repeat(300000); });
  assert.throws(() => discoverPublications({ platform: 'android', api: tooLarge.api }), /mismatch|manifest/);
});

test('Windows history independently verifies legacy and stable full inventories, annotated refs and skipped Android-only releases', t => {
  const f = history(t), first = f.commit('apps/windows/a', 'first');
  const android = f.commit('android/a', 'Android only');
  const head = f.commit('apps/windows/b', 'fix: Windows update');
  const releases = [{ id: 1, tag_name: `windows-v${first.version}`, target_commitish: first.sha, draft: false, prerelease: true }, { id: 2, tag_name: `v${android.version}`, target_commitish: android.sha, draft: false, prerelease: false }, { id: 3, tag_name: `v${head.version}`, target_commitish: head.sha, draft: false, prerelease: false }];
  const api = path => {
    if (path === 'releases?per_page=100') return [[releases[2]], [releases[1], releases[0]]];
    const match = /releases\/(\d+)\/assets\?/.exec(path);
    const assetMatch = /releases\/assets\/(\d+)/.exec(path);
    if (match || assetMatch) {
      const id = Number((match || assetMatch)[1]), r = releases[id - 1], version = r.tag_name.replace(/^(?:windows-)?v/, '');
      const names = ['MusicWindows-win-x64-Setup.exe', `MusicWindows-${version}-win-x64-full.nupkg`, 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64'];
      if (match) return [id === 2 ? [{ id: 22, name: 'release.json', size: 50, state: 'uploaded' }] : [...names, 'windows-release.json', 'SHA256SUMS', ...(id === 3 ? [names[0] + '.sha256'] : [])].map(name => ({ id, name, size: 200, state: 'uploaded' }))];
      return { version, count: Number(version.slice(4)), sha: r.target_commitish, tag: `windows-v${version}`, platform: 'windows-x64', signing: 'unsigned-preview', artifacts: names.map(name => ({ name, bytes: 200, sha256: 'a'.repeat(64) })) };
    }
    const tag = releases.find(r => path === `git/ref/tags/${r.tag_name}`);
    if (tag) return { object: { type: tag.id === 1 ? 'tag' : 'commit', sha: tag.id === 1 ? 'a'.repeat(40) : tag.target_commitish } };
    if (path === 'git/tags/' + 'a'.repeat(40)) return { object: { type: 'commit', sha: first.sha } };
    assert.fail(path);
  };
  const publications = discoverPublications({ platform: 'windows', api });
  assert.deepEqual(publications, [first, head]);
  const changelog = buildChangelog({ root: f.root, platform: 'windows', head, publications });
  assert.equal(changelog.releases[1].commits[0].sha, android.sha);
  assert.equal(changelog.releases[1].commits[0].kind, null);
  assert.equal(changelog.releases[1].commits[1].kind, 'fix');
});

test('discovery excludes incomplete platform publications but rejects tampered completed ones', t => {
  const f = history(t), first = f.commit('android/a', 'first'), second = f.commit('android/b', 'second');
  const incomplete = publicationApi([first, second], (kind, value, i) => { if (kind === 'assets' && i === 1) value.pop(); });
  assert.deepEqual(discoverPublications({ platform: 'android', api: incomplete.api }), [first]);
  for (const kind of ['manifest', 'ref']) {
    const bad = publicationApi([first], (k, value) => { if (k === kind) { if (k === 'manifest') value.sha = 'b'.repeat(40); else value.object.sha = 'b'.repeat(40); } });
    assert.throws(() => discoverPublications({ platform: 'android', api: bad.api }), /mismatch/);
  }
});
