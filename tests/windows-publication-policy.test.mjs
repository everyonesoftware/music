import test from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const policy = new URL('../scripts/windows-publication-policy.mjs', import.meta.url);
const check = (androidChanged, androidOutcome, windowsOutcome = 'success') => spawnSync(process.execPath, [fileURLToPath(policy)], {
  env: { ...process.env, ANDROID_CHANGED: androidChanged, ANDROID_PUBLISH_OUTCOME: androidOutcome, WINDOWS_DOWNLOAD_OUTCOME: windowsOutcome },
  encoding: 'utf8',
});

test('ready Windows with changed but skipped Android fails with actionable rerun guidance', () => {
  const result = check('true', 'skipped');
  assert.equal(result.status, 1);
  assert.match(result.stderr, /Android publication.*skipped/);
  assert.match(result.stderr, /component=all/);
  assert.match(result.stderr, /Windows-only/);
});

test('workflow runs the policy instead of silently gating Windows on Android success', () => {
  const workflow = readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8');
  const step = workflow.split('- name: Publish Windows installer and full update feed in the Music release')[1]?.split('\n      - name:')[0] ?? '';
  assert.match(step, /if: \$\{\{ !cancelled\(\) && steps.windows_download.outcome == 'success' \}\}/);
  assert.match(step, /ANDROID_CHANGED: \$\{\{ needs.validate.outputs.android_changed \}\}/);
  assert.match(step, /ANDROID_PUBLISH_OUTCOME: \$\{\{ steps.android_publish.outcome \}\}/);
  assert.match(step, /WINDOWS_DOWNLOAD_OUTCOME: \$\{\{ steps.windows_download.outcome \}\}/);
  assert.match(step, /run: \|\n\s+node scripts\/windows-publication-policy.mjs\n\s+node scripts\/publish-windows-main-release.mjs/);
});

// Execute the actual workflow shell, substituting only the live publisher.
const workflow = readFileSync(new URL('../.github/workflows/ci.yml', import.meta.url), 'utf8');
const step = workflow.split('- name: Publish Windows installer and full update feed in the Music release')[1].split('\n      - name:')[0];
const condition = step.match(/if: \$\{\{ (.*) \}\}/)[1];
const run = step.split('run: |\n')[1].split('\n').map(line => line.replace(/^          /, '')).join('\n');
const attempt = (changed, android, windows = 'success', cancelled = false) => {
  const enabled = Function('cancelled', 'steps', `return ${condition}`)(() => cancelled, { windows_download: { outcome: windows } });
  if (!enabled) return { status: 0, stdout: '', stderr: '' };
  return spawnSync('bash', ['-e', '-c', `node() { if [[ "$1" == scripts/publish-windows-main-release.mjs ]]; then printf 'PUBLISH_ATTEMPT\\n'; else command node "$@"; fi; }\n${run}`], {
    cwd: new URL('../', import.meta.url), encoding: 'utf8',
    env: { ...process.env, ANDROID_CHANGED: changed, ANDROID_PUBLISH_OUTCOME: android, WINDOWS_DOWNLOAD_OUTCOME: windows },
  });
};

test('actual release shell fails before publication when required Android is unavailable', () => {
  for (const outcome of ['skipped', 'failure', 'cancelled', '']) {
    const result = attempt('true', outcome);
    assert.equal(result.status, 1, outcome);
    assert.doesNotMatch(result.stdout, /PUBLISH_ATTEMPT/);
    assert.match(result.stderr, /component=all/);
  }
});

test('actual release shell permits unchanged Android or successfully published changed Android', () => {
  for (const [changed, outcome] of [['false', 'skipped'], ['false', 'failure'], ['true', 'success']]) {
    const result = attempt(changed, outcome);
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, 'PUBLISH_ATTEMPT\n');
  }
});

test('unready Windows and cancelled workflows do not attempt publication or policy failure', () => {
  for (const outcome of ['skipped', 'failure', 'cancelled', '']) {
    assert.deepEqual(attempt('true', 'skipped', outcome), { status: 0, stdout: '', stderr: '' });
  }
  assert.deepEqual(attempt('true', 'skipped', 'success', true), { status: 0, stdout: '', stderr: '' });
});