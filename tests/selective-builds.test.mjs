// Unit fixtures only; no remote writes or production packages.
import test from 'node:test';
import { readWorkflow, workflowJob, workflowJobs } from './helpers/workflow.mjs';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';

test('platform ownership includes shared inputs, renames/deletions, branding dependencies and docs-only skips', async () => {
  const { affectedComponents } = await import('../scripts/selective-builds.mjs');
  for (const [paths, expected] of [
    [['android/app/src/main/java/Player.kt'], ['android']],
    [['apps/windows/Music.App/Program.cs'], ['windows']],
    [['mockups/android-library/index.html'], ['web']],
    [['package-lock.json'], ['web']],
    [['shared/protocol.json'], ['android', 'windows', 'web']],
    [['unknown-input.config'], ['android', 'windows', 'web']],
    [['tests/shared-api.test.mjs'], ['android', 'windows', 'web']],
    [['README.md', 'docs/CURRENT-DOWNLOADS.md', 'android/RELEASING.md'], []],
    [['scripts/current-downloads.mjs', 'tests/current-downloads.test.mjs', '.github/workflows/current-downloads.yml'], []],
    [['android/app/src/main/res/drawable/ic_audio_foreground.xml'], ['android', 'windows']],
    [['scripts/selective-builds.mjs'], ['android', 'windows', 'web']],
    [['.github/workflows/android-release.yml'], ['android']],
    [['scripts/ensure-windows-main-release.mjs'], ['windows']],
    [['tests/ensure-windows-main-release.test.mjs'], ['windows']],
  ]) assert.deepEqual(affectedComponents(paths), expected, paths.join(', '));
});

function history(t) {
  const root = mkdtempSync(join(tmpdir(), 'music-selective-test-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const git = (...args) => execFileSync('git', ['-C', root, ...args], { encoding: 'utf8' }).trim();
  git('init', '--quiet');
  git('config', 'user.name', 'Unit Fixture');
  git('config', 'user.email', 'fixture@example.invalid');
  function commit(path, text = 'fixture') {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), text);
    git('add', '--all'); git('commit', '--quiet', '-m', 'unit fixture');
    return git('rev-parse', 'HEAD');
  }
  return { root, git, commit };
}

test('compares actual Git trees against last publication, not the previous push, and preserves deletion paths', async t => {
  const { decideFromBaseline } = await import('../scripts/selective-builds.mjs');
  const h = history(t);
  const base = h.commit('android/player.kt');
  h.commit('android/player.kt', 'unpublished change from a failed build');
  h.commit('README.md', 'later docs push');
  assert.equal(decideFromBaseline({ root: h.root, component: 'android', baseline: base }).build, true);
  assert.equal(decideFromBaseline({ root: h.root, component: 'windows', baseline: base }).build, false);
  h.git('rm', 'android/player.kt'); h.git('commit', '--quiet', '-m', 'delete fixture');
  assert.equal(decideFromBaseline({ root: h.root, component: 'android', baseline: base }).build, true);
  assert.equal(decideFromBaseline({ root: h.root, component: 'windows', baseline: null }).build, true);
  assert.equal(decideFromBaseline({ root: h.root, component: 'windows', baseline: h.git('rev-parse', 'HEAD'), force: true }).build, true);
});

