import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

// Source/wiring contracts only; native XAML geometry requires Windows acceptance.
const app = new URL('../Music.App/', import.meta.url);
const read = name => readFileSync(new URL(name, app), 'utf8').replaceAll('\r\n', '\n');
const panel = read('WindowsUpdatesPanel.cs');
const main = read('MainWindow.cs');
const controller = read('../Music.Core/WindowsUpdateController.cs');

test('installed assembly version labels the update header without duplicate product copy', () => {
  assert.match(main, /C<TextBlock>\("VersionText"\)\.Text = \$"Current Version: \{Assembly\.GetExecutingAssembly\(\)\.GetName\(\)\.Version\?\.ToString\(3\)\}"/);
});

test('up-to-date title is rendered only for a completed current check; other phases stay distinct', () => {
  assert.match(panel, /title = \(TextBlock\)root.FindName\("UpdateTitle"\)/);
  assert.match(panel, /title.Text = state.Phase switch/);
  for (const [phase, title] of [
    ['Idle', 'Not checked yet'], ['Checking', 'Checking for updates'],
    ['Current', 'Up to date'], ['Available', 'Update available'],
    ['Downloading', 'Downloading update'], ['Ready', 'Ready to install'],
    ['Restarting', 'Restarting'], ['Error', 'Update failed'],
    ['NotInstalled', 'Updates unavailable'],
  ]) assert.ok(panel.includes(`WindowsUpdatePhase.${phase} => "${title}"`), `${phase} needs its own truthful title`);
  assert.match(panel, /status.Text = state.Phase == WindowsUpdatePhase.Downloading \? .*state.Progress.* : state.Message/);
  assert.doesNotMatch(controller, /Windows preview|preview updates|preview releases/);
});

test('current card collapses the entire action row, preserving explicit download and restart controls', () => {
  assert.match(panel, /actions = \(StackPanel\)root.FindName\("UpdateActions"\)/);
  assert.match(panel, /actions.Visibility = state.Phase is WindowsUpdatePhase.Available or WindowsUpdatePhase.Ready \? Visibility.Visible : Visibility.Collapsed/);
  assert.match(panel, /check.Content = state.Phase == WindowsUpdatePhase.Checking \? "Checking…" : "Check for updates"/);
  assert.match(panel, /download.Visibility = state.Phase == WindowsUpdatePhase.Available/);
  assert.match(panel, /restart.Visibility = state.Phase == WindowsUpdatePhase.Ready/);
  assert.match(panel, /progress.Visibility = busy \|\| state.Phase == WindowsUpdatePhase.Ready/);
  assert.match(panel, /if \(!disposed\) Render\(controller.Status\)/);
  assert.match(panel, /await controller.CheckAsync\(\)/);
  assert.match(panel, /await controller.DownloadAsync\(\)/);
  assert.match(panel, /await controller.RestartAsync\(\)/);
  assert.doesNotMatch(panel, /\.Parent\b/);
  assert.match(main, /new WindowsUpdatesPanel\(root, DispatcherQueue, \(\) =>\s*TrySavePlayback\(\)/);
});
