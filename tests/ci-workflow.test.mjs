import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { workflowJobs } from './helpers/workflow.mjs';

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

test('the pipeline has exactly four real jobs with parallel read-only native builds', () => {
  const ci = read('ci.yml'), jobs = workflowJobs(ci);
  assert.deepEqual(Object.keys(jobs), ['validate', 'android', 'windows', 'release']);
  assert.doesNotMatch(ci, /uses: \.\/\.github\/workflows\//);
  assert.match(ci, /AUDIO_KEYSTORE_BASE64: \$\{\{ secrets.AUDIO_KEYSTORE_BASE64 \}\}/);
  assert.match(ci, /permissions:\n  contents: read\n  actions: read/);
  for (const platform of ['android', 'windows']) {
    assert.match(jobs[platform], /needs: validate/);
    assert.doesNotMatch(jobs[platform], /contents: write|needs:.*(?:android|windows)/);
    assert.ok(jobs[platform].includes(`needs.validate.outputs.${platform} == 'true'`));
  }
  assert.match(jobs.release, /contents: write/);
});

test('serialized release runs after native jobs even when an unchanged platform is skipped', () => {
  const release = workflowJobs(read('ci.yml')).release ?? '';
  assert.match(release, /needs: \[validate, android, windows\]/);
  assert.match(release, /always\(\) && !cancelled\(\)/);
  assert.match(release, /needs.validate.result == 'success'/);
  assert.match(release, /github.ref == 'refs\/heads\/main'/);
  assert.match(release, /github.event_name != 'pull_request'/);
  const commands = ['scripts/android-release.py publish', 'scripts/publish-windows-main-release.mjs', 'scripts/recover-windows-publication.mjs', 'scripts/current-downloads.mjs'];
  let previous = -1;
  for (const command of commands) {
    const index = release.indexOf(command);
    assert.ok(index > previous, command);
    previous = index;
  }
  for (const workflow of ['ci.yml', 'current-downloads.yml', 'windows-publication-recovery.yml']) {
    const text = read(workflow);
    assert.match(text, /group: music-release-publication/);
    assert.match(text, /queue: max/);
    assert.doesNotMatch(text, /^concurrency:/m);
  }
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
