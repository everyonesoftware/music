import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { workflowJobs } from './helpers/workflow.mjs';

const directory = new URL('../.github/workflows/', import.meta.url);
const read = name => readFileSync(new URL(name, directory), 'utf8');
const triggers = text => '\n' + (text.split('\non:\n')[1]?.split('\npermissions:')[0] ?? '');

test('new Android releases omit build and installation boilerplate', () => {
  const publisher = readFileSync(new URL('../scripts/publish-android-release.mjs', import.meta.url), 'utf8');
  assert.match(publisher, /'--notes', ''/);
  for (const text of ['Android alpha build', 'Git commit count / Android versionCode:',
    'Install the signed APK.', 'Keep the same signing key', 'No offline downloads or Android Auto yet.']) {
    assert.ok(!publisher.includes(text), text);
  }
});

test('one workflow owns main pushes and pull requests without completion-triggered fan-out', () => {
  const files = readdirSync(directory).filter(name => /\.ya?ml$/.test(name));
  const mainPushes = files.filter(name => /\n  push:\n    branches: \[main\]/.test(triggers(read(name))));
  const pullRequests = files.filter(name => /\n  pull_request:/.test(triggers(read(name))));
  assert.deepEqual(mainPushes, ['ci.yml']);
  assert.deepEqual(pullRequests, ['ci.yml']);
  for (const file of files) assert.doesNotMatch(triggers(read(file)), /workflow_run:/, file);
});

