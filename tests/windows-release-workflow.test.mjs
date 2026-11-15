import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const workflow = readFileSync(new URL('../.github/workflows/windows-release.yml', import.meta.url), 'utf8');

test('native release subprocesses read UTF-8 source independently of the Windows locale', () => {
  assert.match(workflow, /env:\s+(?:#[^\n]*\n\s+)?PYTHONUTF8: '1'/);
});

test('Windows installers build for every main release, with build-only dispatch retained', () => {
  assert.match(workflow, /push:\s+branches: \[main\]/);
  assert.doesNotMatch(workflow.split('workflow_dispatch:')[0], /paths:/);
  assert.match(workflow, /publish_preview:[\s\S]*?default: false/);
  assert.match(workflow, /if: github\.ref == 'refs\/heads\/main' && \(github\.event_name == 'push' \|\| inputs\.publish_preview\)/);
});

test('real installer smoke test gates the exact artifacts used for publication', () => {
  const smoke = workflow.indexOf('./scripts/test-windows-installer.ps1');
  const retain = workflow.indexOf('name: Retain the installer and update packages');
  assert.ok(smoke > 0, 'Missing Windows installer lifecycle test');
  assert.ok(retain > smoke, 'Installer must pass smoke test before retaining publishable artifacts');
  assert.match(workflow, /-InstallerPath dist\/windows-build\/releases\/MusicWindows-win-x64-Setup\.exe/);
  assert.match(workflow, /-EvidenceDirectory dist\/windows-smoke/);
  assert.match(workflow, /name: Retain Windows smoke diagnostics\s+if: always\(\)/);
  assert.match(workflow, /needs: package/);
  assert.match(workflow, /path: dist\/windows-smoke\//);
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
});

test('Windows publication stays unsigned and isolated from Android latest', () => {
  assert.ok(workflow.includes('--allow-unsigned'));
  assert.ok(workflow.includes('scripts/windows-release.py publish'));
  assert.ok(!workflow.includes('scripts/android-release.py'));
  assert.match(workflow, /contents: write/);
  assert.match(workflow, /group: music-windows-release-publication/);
});

test('verified Windows installer is also attached to the matching main Music release', () => {
  const publish = workflow.indexOf('scripts/windows-release.py publish');
  const attach = workflow.indexOf('node scripts/attach-windows-installer.mjs');
  assert.ok(publish > 0 && attach > publish, 'Main release attachment must follow verified Windows publication');
  assert.ok(workflow.includes('node scripts/attach-windows-installer.mjs --directory dist/windows-release --repository "$GH_REPO"'));
});
