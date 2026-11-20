#!/usr/bin/env node
// Release-aware build selection. GitHub reads only; never advance a baseline here.
import { spawnSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const components = ['android', 'windows', 'web'];
const shaPattern = /^[a-f0-9]{40}$/;
function invoke(run, command, args) {
  const result = run(command, args, { encoding: 'utf8', shell: false, timeout: 60_000, maxBuffer: 32 * 1024 * 1024 });
  if (result.error || result.status !== 0) throw new Error(`${command} failed: ${result.error?.message || result.stderr || result.status}`);
  return result.stdout;
}
export function affectedComponents(paths) {
  const selected = new Set();
  const add = (...items) => items.forEach(item => selected.add(item));
  for (const path of paths) {
    if (path.startsWith('docs/') || /(?:^|\/)(?:README|RELEASING|UPDATING|VALIDATION|NOTES|AGENTS|CLAUDE)\.md$/.test(path)) continue;
    if (['scripts/current-downloads.mjs', 'tests/current-downloads.test.mjs', '.github/workflows/current-downloads.yml'].includes(path)) continue;
    if (['scripts/selective-builds.mjs', 'tests/selective-builds.test.mjs'].includes(path)) { add(...components); continue; }
    if (path.startsWith('android/')) {
      add('android');
      // Windows branding tests regenerate these icons from Android's source artwork.
      if (/^android\/app\/src\/main\/res\/(?:drawable\/ic_audio(?:_foreground)?\.xml|values\/icon_colors\.xml)$/.test(path)) add('windows');
      continue;
    }
    if (path.startsWith('apps/windows/') || /^scripts\/(?:.*windows.*|generate-windows-icons)\.(?:mjs|py|ps1)$/.test(path) || /^tests\/(?:windows-.*\.test\.mjs|ensure-windows-main-release\.test\.mjs|test_windows_.*\.py)$/.test(path) || /^\.github\/workflows\/windows-.*\.yml$/.test(path)) { add('windows'); continue; }
    if (/^scripts\/(?:android-release|test-android-.*)\.py$/.test(path) || /^tests\/test_android_.*\.py$/.test(path) || path === '.github/workflows/android-release.yml') { add('android'); continue; }
    if (path.startsWith('mockups/') || ['package.json', 'package-lock.json', '.github/workflows/web-build.yml'].includes(path) || /^scripts\/(?:build-(?:onboarding|phone-size|readable)|serve)\.mjs$/.test(path) || /^scripts\/(?:v3-contact-sheet|v5-comparison)\.py$/.test(path) || /^tests\/(?:android-auto|bottom-chrome|first-install|media-output-mockup|mockups|now-playing-options|phone-size|playlist-workflow|queue-shuffle|readable-player|windows-native(?:-v[23])?)\.mjs$/.test(path)) { add('web'); continue; }
    // Unknown build inputs are shared until explicitly classified. Never silently skip them.
    add(...components);
  }
  return components.filter(component => selected.has(component));
}
export function decideFromBaseline({ root, component, baseline, force = false, run = spawnSync }) {
  if (!components.includes(component)) throw new Error('Invalid component.');
  const git = (...args) => invoke(run, 'git', ['-C', resolve(root), ...args]);
  if (git('rev-parse', '--is-shallow-repository').trim() !== 'false') throw new Error('Full history required.');
  const head = git('rev-parse', 'HEAD').trim();
  if (!shaPattern.test(head) || (baseline !== null && !shaPattern.test(baseline))) throw new Error('Invalid source SHA.');
  if (force) return { build: true, baseline, head, reason: 'manual force rebuild', paths: [] };
  if (baseline === null) return { build: true, baseline, head, reason: 'no successful publication/check yet', paths: [] };
  const ancestor = (a, b) => {
    const result = run('git', ['-C', resolve(root), 'merge-base', '--is-ancestor', a, b], { encoding: 'utf8', shell: false, timeout: 60_000 });
    if (result.error || ![0, 1].includes(result.status)) throw new Error('Cannot establish publication ancestry.');
    return result.status === 0;
  };
  if (!ancestor(baseline, head)) {
    if (ancestor(head, baseline)) return { build: false, baseline, head, reason: 'a newer published revision already includes this source', paths: [] };
    throw new Error('Published source diverges from HEAD; inspect history before releasing.');
  }
  const paths = git('diff', '--name-only', '-z', '--no-renames', baseline, head, '--').split('\0').filter(Boolean);
  const build = affectedComponents(paths).includes(component);
  return { build, baseline, head, reason: build ? 'affected inputs changed since last successful publication/check' : 'component inputs unchanged', paths };
}

export function publishedBaseline(component, { api }) {
  if (!components.includes(component)) throw new Error('Invalid component.');
  if (component === 'web') {
    // Successful pushes are conservative web checks. Targeted manual CI runs
    // may skip web entirely, so they cannot establish a web baseline.
    for (const workflow of ['ci.yml', 'web-build.yml']) {
      const pages = api(`actions/workflows/${workflow}/runs?branch=main&status=success&per_page=100`, { paginate: true, optional: true });
      const events = workflow === 'ci.yml' ? ['push'] : ['push', 'workflow_dispatch'];
      const run = pages?.flatMap(page => page.workflow_runs).find(run => run.conclusion === 'success' && run.head_branch === 'main' && events.includes(run.event));
      if (!run) continue;
      if (!shaPattern.test(run.head_sha)) throw new Error('Invalid successful web source.');
      return run.head_sha;
    }
    return null;
  }
  const windows = component === 'windows';
  const pattern = windows ? /^(?:windows-)?v0\.1\.([1-9][0-9]*)$/ : /^v0\.1\.([1-9][0-9]*)$/;
  const legacyWindows = release => windows && release.tag_name.startsWith('windows-');
  const releases = api('releases?per_page=100', { paginate: true }).flat()
    .filter(release => !release.draft && pattern.test(release.tag_name) && release.prerelease === legacyWindows(release))
    .sort((a, b) => Number(pattern.exec(b.tag_name)[1]) - Number(pattern.exec(a.tag_name)[1]) || Number(legacyWindows(a)) - Number(legacyWindows(b)));
  for (const release of releases) {
    const count = Number(pattern.exec(release.tag_name)[1]);
    if (!Number.isSafeInteger(count) || count > (windows ? 65534 : 2_100_000_000)) throw new Error('Invalid published version.');
    const version = `0.1.${count}`, tag = release.tag_name;
    const names = windows ? ['MusicWindows-win-x64-Setup.exe', `MusicWindows-${version}-win-x64-full.nupkg`, 'releases.win-x64.json', 'assets.win-x64.json', 'RELEASES-win-x64', 'windows-release.json', 'SHA256SUMS'] : [`Music-${version}.apk`, `Music-${version}.apk.sha256`, 'release.json'];
    // Stable Windows publication includes the installer checksum in addition to
    // the legacy Velopack inventory. EXE-only mirrors are not publications.
    if (windows && !legacyWindows(release)) names.push('MusicWindows-win-x64-Setup.exe.sha256');
    const assets = api(`releases/${release.id}/assets?per_page=100`, { paginate: true }).flat();
    // An unpublished/incomplete platform cannot become the successful baseline.
    if (!names.every(name => assets.filter(asset => asset.name === name && asset.state === 'uploaded' && asset.size > 0).length === 1)) continue;
    const asset = assets.find(asset => asset.name === (windows ? 'windows-release.json' : 'release.json'));
    if (!Number.isSafeInteger(asset.id) || asset.id <= 0 || asset.size > 65536) throw new Error('Invalid publication manifest asset.');
    const manifest = api(`releases/assets/${asset.id}`, { binaryJson: true });
    // Stable publication retains the packaging tag for existing Windows clients;
    // GitHub provenance must still resolve the actual release tag below.
    const manifestTags = windows && !legacyWindows(release) ? [tag, `windows-v${version}`] : [tag];
    if (!manifest || JSON.stringify(manifest).length > 65536 || !shaPattern.test(manifest.sha) || manifest.sha !== release.target_commitish || manifest.count !== count || manifest.version !== version || !manifestTags.includes(manifest.tag)) throw new Error('Published source metadata mismatch.');
    if (windows) {
      if (manifest.platform !== 'windows-x64' || manifest.signing !== 'unsigned-preview' || !Array.isArray(manifest.artifacts)) throw new Error('Invalid Windows publication manifest.');
      for (const name of names.slice(0, 5)) {
        const entries = manifest.artifacts.filter(entry => entry.name === name);
        if (entries.length !== 1 || entries[0].bytes !== assets.find(a => a.name === name).size || !/^[a-f0-9]{64}$/.test(entries[0].sha256)) throw new Error('Windows published asset metadata mismatch.');
      }
    } else if (manifest.apk !== names[0] || manifest.bytes !== assets.find(a => a.name === names[0]).size || !/^[a-f0-9]{64}$/.test(manifest.sha256)) throw new Error('Android published asset metadata mismatch.');
    let object = api(`git/ref/tags/${tag}`).object;
    const seen = new Set();
    while (object?.type === 'tag') {
      if (!shaPattern.test(object.sha) || seen.has(object.sha) || seen.size >= 16) throw new Error('Invalid publication tag chain.');
      seen.add(object.sha);
      object = api(`git/tags/${object.sha}`).object;
    }
    if (object?.type !== 'commit' || object.sha !== manifest.sha) throw new Error('Published tag/source mismatch.');
    return manifest.sha;
  }
  return null;
}

export function main(argv = process.argv.slice(2), { root = fileURLToPath(new URL('..', import.meta.url)), run = spawnSync, output = text => console.log(text) } = {}) {
  const options = {};
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i], value = argv[i + 1];
    if (!['--repository', '--component', '--force', '--github-output'].includes(key) || Object.hasOwn(options, key) || !value || value.startsWith('-')) throw new Error('Usage: selective-builds.mjs --repository OWNER/REPO --component android|windows|web [--force none|android|windows|web|all] [--github-output PATH]');
    options[key] = value;
  }
  const repository = options['--repository'];
  if (!/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(repository) || repository.split('/').some(p => p === '.' || p === '..')) throw new Error('Invalid repository.');
  const component = options['--component'], force = options['--force'] ?? 'none';
  if (!components.includes(component) || !['none', 'all', ...components].includes(force)) throw new Error('Invalid component/force selection.');
  const api = (path, { paginate = false, optional = false, binaryJson = false } = {}) => {
    const args = ['api', `repos/${repository}/${path}`];
    if (paginate) args.push('--paginate', '--slurp');
    if (binaryJson) args.push('-H', 'Accept: application/octet-stream');
    const result = run('gh', args, { encoding: 'utf8', shell: false, timeout: 60_000, maxBuffer: 32 * 1024 * 1024 });
    if (result.error || result.status !== 0) {
      if (!result.error && optional && /\bHTTP 404\b/.test(result.stderr ?? '')) return null;
      throw new Error(`GitHub baseline lookup failed: ${result.error?.message || result.stderr || result.status}`);
    }
    return JSON.parse(result.stdout);
  };
  const result = {};
  // Windows must know whether Android will create the matching shared release.
  for (const item of component === 'windows' ? ['android', 'windows'] : [component]) {
    const baseline = publishedBaseline(item, { api });
    result[item] = decideFromBaseline({ root, component: item, baseline, force: force === item || force === 'all', run });
  }
  if (options['--github-output']) appendFileSync(options['--github-output'], Object.entries(result).map(([key, value]) => `${key}=${value.build}\n`).join(''));
  output(JSON.stringify(result, null, 2));
  return result;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
