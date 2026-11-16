// Behavioral fixtures only; no GitHub reads or writes.
import test from 'node:test';
import assert from 'node:assert/strict';
import { publishedBaseline } from '../scripts/selective-builds.mjs';
import { recoverWindowsPublications } from '../scripts/recover-windows-publication.mjs';
import { refreshDownloads } from '../scripts/current-downloads.mjs';
import { spawnSync } from 'node:child_process';

const successfulRun = (head_sha, overrides = {}) => ({
  conclusion: 'success', head_branch: 'main', event: 'push', head_sha, ...overrides,
});

test('web baseline prefers successful unified CI main pushes across pages', () => {
  for (const event of ['push']) {
    const calls = [];
    const api = (path, options) => {
      calls.push(path);
      assert.deepEqual(options, { paginate: true, optional: true });
      if (path.includes('/ci.yml/')) return [{ workflow_runs: [
        successfulRun('b'.repeat(40), { event: 'pull_request' }),
        successfulRun('b'.repeat(40), { event: 'schedule' }),
        successfulRun('b'.repeat(40), { conclusion: 'failure' }),
        successfulRun('b'.repeat(40), { head_branch: 'feature' }),
      ] }, { workflow_runs: [successfulRun('a'.repeat(40), { event })] }];
      return [{ workflow_runs: [successfulRun('c'.repeat(40))] }];
    };
    assert.equal(publishedBaseline('web', { api }), 'a'.repeat(40));
    assert.deepEqual(calls, ['actions/workflows/ci.yml/runs?branch=main&status=success&per_page=100']);
  }
});

test('targeted manual CI runs cannot hide web changes that were never validated', () => {
  const api = path => [{ workflow_runs: [successfulRun(path.includes('/ci.yml/') ? 'a'.repeat(40) : 'c'.repeat(40), { event: 'workflow_dispatch' })] }];
  assert.equal(publishedBaseline('web', { api }), 'c'.repeat(40));
});

test('web migration falls back to legacy checks only without an eligible CI baseline', () => {
  for (const ci of [null, [], [{ workflow_runs: [successfulRun('a'.repeat(40), { event: 'pull_request' })] }]]) {
    const calls = [];
    const api = path => {
      calls.push(path);
      return path.includes('/ci.yml/') ? ci : [{ workflow_runs: [successfulRun('c'.repeat(40))] }];
    };
    assert.equal(publishedBaseline('web', { api }), 'c'.repeat(40));
    assert.equal(calls.length, 2);
  }
  assert.equal(publishedBaseline('web', { api: () => null }), null);
});

test('web migration fails closed on malformed CI source or lookup failures', () => {
  assert.throws(() => publishedBaseline('web', { api: path => [{ workflow_runs: [successfulRun(path.includes('/ci.yml/') ? 'bad-sha' : 'c'.repeat(40))] }] }), /Invalid successful web source/);
  assert.throws(() => publishedBaseline('web', { api: path => {
    if (path.includes('/ci.yml/')) throw new Error('HTTP 403');
    return [{ workflow_runs: [successfulRun('c'.repeat(40))] }];
  } }), /HTTP 403/);
});

// Reaching inventory discovery proves the publisher gate opened. Stop before
// unrelated release processing; these fixtures must never perform writes.
const inventoryReached = new Error('inventory reached');
function publisherApi(workflow, runs, calls) {
  return (path, options) => {
    calls.push(path);
    if (path === 'actions/runs?branch=main&per_page=100') {
      assert.deepEqual(options, { paginate: true });
      return [{ workflow_runs: [] }, { workflow_runs: runs.map(run => ({ path: `.github/workflows/${workflow}`, ...run })) }];
    }
    assert.equal(path, 'releases?per_page=100');
    throw inventoryReached;
  };
}
const publishers = {
  recovery: options => recoverWindowsPublications({ root: '.', repository: 'owner/music', ...options }),
  downloads: options => refreshDownloads({ repository: 'owner/music', publish: true, ...options }),
};

for (const [name, publish] of Object.entries(publishers)) {
  test(`${name} defers other active unified and legacy publishers even with own run excluded`, async () => {
    for (const workflow of ['ci.yml', 'android-release.yml', 'windows-release.yml', 'windows-publication-recovery.yml']) {
      for (const status of ['queued', 'in_progress', 'waiting', 'pending']) {
        const calls = [];
        const api = publisherApi(workflow, [{ id: 123, status: 'in_progress' }, { id: 124, status }], calls);
        assert.deepEqual(await publish({ api, currentRunId: '123' }), { deferred: true });
        assert.ok(!calls.includes('releases?per_page=100'));
      }
    }
  });

  test(`${name} excludes exactly its own run, not prefixes or missing run identity`, async () => {
    for (const workflow of ['ci.yml', 'windows-publication-recovery.yml']) {
      for (const id of [123, '123']) {
        const calls = [];
        await assert.rejects(async () => publish({ api: publisherApi(workflow, [{ id, status: 'in_progress' }], calls), currentRunId: '123' }), error => error === inventoryReached);
        assert.deepEqual(calls, ['actions/runs?branch=main&per_page=100', 'releases?per_page=100']);
      }
      for (const currentRunId of ['', '12', '1234', '0123']) {
        assert.deepEqual(await publish({ api: publisherApi(workflow, [{ id: 123, status: 'in_progress' }], []), currentRunId }), { deferred: true });
      }
      assert.deepEqual(await publish({ api: publisherApi(workflow, [{ status: 'in_progress' }], []), currentRunId: '' }), { deferred: true });
    }
  });

  test(`${name} ignores completed unified runs regardless of conclusion`, async () => {
    await assert.rejects(async () => publish({ api: publisherApi('ci.yml', ['success', 'failure', 'cancelled'].map((conclusion, id) => ({ id, conclusion, status: 'completed' })), []), currentRunId: '' }), error => error === inventoryReached);
  });
}

test('both publishers default to GITHUB_RUN_ID without leaking test environment', () => {
  const script = `
    import { recoverWindowsPublications } from './scripts/recover-windows-publication.mjs';
    import { refreshDownloads } from './scripts/current-downloads.mjs';
    const reached = new Error('inventory reached');
    const api = path => {
      if (path === 'actions/runs?branch=main&per_page=100') return [{ workflow_runs: [{ id: 123, status: 'in_progress' }] }];
      throw reached;
    };
    for (const publish of [() => recoverWindowsPublications({ root: '.', repository: 'owner/music', api }), () => refreshDownloads({ repository: 'owner/music', publish: true, api })]) {
      try { console.log(JSON.stringify(await publish())); }
      catch (error) { if (error !== reached) throw error; console.log('inventory reached'); }
    }
  `;
  for (const ownId of ['123', undefined]) {
    const env = { ...process.env };
    delete env.GITHUB_RUN_ID;
    if (ownId !== undefined) env.GITHUB_RUN_ID = ownId;
    const result = spawnSync(process.execPath, ['--input-type=module', '-e', script], { cwd: new URL('..', import.meta.url), env, encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    const expected = ownId ? 'inventory reached' : '{"deferred":true}';
    assert.deepEqual(result.stdout.trim().split('\n'), [expected, expected]);
  }
});
