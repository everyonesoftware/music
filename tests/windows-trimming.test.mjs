import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

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

test('Windows release parsing uses generated metadata, not the reflection overload', () => {
  const backend = read('apps/windows/Music.App/WindowsUpdateBackend.cs');
  assert.match(backend, /item.Deserialize\(WindowsUpdateJsonContext.Default.GithubRelease\)/);
  assert.match(backend, /\[JsonSerializable\(typeof\(GithubRelease\)\)\]/);
});

test('build-only native trimming is explicit and compatibility checks run in CI', () => {
  const build = read('.github/workflows/windows-build.yml');
  const release = read('.github/workflows/windows-release.yml');
  assert.match(build, /trimmed_experiment:[\s\S]*?default: false/);
  assert.match(build, /-p:MusicTrimmed=\$\{\{ inputs.trimmed_experiment == true \}\}/);
  assert.match(build, /dotnet publish Music.TrimChecks\/Music.TrimChecks.csproj[^\n]*-p:PublishTrimmed=true/);
  assert.match(build, /run: \.\/artifacts\/trim-checks\/Music.TrimChecks/);
  for (const workflow of [build, release]) {
    assert.match(workflow, /node --test[^\n]*tests\/windows-trimming\.test\.mjs/);
  }
  assert.doesNotMatch(release, /MusicTrimmed|trimmed_experiment/,
    'normal releases must not silently opt into the trimming experiment');
});