test('the pipeline has five real jobs with browser prototypes parallel to read-only native builds', () => {
  const ci = read('ci.yml'), jobs = workflowJobs(ci);
  assert.deepEqual(Object.keys(jobs), ['validate', 'browser-prototypes', 'android', 'windows', 'release']);
  assert.doesNotMatch(ci, /uses: \.\/\.github\/workflows\//);
  assert.match(ci, /AUDIO_KEYSTORE_BASE64: \$\{\{ secrets.AUDIO_KEYSTORE_BASE64 \}\}/);
  assert.match(ci, /permissions:\n  contents: read\n  actions: read/);
  for (const platform of ['android', 'windows']) {
    assert.match(jobs[platform], /needs: validate/);
    assert.doesNotMatch(jobs[platform], /contents: write|needs:.*(?:android|windows)/);
    assert.ok(jobs[platform].includes(`needs.validate.outputs.${platform} == 'true'`));
  }
  assert.match(jobs.release, /contents: write/);
  assert.match(jobs.validate, /web: \$\{\{ steps.detect.outputs.web \}\}/);
  assert.match(jobs['browser-prototypes'], /needs: validate/);
  assert.match(jobs['browser-prototypes'], /if: needs.validate.outputs.web == 'true'/);
  assert.match(jobs['browser-prototypes'], /runs-on: ubuntu-24.04/);
  for (const command of ['npm ci', 'npx playwright install --with-deps chromium', 'npm test']) {
    assert.ok(jobs['browser-prototypes'].includes(command));
    assert.ok(!jobs.validate.includes(command));
  }
  assert.doesNotMatch(jobs['browser-prototypes'], /contents: write|secrets\.|upload-artifact|needs:.*(?:android|windows)/);
});

test('serialized release runs after native jobs even when an unchanged platform is skipped', () => {
  const release = workflowJobs(read('ci.yml')).release ?? '';
  assert.match(release, /needs: \[validate, android, windows, browser-prototypes\]/);
  assert.match(release, /always\(\) && !cancelled\(\)/);
  assert.match(release, /needs.validate.result == 'success'/);
  assert.match(release, /github.ref == 'refs\/heads\/main'/);
  assert.match(release, /github.event_name != 'pull_request'/);
  assert.match(release, /needs\['browser-prototypes'\].result == 'success' \|\| needs\['browser-prototypes'\].result == 'skipped'/);
  const commands = ['scripts/publish-android-release.mjs', 'scripts/publish-windows-main-release.mjs', 'scripts/publish-release-index.mjs', 'scripts/recover-windows-publication.mjs'];
  let previous = -1;
  for (const command of commands) {
    const index = release.indexOf(command);
    assert.ok(index > previous, command);
    previous = index;
  }
  for (const workflow of ['ci.yml', 'windows-publication-recovery.yml']) {
    const text = read(workflow);
    assert.match(text, /group: music-release-publication/);
    assert.match(text, /queue: max/);
    assert.doesNotMatch(text, /^concurrency:/m);
  }
});

test('final release enriches Android before draft publication and Windows only after legacy inventory validation', () => {
  const release = workflowJobs(read('ci.yml')).release;
  const android = release.slice(release.indexOf('- name: Publish and read back Android release assets'), release.indexOf('- name: Download this run\'s verified Windows inventory'));
  assert.ok(android.includes('node scripts/release-changelog.mjs --platform android --directory dist/android --repository "$GH_REPO"'));
  assert.ok(android.indexOf('scripts/release-changelog.mjs') < android.indexOf('scripts/publish-android-release.mjs'));
  assert.match(release, /scripts\/publish-windows-main-release\.mjs[^\n]*--changelog true/);
  assert.ok(workflowJobs(read('ci.yml')).validate.includes('tests/release-changelog.test.mjs'));
});

test('the actual Latest step requires a successful native publication and no failed native job', () => {
  const jobs = workflowJobs(read('ci.yml'));
  const step = jobs.release.split('- name: Publish release index and promote Latest')[1]?.split('\n      - name:')[0] ?? '';
  const condition = step.match(/if: \$\{\{ (.*) \}\}/)?.[1];
  assert.ok(condition, 'explicit success and native publication gates are required');
  const enabled = Function('success', 'cancelled', 'needs', 'steps', `return ${condition};`);
  const check = (android, windows, publishers, success = true, cancelled = false) => enabled(() => success, () => cancelled, { android: { result: android }, windows: { result: windows } }, { android_publish: { outcome: publishers[0] }, windows_publish: { outcome: publishers[1] } });
  for (const [android, windows, publishers, expected] of [
    ['success', 'skipped', ['success', 'skipped'], true], ['skipped', 'success', ['skipped', 'success'], true],
    ['success', 'success', ['success', 'success'], true], ['skipped', 'skipped', ['skipped', 'skipped'], false],
    ['failure', 'success', ['skipped', 'success'], false], ['success', 'cancelled', ['success', 'skipped'], false],
  ]) assert.equal(check(android, windows, publishers), expected);
  assert.equal(check('success', 'success', ['success', 'failure'], false), false);
  assert.equal(check('success', 'success', ['success', 'success'], true, true), false);
  assert.match(step, /ANDROID_REQUIRED: \$\{\{ needs.validate.outputs.android_changed \}\}/);
  assert.match(step, /WINDOWS_REQUIRED: \$\{\{ needs.validate.outputs.windows_changed \}\}/);
  assert.match(step, /ANDROID_PUBLISHED: \$\{\{ steps.android_publish.outcome == 'success' \}\}/);
  assert.match(step, /WINDOWS_PUBLISHED: \$\{\{ steps.windows_publish.outcome == 'success' \}\}/);
  assert.match(step, /--source "\$GITHUB_SHA" --android-required "\$ANDROID_REQUIRED" --windows-required "\$WINDOWS_REQUIRED" --android-published "\$ANDROID_PUBLISHED" --windows-published "\$WINDOWS_PUBLISHED"/);
  for (const path of ['publish-android-release', 'publish-release-index', 'update-index-rollout']) assert.ok(jobs.validate.includes(`tests/${path}.test.mjs`), path);
});

test('manual platform targeting and build-only trimming remain available', () => {
  const ci = read('ci.yml');
  assert.match(ci, /component:[\s\S]*?options: \[all, android, windows, maintenance\]/);
  assert.match(ci, /trimmed_experiment:[\s\S]*?type: boolean/);
  assert.match(ci, /-p:MusicTrimmed=\$\{\{ inputs.trimmed_experiment == true \}\}/);
  assert.match(ci, /!inputs.trimmed_experiment/);
  assert.match(ci, /node --test tests\/ci-workflow.test.mjs/);
});

test('native acceptance remains mandatory and no Windows preview publication path remains', () => {
  const ci = read('ci.yml'), jobs = workflowJobs(ci);
  assert.doesNotMatch(ci, /windows-release.py publish|--prerelease|web-build.yml|container:/);
  assert.match(jobs.windows ?? '', /scripts\/test-windows-installer.ps1/);
  assert.match(jobs.release ?? '', /needs.android.result == 'success'/);
  assert.match(jobs.release ?? '', /needs.windows.result == 'success'/);
});

test('release permits passed or unchanged browser checks but blocks failed or cancelled checks', () => {
  const release = workflowJobs(read('ci.yml')).release;
  const condition = release.match(/^    if: (.*)$/m)[1];
  // Evaluate the trusted local workflow expression with the supported contexts.
  const enabled = new Function('needs', 'github', 'inputs', 'always', 'cancelled', `return ${condition};`);
  for (const [result, expected] of [['success', true], ['skipped', true], ['failure', false], ['cancelled', false]]) {
    assert.equal(enabled(
      { validate: { result: 'success' }, 'browser-prototypes': { result } },
      { ref: 'refs/heads/main', event_name: 'push' },
      { trimmed_experiment: false }, () => true, () => false,
    ), expected, result);
  }
});
