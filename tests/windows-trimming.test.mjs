import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { readWorkflow, workflowJob } from './helpers/workflow.mjs';

const root = new URL('../', import.meta.url);
const read = path => readFileSync(new URL(path, root), 'utf8');

test('trimming is explicit opt-in and preserves runtime XAML projections', () => {
  const properties = extra => {
    const run = spawnSync('dotnet', ['msbuild', 'apps/windows/Music.App/Music.App.csproj', '-nologo',
      '-p:Configuration=Release', ...extra,
      '-getProperty:PublishTrimmed,TrimMode,ILLinkTreatWarningsAsErrors,JsonSerializerIsReflectionEnabledByDefault,DynamicCodeSupport,CsWinRTAotOptimizerEnabled,CsWinRTAotWarningLevel,AllowUnsafeBlocks',
      '-getItem:TrimmerRootAssembly'], { cwd: fileURLToPath(root), encoding: 'utf8', timeout: 30000 });
    assert.ifError(run.error);
    assert.equal(run.status, 0, run.stdout + run.stderr);
    return JSON.parse(run.stdout);
  };
  assert.equal(properties([]).Properties.PublishTrimmed, 'false');
  const trimmed = properties(['-p:MusicTrimmed=true']);
  assert.equal(trimmed.Properties.PublishTrimmed, 'true');
  assert.equal(trimmed.Properties.TrimMode, 'partial');
  assert.equal(trimmed.Properties.ILLinkTreatWarningsAsErrors, 'true');
  assert.equal(trimmed.Properties.JsonSerializerIsReflectionEnabledByDefault, 'false');
  const roots = trimmed.Items.TrimmerRootAssembly.map(item => item.Identity);
  assert.ok(roots.includes('Microsoft.WinUI'), 'runtime-loaded XAML needs WinUI metadata and members');
  assert.equal(trimmed.Properties.DynamicCodeSupport, 'false');
  assert.equal(trimmed.Properties.CsWinRTAotOptimizerEnabled, 'true');
  assert.equal(trimmed.Properties.CsWinRTAotWarningLevel, '2');
  assert.equal(trimmed.Properties.AllowUnsafeBlocks, 'true');
  assert.ok(!roots.includes('WinRT.Runtime'), 'do not root unused reflection-based generic fallbacks');
});

test('connection profiles use generated metadata for both persistence directions', () => {
  const profile = read('apps/windows/Music.App/ProfileStore.cs');
  assert.match(profile, /Deserialize\(File.ReadAllText\(ProfilePath\), PersistenceJsonContext.Default.ConnectionProfile\)/);
  assert.match(profile, /Serialize\(profile, PersistenceJsonContext.Default.ConnectionProfile\)/);
});

test('Windows release discovery projects verified JSON without reflection', () => {
  const backend = read('apps/windows/Music.App/WindowsUpdateBackend.cs');
  assert.match(backend, /verifiedAssets\.Add\(new GithubReleaseAsset/);
  assert.match(backend, /new GithubRelease \{[^\n]*Assets = verifiedAssets\.ToArray\(\)/);
  assert.doesNotMatch(backend, /\bDeserialize(?:Async)?\s*(?:<|\()/);
});

test('build-only native trimming is explicit and compatibility checks run in validation', () => {
  const ci = readWorkflow();
  const validate = workflowJob(ci, 'validate');
  const windows = workflowJob(ci, 'windows');
  const release = workflowJob(ci, 'release');
  assert.match(ci, /trimmed_experiment:[\s\S]*?type: boolean[\s\S]*?default: false/);
  assert.match(windows, /dotnet publish apps\/windows\/Music.App\/Music.App.csproj[^\n]*-p:MusicTrimmed=\$\{\{ inputs.trimmed_experiment == true \}\}/);
  assert.match(windows, /if:.*inputs.trimmed_experiment/);
  assert.match(validate, /dotnet publish apps\/windows\/Music.TrimChecks\/Music.TrimChecks.csproj[^\n]*-p:PublishTrimmed=true/);
  assert.match(validate, /run: \.\/apps\/windows\/artifacts\/trim-checks\/Music.TrimChecks/);
  for (const job of [validate, windows]) {
    assert.match(job, /node --test[^\n]*tests\/windows-trimming\.test\.mjs/);
  }
  assert.match(release, /if:.*!inputs.trimmed_experiment/,
    'experimental trimmed artifacts must never be published');
  assert.doesNotMatch(release, /dotnet publish|MusicTrimmed/);
});
