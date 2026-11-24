import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Source/wiring contracts only; native XAML geometry requires Windows acceptance.
const app = new URL('../Music.App/', import.meta.url);
const read = name => readFileSync(new URL(name, app), 'utf8').replaceAll('\r\n', '\n');
const panel = read('WindowsUpdatesPanel.cs');
const main = read('MainWindow.cs');
const controller = read('../Music.Core/WindowsUpdateController.cs');

test('Music mounts the published shared Updates panel without a parallel local status card', () => {
  assert.match(read('Music.App.csproj'), /<PackageReference Include="EveryoneSoftware\.WindowsCommon" Version="\[0\.1\.0-alpha\.2\]"\s*\/>/);
  const shell = read('Shell.xaml');
  assert.match(shell, /<ContentControl x:Name="UpdatesPanelHost" HorizontalContentAlignment="Stretch"\s*\/>/);
  assert.doesNotMatch(shell, /x:Name="(?:VersionText|CheckUpdatesButton|UpdateTitle|UpdateStatus|UpdateOfferedVersion|UpdateProgress|UpdateActions|DownloadUpdateButton|RestartUpdateButton)"/);
  assert.match(panel, /using SharedUpdatesPanel = EveryoneSoftware\.WindowsCommon\.WindowsUpdatesPanel;/);
  assert.match(panel, /new SharedUpdatesPanel\("Music", installedVersion,/);
  assert.match(panel, /FindName\("UpdatesPanelHost"\)\)\.Content = panel\.View;/);
});

test('shared actions drive Music operations and snapshot rendering stays on the dispatcher', () => {
  for (const [event, method] of [
    ['CheckRequested', 'CheckAsync'], ['DownloadRequested', 'DownloadAsync'], ['RestartRequested', 'RestartAsync'],
  ]) {
    assert.ok(panel.includes(`panel.${event} += ${event};`));
    assert.ok(panel.includes(`panel.${event} -= ${event};`));
    assert.match(panel, new RegExp(`${event}\\([^\\n]+=> await controller\\.${method}\\(\\)`));
  }
  assert.match(panel, /dispatcher\.TryEnqueue\(\(\) =>[\s\S]*?if \(!disposed\) Render\(controller\.Status\)/);
  assert.match(panel, /panel\.Render\(new UpdateSnapshot\(ToSharedPhase\(state\.Phase\), state\.Message, state\.Version, state\.Progress\)\s*\{\s*AllowRecheckDuringDownloadOrReady = true\s*\},\s*restartAllowed: true, changelog: changelog\)/);
  assert.match(panel, /panel\.Dispose\(\)/);
  assert.match(panel, /controller\.Changed -= OnChanged/);
});

test('Music uses no separate recheck control or click handler', () => {
  assert.doesNotMatch(read('Shell.xaml'), /RecheckUpdateButton|Check for a newer update/);
  assert.doesNotMatch(panel, /RecheckUpdateButton|RecheckClicked/);
});

test('normal Windows activation starts discovery without waiting for account restoration', () => {
  const program = read('Program.cs');
  assert.match(program, /window\.Activate\(\);[\s\S]*?_ = window\.CheckForUpdatesOnStartupAsync\(\);/);
  assert.match(main, /CheckForUpdatesOnStartupAsync\(\) => updatesPanel\.CheckOnStartupAsync\(\)/);
  assert.match(panel, /CheckOnStartupAsync\(\) => controller\.CheckOnStartupAsync\(\)/);
  assert.match(program, /SetAutoApplyOnStartup\(false\)/);
});

test('installed assembly version feeds the shared panel', () => {
  assert.match(main, /var installedVersion = Assembly\.GetExecutingAssembly\(\)\.GetName\(\)\.Version!\.ToString\(3\);/);
  assert.match(main, /new WindowsUpdatesPanel\(root, DispatcherQueue, installedVersion,/);
});

test('every Music update phase maps to its distinct shared phase', () => {
  const phases = controller.match(/enum WindowsUpdatePhase \{([^}]+)\}/)[1].split(',').map(s => s.trim());
  for (const phase of phases) assert.ok(panel.includes(`WindowsUpdatePhase.${phase} => UpdatePhase.${phase},`), `mapping for ${phase}`);
  assert.doesNotMatch(panel, /\(UpdatePhase\)\s*\(?\s*(?:phase|state\.Phase)/);
  assert.doesNotMatch(controller, /Windows preview|preview updates|preview releases/);
});

test('shared card receives the Music settings palette without probing native parents', () => {
  assert.match(panel, /cardBackground: .*root.Resources\["SettingsCardBackground"\]/);
  assert.match(panel, /cardBorder: .*root.Resources\["SettingsCardBorder"\]/);
  assert.match(read('Shell.xaml'), /x:Key="SettingsCardBackground" Color="#2a332d"/);
  assert.match(read('Shell.xaml'), /x:Key="SettingsCardBorder" Color="#39453d"/);
  assert.doesNotMatch(panel, /\.Parent\b/);
});
