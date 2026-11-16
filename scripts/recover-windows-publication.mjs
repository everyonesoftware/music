#!/usr/bin/env node
// Recover attachment from immutable published inventory; never build/repackage.
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { deriveSource, validateLocal, attachWindowsInstaller } from './attach-windows-installer.mjs';
import { main as ensureMain } from './ensure-windows-main-release.mjs';
import { publishedBaseline, decideFromBaseline } from './selective-builds.mjs';

export async function recoverWindowsPublications({ root, repository, api, run = spawnSync, ensure = ensureMain, attach = attachWindowsInstaller, currentRunId = process.env.GITHUB_RUN_ID ?? '',
  androidChanged = sourceRoot => decideFromBaseline({ root: sourceRoot, component: 'android', baseline: publishedBaseline('android', { api }), run }).build }) {
  if (!/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(repository)) throw Error('Invalid repository.');
  const invoke = (command, args, extra = {}) => {
    const result = run(command, args, { encoding: 'utf8', shell: false, timeout: 120_000, maxBuffer: 32 * 1024 * 1024, ...extra });
    if (result.error || result.status !== 0) throw Error(`${command} failed: ${result.error?.message || result.stderr || result.status}`);
    return String(result.stdout ?? '').trim();
  };
  // The workflow also holds the original Windows publisher's concurrency lock.
  // Call within unified CI only AFTER its native dependencies finish. Ignore
  // exactly that owning run (also for independent recovery), never other runs.
  for (const workflow of ['ci.yml', 'android-release.yml', 'windows-release.yml', 'windows-publication-recovery.yml']) {
    if (api(`actions/workflows/${workflow}/runs?branch=main&per_page=100`, { paginate: true }).some(p => p.workflow_runs.some(r => r.status !== 'completed' && !(currentRunId && String(r.id) === currentRunId)))) return { deferred: true };
  }
  const previews = api('releases?per_page=100', { paginate: true }).flat().filter(r => /^windows-v0\.1\.[1-9][0-9]*$/.test(r.tag_name));
  const recovered = [];
  for (const preview of previews) {
    const tag = preview.tag_name;
    if (!Number.isSafeInteger(preview.id) || preview.id <= 0 || preview.draft !== false || preview.prerelease !== true || !/^[a-f0-9]{40}$/.test(preview.target_commitish)) throw Error('Conflicting/draft Windows preview state.');
    const verifyPreviewTag = () => {
      let object = api(`git/ref/tags/${tag}`).object;
      const seen = new Set();
      while (object?.type === 'tag') {
        if (!/^[a-f0-9]{40}$/.test(object.sha) || seen.has(object.sha) || seen.size >= 16) throw Error('Invalid preview tag chain.');
        seen.add(object.sha); object = api(`git/tags/${object.sha}`).object;
      }
      if (object?.type !== 'commit' || object.sha !== preview.target_commitish) throw Error('Preview tag/source mismatch.');
    };
    verifyPreviewTag();
    invoke('git', ['-C', root, 'merge-base', '--is-ancestor', preview.target_commitish, 'HEAD']);
    const names = ['MusicWindows-win-x64-Setup.exe', `MusicWindows-${tag.slice(9)}-win-x64-full.nupkg`, 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64', 'windows-release.json', 'SHA256SUMS'];
    const assetsPath = `releases/${preview.id}/assets?per_page=100`;
    const assets = api(assetsPath, { paginate: true }).flat();
    if (assets.length !== names.length || !names.every(name => assets.filter(a => a.name === name && a.state === 'uploaded' && Number.isSafeInteger(a.id) && a.id > 0 && a.size > 0).length === 1)) throw Error('Incomplete/conflicting preview inventory.');
    const temporary = mkdtempSync(join(tmpdir(), 'music-windows-recovery-'));
    const sourceRoot = join(temporary, 'source');
    const directory = join(temporary, 'inventory');
    let added = false;
    try {
      invoke('git', ['-C', root, 'worktree', 'add', '--detach', sourceRoot, preview.target_commitish]); added = true;
      const source = deriveSource(sourceRoot, { run });
      if (source.tag !== tag || source.sha !== preview.target_commitish) throw Error('Preview version/source mismatch.');
      const main = api(`releases/tags/v${source.version}`, { optional: true });
      if (main && (main.draft !== false || main.prerelease !== false || main.target_commitish !== source.sha)) throw Error('Conflicting/draft main release state.');
      if (main) {
        let ref = api(`git/ref/tags/v${source.version}`).object;
        const tags = new Set();
        while (ref?.type === 'tag') {
          if (!/^[a-f0-9]{40}$/.test(ref.sha) || tags.has(ref.sha) || tags.size >= 16) throw Error('Invalid main tag chain.');
          tags.add(ref.sha); ref = api(`git/tags/${ref.sha}`).object;
        }
        if (ref?.type !== 'commit' || ref.sha !== source.sha) throw Error('Main tag/source mismatch.');
        const attached = api(`releases/${main.id}/assets?per_page=100`, { paginate: true }).flat();
        const installer = names[0];
        if ([installer, `${installer}.sha256`].every(name => attached.filter(a => a.name === name && a.state === 'uploaded' && a.size > 0).length === 1)) continue;
      }
      // Missing Android publication must not be silently replaced by a Windows-only
      // release. Wait for Android recovery/operator intervention instead.
      if (!main && androidChanged(sourceRoot)) throw Error('Missing main release while Android publication is required; recover Android first.');
      invoke('gh', ['release', 'download', tag, '--repo', repository, '--dir', directory]);
      validateLocal(directory, source);
      // Use trusted current tooling, but derive identity from the ORIGINAL checkout.
      invoke('python3', [join(root, 'scripts/windows-release.py'), 'validate', '--root', sourceRoot, '--directory', directory]);
      const normalize = items => items.map(a => Object.fromEntries(Object.entries(a).filter(([k]) => k !== 'download_count'))).sort((a, b) => a.id - b.id);
      const fresh = api(`releases/tags/${tag}`);
      if (fresh.id !== preview.id || fresh.target_commitish !== source.sha || fresh.draft !== false || fresh.prerelease !== true || fresh.tag_name !== tag || !isDeepStrictEqual(normalize(assets), normalize(api(assetsPath, { paginate: true }).flat()))) throw Error('Preview changed during download.');
      verifyPreviewTag();
      await ensure(['--repository', repository, '--android-changed', 'false'], { root: sourceRoot, run, output: () => {} });
      await attach({ directory, repository, source, run });
      recovered.push(tag);
    } finally {
      if (added) invoke('git', ['-C', root, 'worktree', 'remove', '--force', sourceRoot]);
      rmSync(temporary, { recursive: true, force: true });
    }
  }
  return { recovered };
}

export async function main(argv = process.argv.slice(2)) {
  if (argv.length !== 2 || argv[0] !== '--repository' || !/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(argv[1])) throw Error('Usage: node scripts/recover-windows-publication.mjs --repository OWNER/REPO');
  const repository = argv[1];
  const api = (path, { paginate = false, optional = false, binaryJson = false } = {}) => {
    const args = ['api', `repos/${repository}/${path}`, ...(paginate ? ['--paginate', '--slurp'] : []), ...(binaryJson ? ['-H', 'Accept: application/octet-stream'] : [])];
    const r = spawnSync('gh', args, { encoding: 'utf8', shell: false, timeout: 60_000, maxBuffer: 32 * 1024 * 1024 });
    if (r.error || r.status !== 0) {
      if (!r.error && optional && /\bHTTP 404\b/i.test(r.stderr)) return null;
      throw Error(`GitHub API failed: ${r.error?.message || r.stderr}`);
    }
    return JSON.parse(r.stdout);
  };
  const result = await recoverWindowsPublications({ root: resolve(dirname(fileURLToPath(import.meta.url)), '..'), repository, api });
  console.log(JSON.stringify(result)); return result;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) main().catch(e => { console.error(e.message); process.exitCode = 1; });
