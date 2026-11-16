#!/usr/bin/env node
import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

const start = '<!-- current-downloads:start -->';
const end = '<!-- current-downloads:end -->';
function validateRepository(repository) {
  if (!/^[A-Za-z0-9_][A-Za-z0-9_.-]*\/[A-Za-z0-9_][A-Za-z0-9_.-]*$/.test(repository) || repository.split('/').some(p => p === '.' || p === '..')) throw new Error('Invalid repository.');
}
function versionOf(release) {
  return /^(?:windows-)?v(0\.1\.[1-9][0-9]*)$/.exec(release.tag_name)?.[1];
}
export function collectDownloads(releases, repository) {
  validateRepository(repository);
  const inventory = { repository, windows: null, android: null };
  const candidates = releases.filter(r => !r.draft && versionOf(r)).sort((a, b) =>
    Number(versionOf(b).split('.').at(-1)) - Number(versionOf(a).split('.').at(-1)) || Number(a.tag_name.startsWith('windows-')) - Number(b.tag_name.startsWith('windows-')));
  for (const release of candidates) {
    const version = versionOf(release);
    for (const platform of ['windows', 'android']) {
      if (inventory[platform] || (release.prerelease && !release.tag_name.startsWith('windows-')) || (platform === 'android' && release.tag_name.startsWith('windows-'))) continue;
      const name = platform === 'windows' ? 'MusicWindows-win-x64-Setup.exe' : `Music-${version}.apk`;
      const expected = `https://github.com/${repository}/releases/download/${release.tag_name}/`;
      const find = file => {
        const matches = release.assets.filter(a => a.name === file);
        return matches.length === 1 && matches[0].state === 'uploaded' && matches[0].size > 0 && matches[0].browser_download_url === expected + file ? matches[0] : null;
      };
      const installer = find(name);
      const checksum = find(`${name}.sha256`) ?? (platform === 'windows' && release.tag_name.startsWith('windows-') ? find('SHA256SUMS') : null);
      if (!installer || !checksum) continue;
      inventory[platform] = { version, tag: release.tag_name, url: installer.browser_download_url, checksum: checksum.browser_download_url };
    }
  }
  return inventory;
}
export function renderDownloads(inventory) {
  const row = (key, title, action, notes) => {
    const item = inventory[key];
    return item ? `| ${title} | ${item.version} | [${action}](${item.url}) · [SHA-256](${item.checksum}) | ${notes} |` : `| ${title} | — | Not yet published | ${notes} |`;
  };
  return [
    '## Current downloads', '',
    'Choose your platform. Each link points to its latest published installer, which may come from a different release.', '',
    '| Platform | Version | Download | Status |',
    '| --- | --- | --- | --- |',
    row('windows', 'Windows x64', 'Download installer (.exe)', 'Unsigned installer; Windows may display a security warning.'),
    row('android', 'Android', 'Download APK', 'Signed alpha; install manually on your device.'),
    '| Web | — | No released web app | Browser design studies only; not a working web player. |', '',
    'Unchanged platforms keep their existing installers; a new project release does not necessarily mean a new version for every platform.',
  ].join('\n');
}
export function replaceDownloads(text, table, { readme = false } = {}) {
  const starts = text.split(start).length - 1, ends = text.split(end).length - 1;
  const block = `${start}\n${table}\n${end}`;
  if (starts || ends) {
    if (starts !== 1 || ends !== 1 || text.indexOf(end) < text.indexOf(start)) throw new Error('Malformed current-downloads markers.');
    return text.slice(0, text.indexOf(start)) + block + text.slice(text.indexOf(end) + end.length);
  }
  if (readme) {
    const heading = /^# [^\n]+\n/.exec(text);
    if (!heading) throw new Error('README must start with a title.');
    return heading[0] + '\n' + block + '\n\n' + text.slice(heading[0].length).replace(/^\n/, '');
  }
  return block + (text ? '\n\n' + text : '\n');
}

