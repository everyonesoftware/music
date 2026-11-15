import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
const base = new URL('../apps/windows/', import.meta.url);
const read = p => readFileSync(new URL(p, base), 'utf8');
test('native driver is opt-in linked code, never normal release input', () => {
  const project = read('Music.App/Music.App.csproj');
  assert.match(project, /PropertyGroup Condition="'\$\(MusicNativeAcceptance\)' == 'true'"/);
  assert.match(project, /MUSIC_NATIVE_ACCEPTANCE/);
  assert.match(project, /Compile Include="\.\.\/validation\/native\/\*\.cs"/);
  assert.match(read('Music.App/Program.cs'), /#if MUSIC_NATIVE_ACCEPTANCE[\s\S]*NativeAcceptance/);
  assert.match(read('Music.App/MainWindow.cs'), /public sealed partial class MainWindow/);
  assert.ok(existsSync(new URL('validation/native/NativeAcceptance.cs', base)));
  assert.match(read('validation/LogicChecks.csproj'), /Compile Remove="native\/\*\*\/\*\.cs"/);
});
test('seed and restore use real stores and production restore, with bounded safe evidence', () => {
  const driver = read('validation/native/NativeAcceptance.cs');
  for (const contract of ['new ProfileStore()', 'new SessionStore(', 'profiles.Commit(', 'profiles.ReadPassword(', 'RestorePlayback();', 'TrySavePlayback()', 'ActivateConnection(', 'new NavidromeClient(', 'HttpMessageHandler', 'ButtonAutomationPeer', 'VisualTreeHelper.GetChildrenCount', 'Utf8JsonWriter', 'TargetVersion', 'Close();', 'Task.Delay(', 'freshProfile', 'duplicateQueue', 'pausedRestoration', 'viewRestoration']) assert.ok(driver.includes(contract), contract);
  assert.doesNotMatch(driver, /(?:exception|error|ex)\.ToString\(|(?:exception|error|ex)\.Message/);
});
test('media evidence never claims audibility and fixture cleanup is narrow and bounded', () => {
  const driver = read('validation/native/NativeAcceptance.cs');
  for (const contract of ['["audibleOutputVerified"] = false', '["mediaOpened"] = false', '["seekVerified"] = false', '["pauseVerified"] = false', '["positionAdvanced"] = false', 'no-audio-renderer', 'MediaPlayerFailedEventArgs', 'NativeCleanupFixtureCredential', 'vault.Remove(vault.Retrieve("EveryoneSoftware.Music", NativeAcceptance.ProfileId))', 'await Task.Delay(5000)', 'vaultCleanup']) assert.ok(driver.includes(contract), contract);
  assert.doesNotMatch(driver, /new MediaPlayer\(/);
  assert.match(read('Music.App/MainWindow.cs'), /#if MUSIC_NATIVE_ACCEPTANCE\s+if \(NativeAcceptance.IsRequested\) return;/);
});
test('parent receives media classification independently of core stage and real restore seek is observed', () => {
  const driver = read('validation/native/NativeAcceptance.cs');
  for (const contract of ['json.WriteStartObject("media")', 'renderer-unavailable', 'restoredNativeSeek', 'restoredNativePaused', 'mediaStatus == "failed"']) assert.ok(driver.includes(contract), contract);
});
test('results become visible only after complete safe JSON is flushed', () => {
  const driver = read('validation/native/NativeAcceptance.cs');
  assert.match(driver, /json.Flush\(\)/);
  assert.match(driver, /File.Move\(temporary, destination, true\)/);
});
test('seed refuses preexisting Music vault data as well as profile/session files', () => {
  const driver = read('validation/native/NativeAcceptance.cs');
  assert.ok(driver.includes('NativeVaultIsEmpty()'));
  assert.ok(driver.includes('!File.Exists(Path.Combine(profiles.DirectoryPath, "connection.json"))'));
});
test('media probe observes one stable production source and short-circuits a missing renderer explicitly', () => {
  const driver = read('validation/native/NativeAcceptance.cs');
  const probe = driver.slice(driver.indexOf('private async Task<string> NativeMediaAsync'));
  assert.doesNotMatch(probe, /player.Source\s*=/, 'do not mix events from a preceding source with a replacement');
  assert.match(probe, /ReferenceEquals\(player.Source, expectedSource\)/);
  assert.ok(probe.indexOf('if (!checks["rendererAvailable"])') < probe.indexOf('player.MediaFailed += onFailed'));
  assert.doesNotMatch(probe, /catch \(Exception\)[\s\S]*checks.TryGetValue\("rendererAvailable"/,
    'independent media failures must not become missing-renderer skips');
  assert.match(driver, /NativeHasRealizedFixtureText/);
});
