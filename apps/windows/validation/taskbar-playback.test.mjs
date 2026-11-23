import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const app = new URL('../Music.App/', import.meta.url);
const main = readFileSync(new URL('MainWindow.cs', app), 'utf8').replaceAll('\r\n', '\n');
const toolbar = () => readFileSync(new URL('TaskbarPlayback.cs', app), 'utf8').replaceAll('\r\n', '\n');

test('the taskbar preview invokes the same transport actions as the footer', () => {
  assert.match(main, /TaskbarPlayback\.Attach\(\s*WinRT\.Interop\.WindowNative\.GetWindowHandle\(this\),\s*DispatcherQueue,\s*Previous,\s*TogglePlayback,\s*\(\) => Step\(1\)\)/);
  assert.match(main, /if \(closed \|\| queueIndex < 0\) return;/);
  assert.match(toolbar(), /WmCommand[\s\S]*?ThumbnailClicked[\s\S]*?dispatcher\.TryEnqueue/);
});

test('the preview reflects play intent and disables transport when the queue is cleared', () => {
  assert.match(main, /taskbarPlayback\?\.Update\(enabled, playIntended\)/);
  assert.match(main, /playIntended = play; playbackInterrupted = false;\s*UpdateTaskbarPlayback\(\);/);
  assert.match(main, /private void TogglePlayback\(\)[\s\S]*?finally \{ UpdateTaskbarPlayback\(\); \}/);
  assert.match(main, /private void StopPlayback\(\)[\s\S]*?queueIndex = -1;[\s\S]*?RenderQueue\(\); UpdateTransport\(\);/);
  assert.match(toolbar(), /\.Playing \? "Pause" : "Play"/);
  assert.match(toolbar(), /enabled \? 0u : Disabled/);
});

test('the toolbar waits for Explorer readiness, rebuilds on recreation and detaches on close', () => {
  const source = toolbar();
  assert.match(source, /RegisterWindowMessage\("TaskbarButtonCreated"\)/);
  assert.match(source, /if \(message == taskbarButtonCreated\)[\s\S]*?added = false;\s*published = null;\s*QueuePublish\(\)/);
  assert.match(source, /if \(disposed \|\| !ready/);
  assert.match(source, /RemoveWindowSubclass\(hwnd, subclass, SubclassId\)/);
  assert.match(main, /private void Shutdown\(\)[\s\S]*?taskbarPlayback\?\.Dispose\(\)/);
});
