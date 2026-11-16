import test from 'node:test';
import { readWorkflow, workflowJob } from './helpers/workflow.mjs';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const app = new URL('../apps/windows/Music.App/', import.meta.url);
const source = readFileSync(new URL('MainWindow.cs', app), 'utf8');
const shell = readFileSync(new URL('Shell.xaml', app), 'utf8');

test('window binds explicit update actions and requires successful session persistence before restart', () => {
  assert.match(source, /new WindowsUpdatesPanel\(root, DispatcherQueue,/);
  assert.match(source, /TrySavePlayback\(\)\s*\? Task.CompletedTask\s*: Task.FromException/);
  const shutdown = source.split('private void Shutdown()')[1];
  assert.match(shutdown, /updatesPanel\.Dispose\(\)/);
  assert.ok(shutdown.indexOf('updatesPanel.Dispose()') < shutdown.indexOf('sessions?.Dispose()'));
});

test('window and titlebar use the shared Android artwork, not a text-note substitute', () => {
  assert.match(shell, /<Image x:Name="AppIcon"/);
  assert.match(source, /AppWindow.SetIcon\(Path.Combine\(AppContext.BaseDirectory, "Assets", "Music.ico"\)\)/);
  assert.match(source, /C<Image>\("AppIcon"\).Source/);
  assert.match(source, /Path.Combine\(AppContext.BaseDirectory, "Assets", "Music.png"\)/);
});

test('new updater suites are wired into build and installer validation', () => {
  const ci = readWorkflow();
  const validate = workflowJob(ci, 'validate');
  const windows = workflowJob(ci, 'windows');
  assert.match(validate, /dotnet test apps\/windows\/Music.Update.Tests\/Music.Update.Tests.csproj/);
  assert.match(validate, /node --test[^\n]*apps\/windows\/validation\/visual-contracts.test.mjs/);
  assert.match(windows, /dotnet test apps\/windows\/Music.Update.Tests\/Music.Update.Tests.csproj/);
});