// Publication workflows inspect release metadata while attaching assets. Do not edit
// their release descriptions until all currently visible publishers have finished.
export function refreshDownloads({ repository, api, publish = false, localReadme, currentRunId = process.env.GITHUB_RUN_ID ?? '' }) {
  validateRepository(repository);
  if (publish) {
    // Repository-wide discovery survives deleted legacy workflow filenames.
    // Conservatively defer any other active main run; never ignore API failures.
    const pages = api('actions/runs?branch=main&per_page=100', { paginate: true });
    if (pages.some(page => page.workflow_runs.some(run => run.status !== 'completed' && !(currentRunId && String(run.id) === currentRunId)))) return { deferred: true };
  }
  const releases = api('releases?per_page=100', { paginate: true }).flat()
    .filter(r => !r.draft && versionOf(r))
    .sort((a, b) => Number(versionOf(b).split('.').at(-1)) - Number(versionOf(a).split('.').at(-1)) || Number(a.tag_name.startsWith('windows-')) - Number(b.tag_name.startsWith('windows-')));
  const loaded = [];
  let inventory = collectDownloads([], repository);
  for (const release of releases) {
    if (release.prerelease && !release.tag_name.startsWith('windows-')) continue;
    const assets = api(`releases/${release.id}/assets?per_page=100`, { paginate: true }).flat();
    loaded.push({ ...release, assets });
    inventory = collectDownloads(loaded, repository);
    if (inventory.windows && inventory.android) break;
  }
  const table = renderDownloads(inventory);
  const latest = api('releases/latest');
  if (!Number.isSafeInteger(latest.id) || latest.id <= 0 || latest.draft || latest.prerelease) throw new Error('Invalid latest release.');
  // Existing Android installs require /latest to contain that tag's APK. Windows-
  // only main releases stay latest=false, but still need their own download table.
  const newest = releases.find(release => !release.prerelease);
  const targets = [latest];
  if (newest && newest.id !== latest.id) targets.push(api(`releases/${newest.id}`));
  for (const target of targets) {
    if (!Number.isSafeInteger(target.id) || target.id <= 0 || target.draft || target.prerelease || !versionOf(target)) throw new Error('Invalid stable release target.');
  }
  const branch = api('').default_branch;
  if (typeof branch !== 'string' || !branch) throw new Error('Missing default branch.');
  const readmePath = `contents/README.md?ref=${encodeURIComponent(branch)}`;
  const original = api(readmePath);
  if (original.encoding !== 'base64' || typeof original.content !== 'string' || !original.sha) throw new Error('Invalid README content.');
  const remoteReadme = Buffer.from(original.content, 'base64').toString('utf8');
  const readme = replaceDownloads(publish ? remoteReadme : (localReadme ?? remoteReadme), table, { readme: true });
  const descriptions = targets.map(target => ({ target, body: replaceDownloads(target.body ?? '', table) }));
  const preview = descriptions.at(-1);
  if (publish) {
    if (api('releases/latest').id !== latest.id) throw new Error('Latest release changed; retry refresh.');
    for (const { target, body } of descriptions) {
      // This detects observable edits, NOT an atomic compare-and-swap. GitHub's
      // release PATCH documents no conditional-write precondition. Automated
      // refresh writers must serialize; humans must not edit descriptions during
      // refresh. An edit between this GET and PATCH can still be overwritten.
      const fresh = api(`releases/${target.id}`);
      if (fresh.body !== target.body || fresh.tag_name !== target.tag_name || fresh.draft || fresh.prerelease) throw new Error('Release changed; retry refresh.');
      if (body !== (target.body ?? '')) api(`releases/${target.id}`, { method: 'PATCH', data: { body } });
      const verified = api(`releases/${target.id}`);
      if (verified.body !== body || verified.tag_name !== target.tag_name) throw new Error('Release readback mismatch.');
    }
    if (readme !== remoteReadme) {
      api('contents/README.md', { method: 'PUT', data: {
        message: 'docs: refresh current platform downloads', branch, sha: original.sha,
        content: Buffer.from(readme).toString('base64'),
      } });
    }
    const confirmed = api(readmePath);
    if (confirmed.encoding !== 'base64' || Buffer.from(confirmed.content, 'base64').toString('utf8') !== readme) throw new Error('README readback mismatch.');
  }
  return { inventory, table, readme, releaseNotes: preview.body, tag: preview.target.tag_name, published: publish };
}

export function main(argv = process.argv.slice(2), { run = spawnSync } = {}) {
  const options = {};
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i];
    if (!['--repository', '--output-directory', '--publish'].includes(key) || Object.hasOwn(options, key)) throw new Error('Usage: node scripts/current-downloads.mjs --repository OWNER/REPO [--output-directory PATH] [--publish]');
    if (key === '--publish') options[key] = true;
    else {
      const value = argv[++i];
      if (!value || value.startsWith('-')) throw new Error(`Missing value for ${key}.`);
      options[key] = value;
    }
  }
  const repository = options['--repository'];
  validateRepository(repository);
  if (!options['--publish'] && !options['--output-directory']) throw new Error('Preview requires --output-directory.');
  const api = (path, { method, data, paginate } = {}) => {
    const args = ['api', `repos/${repository}${path ? '/' + path : ''}`];
    if (paginate) args.push('--paginate', '--slurp');
    if (method) args.push('--method', method, '--input', '-');
    const result = run('gh', args, { encoding: 'utf8', shell: false, timeout: 60_000, maxBuffer: 32 * 1024 * 1024, ...(data ? { input: JSON.stringify(data) } : {}) });
    if (result.error || result.status !== 0) throw new Error(`GitHub API failed: ${result.error?.message || result.stderr}`);
    return JSON.parse(result.stdout);
  };
  const result = refreshDownloads({ repository, api, publish: Boolean(options['--publish']), localReadme: options['--publish'] ? undefined : readFileSync(new URL('../README.md', import.meta.url), 'utf8') });
  if (!result.deferred && options['--output-directory']) {
    const directory = resolve(options['--output-directory']);
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, 'README.md'), result.readme);
    writeFileSync(join(directory, 'release-notes.md'), result.releaseNotes);
    writeFileSync(join(directory, 'downloads.json'), JSON.stringify(result.inventory, null, 2) + '\n');
  }
  console.log(JSON.stringify(result.deferred ? result : { tag: result.tag, published: result.published, inventory: result.inventory }, null, 2));
  return result;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
