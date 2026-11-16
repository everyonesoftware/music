import assert from 'node:assert/strict';
import test from 'node:test';
import { readWorkflow, workflowJob } from './helpers/workflow.mjs';

const ci = readWorkflow();
const workflow = workflowJob(ci, 'windows');
const release = workflowJob(ci, 'release');

test('native release subprocesses read UTF-8 source independently of the Windows locale', () => {
  const globalSettings = ci.split(/^jobs:/m)[0];
  assert.match(globalSettings + workflow, /env:\s+(?:#[^\n]*\n\s+)?PYTHONUTF8: '1'/);
});

test('flat Windows packaging builds only when selected by validation and cannot publish', () => {
  assert.match(workflow, /runs-on: windows-2025/);
  assert.match(workflow, /needs: validate/);
  assert.match(workflow, /if:.*needs.validate.outputs.windows == 'true'/);
  assert.match(workflow, /steps:/);
  assert.doesNotMatch(workflow, /uses: \.\/\.github\/workflows\/|contents: write|scripts\/windows-release.py publish|scripts\/android-release.py/);
  assert.match(release, /needs: \[validate, android, windows\]/);
  assert.match(release, /if:.*always\(\).* !cancelled\(\).*github.ref == 'refs\/heads\/main'.*github.event_name != 'pull_request'.*!inputs.trimmed_experiment/);
});

test('real installer smoke test gates the exact artifacts used for publication', () => {
  const smoke = workflow.indexOf('./scripts/test-windows-installer.ps1');
  const retain = workflow.indexOf('name: Retain the installer and update packages');
  assert.ok(smoke > 0, 'Missing Windows installer lifecycle test');
  assert.ok(retain > smoke, 'Installer must pass smoke test before retaining publishable artifacts');
  assert.match(workflow, /-InstallerPath dist\/windows-build\/releases\/MusicWindows-win-x64-Setup\.exe/);
  assert.match(workflow, /-EvidenceDirectory dist\/windows-smoke/);
  assert.match(workflow, /name: Retain Windows smoke diagnostics\s+if: always\(\)/);
  assert.match(workflow, /path: dist\/windows-smoke\//);
  assert.match(workflow, /name: music-windows-installer-\$\{\{ github.sha \}\}/);
  assert.match(release, /name: music-windows-installer-\$\{\{ github.sha \}\}/);
  assert.match(release, /path: dist\/windows-release/);
  assert.match(release, /digest-mismatch: error/);
  assert.ok(workflow.includes('node --test tests/windows-installer-smoke.test.mjs tests/windows-release-workflow.test.mjs tests/windows-shared-release.test.mjs'));
});

test('native lifecycle gate has an independent hard step timeout', () => {
  assert.match(workflow, /name: Install, launch and uninstall the packaged Windows app\s+id: smoke\s+timeout-minutes: 5/);
});

test('failed native launches get a bounded pinned debugger diagnostic before evidence upload', () => {
  assert.ok(workflow.includes("if: failure() && steps.smoke.outcome == 'failure'"));
  assert.ok(workflow.includes("hashFiles('dist/windows-smoke/app-exit.json') != ''"));
  assert.ok(workflow.includes('67a8e1d39421a5ef5309c7017327c6a70ea6215a0e56e47ec22a7e9ec3b914e6'));
  assert.ok(workflow.includes('--no-build --configuration Release --debug-output --symbols'));
  assert.match(workflow, /name: Diagnose native startup failure\s+if:[^\n]+\s+timeout-minutes: 4/);
  assert.ok(workflow.indexOf('name: Diagnose native startup failure') < workflow.indexOf('name: Retain Windows smoke diagnostics'));
});

test('unsigned Windows packages publish to stable main releases under one serialized release job', () => {
  assert.ok(workflow.includes('--allow-unsigned'));
  assert.ok(release.includes('node scripts/publish-windows-main-release.mjs'));
  assert.doesNotMatch(release, /scripts\/windows-release.py publish|--prerelease|node scripts\/attach-windows-installer.mjs/);
  assert.ok(release.includes('scripts/android-release.py publish'));
  assert.match(release, /contents: write/);
  assert.match(release, /concurrency:\s+group: music-release-publication\s+cancel-in-progress: false\s+queue: max/);
  assert.doesNotMatch(workflow, /contents: write/);
});

test('verified Windows feed publishes to the stable main release after Android, before recovery and refresh', () => {
  const commands = [
    'scripts/android-release.py publish',
    'node scripts/publish-windows-main-release.mjs',
    'node scripts/recover-windows-publication.mjs',
    'node scripts/current-downloads.mjs',
  ];
  let previous = -1;
  for (const command of commands) {
    const position = release.indexOf(command);
    assert.ok(position > previous, `${command} must exist after the preceding publication stage`);
    previous = position;
  }
  assert.ok(release.includes('node scripts/publish-windows-main-release.mjs --directory dist/windows-release --repository "$GH_REPO" --android-changed "$ANDROID_CHANGED"'));
  assert.match(release, /ANDROID_CHANGED: \$\{\{ needs.validate.outputs.android_changed \}\}/);
});
