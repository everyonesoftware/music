import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';

const directory = new URL('../.github/workflows/', import.meta.url);
const read = name => readFileSync(new URL(name, directory), 'utf8');
const triggers = text => '\n' + (text.split('\non:\n')[1]?.split('\npermissions:')[0] ?? '');

test('one workflow owns main pushes and pull requests without completion-triggered fan-out', () => {
  const files = readdirSync(directory).filter(name => /\.ya?ml$/.test(name));
  const mainPushes = files.filter(name => /\n  push:\n    branches: \[main\]/.test(triggers(read(name))));
  const pullRequests = files.filter(name => /\n  pull_request:/.test(triggers(read(name))));
  assert.deepEqual(mainPushes, ['ci.yml']);
  assert.deepEqual(pullRequests, ['ci.yml']);
  for (const file of files) assert.doesNotMatch(triggers(read(file)), /workflow_run:/, file);
});

test('the single pipeline calls reusable platform jobs and explicitly grants publication permissions', () => {
  const ci = read('ci.yml');
  for (const name of ['web-build', 'windows-build', 'android-release', 'windows-release', 'windows-publication-recovery', 'current-downloads']) {
    assert.ok(ci.includes(`uses: ./.github/workflows/${name}.yml`), name);
    assert.match(triggers(read(`${name}.yml`)), /workflow_call:/, name);
  }
  assert.match(ci, /AUDIO_KEYSTORE_BASE64: \$\{\{ secrets.AUDIO_KEYSTORE_BASE64 \}\}/);
  assert.doesNotMatch(ci, /secrets: inherit/);
  assert.match(ci, /permissions:\n  contents: read\n  actions: read/);
  assert.match(ci, /android:\n[\s\S]*?permissions:\n      contents: write/);
  assert.match(ci, /windows-release:\n[\s\S]*?permissions:\n      contents: write/);
});

test('reconciliation and refresh wait for native jobs even when an unchanged platform is skipped', () => {
  const ci = read('ci.yml');
  const recovery = ci.split('\n  recovery:\n')[1]?.split('\n  downloads:\n')[0] ?? '';
  assert.match(recovery, /needs: \[android, windows, windows-release\]/);
  assert.match(recovery, /always\(\) && !cancelled\(\)/);
  assert.match(recovery, /github.ref == 'refs\/heads\/main'/);
  assert.match(recovery, /github.event_name != 'pull_request'/);
  const downloads = ci.split('\n  downloads:\n')[1] ?? '';
  assert.match(downloads, /needs: \[android, windows, windows-release, recovery\]/);
  assert.match(downloads, /always\(\) && !cancelled\(\)/);
  assert.match(downloads, /github.event_name != 'pull_request'/);
  assert.doesNotMatch(read('current-downloads.yml'), /workflow_run:/);
  assert.match(read('windows-publication-recovery.yml'), /schedule:/);
});

test('manual platform targeting and build-only trimming remain available', () => {
  const ci = read('ci.yml');
  assert.match(ci, /component:[\s\S]*?options: \[all, android, windows, web, maintenance\]/);
  assert.match(ci, /trimmed_experiment:[\s\S]*?type: boolean/);
  assert.match(ci, /trimmed_experiment: \$\{\{ inputs.trimmed_experiment == true \}\}/);
  assert.match(ci, /!inputs.trimmed_experiment/);
  assert.match(ci, /node --test tests\/ci-workflow.test.mjs/);
});
