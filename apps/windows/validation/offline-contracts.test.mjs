import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const main = readFileSync(new URL('../Music.App/MainWindow.cs', import.meta.url), 'utf8');
const offline = () => readFileSync(new URL('../Music.App/MainWindow.Offline.cs', import.meta.url), 'utf8');
test('Previous and Next navigate the same model; replacing queues resets history', () => {
  const step = main.slice(main.indexOf('private void Step(int direction)'), main.indexOf('private void RenderQueue()'));
  assert.ok(step.includes('playbackOrder!.MovePrevious()'));
  assert.ok(step.includes('playbackOrder!.MoveNext()'));
  assert.ok(!step.includes('queueIndex + direction'));
  const previous = main.slice(main.indexOf('private void Previous()'), main.indexOf('private void Step(int direction)'));
  assert.ok(previous.includes('Position.TotalSeconds > 3'));
  assert.ok(previous.includes('Step(-1)'));
  for (const [start, end] of [['private void StartQueue(', 'private async void SelectTrack('], ['private void RestorePlayback()', 'private void StopPlayback()']]) {
    assert.ok(main.slice(main.indexOf(start), main.indexOf(end)).includes('playbackOrder = null'));
  }
  assert.ok(offline().includes('playbackOrder.Select(queueIndex)'));
});

test('settings, offline startup/browse and account leases are integrated into the window', () => {
  const source = offline();
  assert.ok(main.includes('InitializeOfflineSettings()'));
  assert.ok(main.includes('ActivateCache(profile)'));
  assert.ok(main.includes('if (offlineSettings.Offline || page == "offline")'));
  assert.ok(main.includes('if (offlineSettings.Offline)\n            {'));
  assert.ok(source.includes('Storage & offline'));
  assert.ok(source.includes('Clear automatic cache'));
  assert.ok(source.includes('Download song'));
  assert.ok(source.includes('Remove download'));
  assert.ok(source.includes('SaveOfflineSettings'));
  assert.ok(source.includes('NetworkInformation.NetworkStatusChanged'));
});

test('row actions, complete-only badges, intent-aware reconnect and shutdown are wired', () => {
  assert.ok(main.includes('AddDownloadMenu(row, song, owner)'));
  assert.ok(main.includes('SongAvailability(song)'));
  assert.ok(main.includes('playbackInterrupted = true'));
  assert.ok(main.includes('playIntended = false'));
  assert.ok(main.includes('ShutdownOffline()'));
  assert.ok(offline().includes('playbackInterrupted && playIntended'));
});
test('cache scheduling drains cancelled predecessors before clear and completion refreshes usage', () => {
  const source = offline();
  assert.ok(source.includes('await previousTask'));
  assert.ok(source.includes('token.ThrowIfCancellationRequested()'));
  assert.ok(source.includes('RefreshStorageUsage();'));
  assert.ok(main.includes('localPlayback'));
  assert.ok(main.includes('interruptedPosition : player.PlaybackSession.Position.TotalSeconds'));
});
test('explicit collection pin intentions are durable and resumed independently of memory', () => {
  const source = offline();
  assert.ok(source.includes('cache?.Pin(requested)'));
  assert.ok(source.includes('storage.Recent().Where(x => x.Pinned && !x.Complete)'));
  assert.ok(main.includes('capturedSongs'));
});
test('completed current media hands off with latest position/intent and shared playback order', () => {
  const source = offline();
  assert.ok(source.includes('HandoffCompletedSong(song.Id, scheduledPlaybackVersion)'));
  assert.ok(source.includes('version != playbackVersion || localPlayback'));
  assert.ok(source.includes('CacheHandoff.Resolve(songId, version'));
  assert.ok(source.includes('SelectTrack(queueIndex, handoff.Play, handoff.PositionSeconds)'));
  assert.ok(source.includes('playbackOrder.Upcoming(offlineSettings.NextCount)'));
  assert.ok(main.includes('playbackOrder!.MoveNext()'));
  assert.ok(source.includes('ShowError(CacheFailureMessage(error))'));
});
test('startup restores account cache before any ping and replacement purges removed account', () => {
  const init = main.slice(main.indexOf('private async Task InitializeAsync'), main.indexOf('private static async Task<(IReadOnlyList<Album>'));
  assert.ok(init.indexOf('RestorePlayback()') < init.indexOf('await client.PingAsync'));
  assert.ok(main.includes('cache?.Purge()'));
  assert.ok(main.includes('sessions.RemovePlayback(previous.Id)'));
});
test('optional cache storage failure cannot prevent server connection or playback', () => {
  const source = offline();
  assert.ok(source.includes('Song storage is unavailable. Streaming still works'));
  assert.ok(source.includes('cacheMaintenance != 0'));
});
test('selection invalidates transfers and publishes live cache protection before resolving or opening a local path', () => {
  const select = main.slice(main.indexOf('private async void SelectTrack('), main.indexOf('private void TogglePlayback()'));
  const cancel = select.indexOf('cacheCancellation.Cancel()');
  const protect = select.indexOf('cache?.SetProtectedSongs(new[] { song.Id, previous }.OfType<string>())');
  const activate = select.indexOf('activeSongId = song.Id');
  const find = select.indexOf('cache?.Find(song.Id)');
  const open = select.indexOf('await Windows.Storage.StorageFile.GetFileFromPathAsync(local)');
  assert.ok(cancel >= 0 && cancel < protect && protect < activate && activate < find && find < open);
  assert.ok(select.includes('activeSongId != song.Id ? activeSongId : previousSongId'));
  assert.ok(select.indexOf('version != playbackVersion', open) < select.indexOf('await file.OpenReadAsync()'));
  assert.ok(select.includes('if (closed || version != playbackVersion) { opened.Dispose(); return; }'));
  const stop = main.slice(main.indexOf('private void StopPlayback()'), main.indexOf('private async Task ChooseOutputAsync()'));
  assert.ok(stop.indexOf('localStream?.Dispose()') < stop.indexOf('cache?.SetProtectedSongs([])'));
  assert.ok(offline().includes('cache.SetProtectedSongs(ProtectedTracks())'));
});

test('player resolves complete account-local storage before ephemeral remote streaming, without waiting on full download', () => {
  assert.match(main, /cache\?\.Find\(song.Id\)/);
  assert.ok(main.includes('MediaSource.CreateFromStream(localStream, contentType)'));
  assert.ok(main.includes('file.OpenReadAsync()'));
  assert.ok(main.includes('localStream?.Dispose()'));
  assert.match(main, /if \(offlineSettings.Offline\)/);
  assert.match(main, /ScheduleCache\(\)/);
  assert.match(main, /version != playbackVersion/);
});