function publication(component, count, sha) {
  const windows = component === 'windows';
  const version = `0.1.${count}`, tag = `${windows ? 'windows-' : ''}v${version}`;
  const manifest = windows ? {
    count, version, tag, sha, platform: 'windows-x64', signing: 'unsigned-preview',
    artifacts: ['MusicWindows-win-x64-Setup.exe', `MusicWindows-${version}-win-x64-full.nupkg`, 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64'].map(name => ({ name, bytes: 42, sha256: 'b'.repeat(64) })),
  } : { count, version, tag, sha, apk: `Music-${version}.apk`, bytes: 42, sha256: 'b'.repeat(64) };
  const names = windows ? [...manifest.artifacts.map(a => a.name), 'windows-release.json', 'SHA256SUMS'] : [manifest.apk, `${manifest.apk}.sha256`, 'release.json'];
  const assets = names.map((name, index) => ({ id: count * 100 + index, name, size: ['windows-release.json', 'release.json'].includes(name) ? 1000 : 42, state: 'uploaded' }));
  const release = { id: count, tag_name: tag, target_commitish: sha, draft: false, prerelease: windows };
  const api = path => {
    if (path === 'releases?per_page=100') return [[release]];
    if (path.startsWith(`releases/${count}/assets?`)) return [assets.slice(0, 2), assets.slice(2)];
    if (path.startsWith('releases/assets/')) return manifest;
    if (path === `git/ref/tags/${tag}`) return { object: { type: 'commit', sha } };
    throw new Error('Unexpected endpoint ' + path);
  };
  return { release, manifest, assets, api };
}

test('publication baselines require complete platform assets, exact source metadata and matching tag', async () => {
  const { publishedBaseline } = await import('../scripts/selective-builds.mjs');
  for (const component of ['android', 'windows']) {
    const sha = 'a'.repeat(40), r = publication(component, 9, sha);
    assert.equal(publishedBaseline(component, { api: r.api }), sha);
    r.release.draft = true;
    assert.equal(publishedBaseline(component, { api: r.api }), null);
    r.release.draft = false;
    r.assets.pop();
    assert.equal(publishedBaseline(component, { api: r.api }), null);
  }
  const r = publication('android', 10, 'a'.repeat(40));
  r.manifest.sha = 'c'.repeat(40);
  assert.throws(() => publishedBaseline('android', { api: r.api }), /source/);
});

test('newer incomplete releases do not advance a baseline past the last complete publication', async () => {
  const { publishedBaseline } = await import('../scripts/selective-builds.mjs');
  const old = publication('android', 9, 'a'.repeat(40));
  const next = publication('android', 10, 'c'.repeat(40));
  next.assets.pop();
  const api = path => path === 'releases?per_page=100' ? [[next.release], [old.release]] : path.includes('/10/') ? next.api(path) : old.api(path);
  assert.equal(publishedBaseline('android', { api }), old.manifest.sha);
});

test('a stale rerun skips newer published source, while a diverged history fails closed', async t => {
  const { decideFromBaseline } = await import('../scripts/selective-builds.mjs');
  const h = history(t), old = h.commit('README.md');
  const newer = h.commit('android/player.kt');
  h.git('checkout', '--quiet', old);
  assert.equal(decideFromBaseline({ root: h.root, component: 'android', baseline: newer }).build, false);
  h.commit('apps/windows/player.cs');
  assert.throws(() => decideFromBaseline({ root: h.root, component: 'android', baseline: newer }), /diverges/);
});

test('native CI jobs gate SDK setup on validation outputs and preserve manual component selection', () => {
  const workflow = readWorkflow();
  const validate = workflowJob(workflow, 'validate');
  assert.deepEqual(Object.keys(workflowJobs(workflow)), ['validate', 'browser-prototypes', 'android', 'windows', 'release']);
  assert.match(workflow, /options: \[all, android, windows, maintenance\]/);
  assert.match(validate, /fetch-depth: 0/);
  assert.match(validate, /inputs\.component/);
  assert.match(validate, /github\.event_name == 'workflow_dispatch'/);
  assert.match(validate, /GH_TOKEN: \$\{\{ github.token \}\}/);
  assert.match(validate, /node scripts\/ci-selection.mjs/);
  assert.match(validate, /--target "\$TARGET_COMPONENT" --trimmed "\$TRIMMED" --github-output "\$GITHUB_OUTPUT"/);
  for (const component of ['android', 'windows']) {
    const job = workflowJob(workflow, component);
    assert.match(job, /needs: validate/);
    assert.ok(job.includes(`needs.validate.outputs.${component} == 'true'`));
    assert.ok(job.indexOf(`needs.validate.outputs.${component}`) < job.indexOf('steps:'));
    assert.doesNotMatch(job, /contents: write/);
  }
  const release = workflowJob(workflow, 'release');
  assert.match(release, /node scripts\/publish-windows-main-release.mjs/);
  assert.match(release, /ANDROID_CHANGED: \$\{\{ needs.validate.outputs.android_changed \}\}/);
});

test('web baselines bootstrap once and ignore PR/failure runs instead of inventing a deployed web release', async () => {
  const { publishedBaseline } = await import('../scripts/selective-builds.mjs');
  assert.equal(publishedBaseline('web', { api: () => null }), null);
  const source = 'a'.repeat(40);
  const api = () => [{ workflow_runs: [
    { conclusion: 'success', event: 'pull_request', head_branch: 'main', head_sha: 'b'.repeat(40) },
    { conclusion: 'failure', event: 'push', head_branch: 'main', head_sha: 'b'.repeat(40) },
    { conclusion: 'success', event: 'push', head_branch: 'main', head_sha: source },
  ] }];
  assert.equal(publishedBaseline('web', { api }), source);
});

test('CLI exercises real Git history, paginated API baselines, force controls and GitHub output booleans', async t => {
  const { main } = await import('../scripts/selective-builds.mjs');
  const h = history(t), base = h.commit('android/player.kt');
  h.commit('README.md');
  const remote = publication('android', 1, base);
  const run = (command, args, options) => {
    if (command === 'git') return spawnSync(command, args, options);
    assert.equal(command, 'gh');
    assert.equal(options.shell, false);
    const path = args[1].replace('repos/owner/music/', '');
    if (path.includes('per_page=100')) assert.ok(args.includes('--paginate') && args.includes('--slurp'));
    if (path.startsWith('releases/assets/')) assert.ok(args.includes('Accept: application/octet-stream'));
    return { status: 0, stdout: JSON.stringify(remote.api(path)), stderr: '' };
  };
  const outputPath = join(h.root, 'outputs');
  const argv = ['--repository', 'owner/music', '--component', 'android', '--github-output', outputPath];
  assert.equal(main(argv, { root: h.root, run, output: () => {} }).android.build, false);
  assert.equal(readFileSync(outputPath, 'utf8'), 'android=false\n');
  assert.equal(main([...argv, '--force', 'android'], { root: h.root, run, output: () => {} }).android.build, true);
  assert.equal(readFileSync(outputPath, 'utf8'), 'android=false\nandroid=true\n');
});

test('CLI rejects malformed options, inaccessible GitHub baselines and shallow checkouts', async t => {
  const { main, decideFromBaseline } = await import('../scripts/selective-builds.mjs');
  for (const argv of [[], ['--repository', '../bad', '--component', 'android'], ['--repository', 'o/r', '--component', 'other'], ['--unknown', 'x'], ['--repository', 'o/r', '--component', 'web', '--force', 'bad']]) {
    assert.throws(() => main(argv, { run: () => assert.fail('no commands allowed') }));
  }
  assert.throws(() => main(['--repository', 'o/r', '--component', 'web'], { run: () => ({ status: 1, stderr: 'HTTP 403: denied' }) }), /lookup failed/);
  assert.throws(() => decideFromBaseline({ root: '.', component: 'android', baseline: null, run: () => ({ status: 0, stdout: 'true\n' }) }), /Full history/);
});

test('browser studies run selectively after validation in parallel with native jobs without push path filters', () => {
  const workflow = readWorkflow();
  const validate = workflowJob(workflow, 'validate');
  const browser = workflowJob(workflow, 'browser-prototypes');
  assert.match(validate, /node scripts\/ci-selection.mjs/);
  assert.match(validate, /web: \$\{\{ steps.detect.outputs.web \}\}/);
  assert.match(browser, /needs: validate/);
  assert.match(browser, /if: needs.validate.outputs.web == 'true'/);
  assert.match(browser, /npm ci/);
  assert.match(browser, /npm test/);
  assert.doesNotMatch(validate, /npm ci|playwright install|run: npm test/);
  assert.match(workflow, /actions: read/);
  assert.doesNotMatch(workflow.split('workflow_dispatch:')[0], /paths:/);
});
