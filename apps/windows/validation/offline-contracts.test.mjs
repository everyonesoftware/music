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
  assert.ok(source.includes('storage.Pin(requested)'));
  assert.ok(source.includes('recent.Where(x => x.Pinned && !x.Complete)'));
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
  assert.ok(main.includes('await PurgeAccountCacheAsync(previous)'));
  assert.ok(main.includes('sessions.RemovePlayback(previous.Id)'));
});
test('optional cache storage failure cannot prevent server connection or playback', () => {
  const source = offline();
  assert.ok(source.includes('Song storage is unavailable. Streaming still works'));
  assert.ok(source.includes('cacheMaintenance != 0'));
});
test('explicit removal awaits live protection refusal before dropping pending downloads', () => {
  const source = offline();
  const menu = source.slice(source.indexOf('private void AddDownloadMenu('), source.indexOf('private void NetworkChanged('));
  assert.match(menu, /bool removed = await storageWork.RunAsync\(\(\) => storage.RemoveDownload\(song.Id\)\)/);
  const awaited = menu.indexOf('bool removed = await');
  const leaseGuard = menu.indexOf('if (closed || storage != cache || !connectionGate.IsCurrent(owner)) return;', awaited);
  const refusal = menu.indexOf('if (!removed)', awaited);
  const pending = menu.indexOf('pendingDownloads.RemoveAll(');
  assert.ok(awaited >= 0 && awaited < leaseGuard && leaseGuard < refusal && refusal < pending);
  assert.match(menu.slice(refusal, pending), /ShowError\("[^"\n]*current or previous[^"\n]*"\); return;/);
});
test('selection publishes immediate protection before active state and queued local lookup', () => {
  const select = main.slice(main.indexOf('private async void SelectTrack('), main.indexOf('private void TogglePlayback()'));
  const cancel = select.indexOf('cacheCancellation.Cancel()');
  const work = select.indexOf('await storageWork.RunAsync(');
  const protect = select.indexOf('storage?.SetProtectedSongs(protectedSongs)');
  const activate = select.indexOf('activeSongId = song.Id');
  const find = select.indexOf('storage?.Find(song.Id)');
  const open = select.indexOf('await Windows.Storage.StorageFile.GetFileFromPathAsync(local)');
  assert.ok(cancel >= 0 && cancel < protect && protect < activate && activate < work && work < find && find < open);
  assert.doesNotMatch(select.slice(work, find), /SetProtectedSongs|activeSongId|previousSongId|playbackVersion|\bcache\b/);
  assert.ok(select.includes('if (closed || version != playbackVersion || storage != cache) return;'));
  assert.ok(select.includes('activeSongId != song.Id ? activeSongId : previousSongId'));
  assert.ok(select.indexOf('version != playbackVersion', open) < select.indexOf('await file.OpenReadAsync()'));
  assert.ok(select.includes('if (closed || version != playbackVersion) { opened.Dispose(); return; }'));
  const stop = main.slice(main.indexOf('private void StopPlayback()'), main.indexOf('private async Task ChooseOutputAsync()'));
  assert.ok(stop.indexOf('localStream?.Dispose()') < stop.indexOf('storage?.SetProtectedSongs([])'));
  assert.doesNotMatch(stop, /storageWork.RunAsync\(/);
  assert.ok(offline().includes('await storageWork.RunAsync(() => { old?.Dispose(); return new SongCache(root, account); })'));
});

test('player resolves complete account-local storage before ephemeral remote streaming, without waiting on full download', () => {
  assert.match(main, /storage\?\.Find\(song.Id\)/);
  assert.ok(main.includes('MediaSource.CreateFromStream(localStream, contentType)'));
  assert.ok(main.includes('file.OpenReadAsync()'));
  assert.ok(main.includes('localStream?.Dispose()'));
  assert.match(main, /if \(offlineSettings.Offline\)/);
  assert.match(main, /ScheduleCache\(\)/);
  assert.match(main, /version != playbackVersion/);
});

test('download clicks yield before pin persistence and rendering reads only memory snapshots', () => {
  const source = offline();
  const download = source.slice(source.indexOf('private async Task DownloadSongs('), source.indexOf('private void AddDownloadMenu('));
  assert.match(download, /await storageWork.RunAsync\(/);
  assert.match(download, /storage.Pin\(requested\)/);
  assert.match(download, /storage != cache/);
  const badge = source.slice(source.indexOf('private string SongAvailability('), source.indexOf('private void ShutdownOffline('));
  assert.match(badge, /Snapshot\(\).*TryGetValue/);
  assert.doesNotMatch(badge, /\.Recent\(|\.Find\(/);
  const usage = source.slice(source.indexOf('private void RefreshStorageUsage('), source.indexOf('private async Task TrimCacheAsync('));
  assert.match(usage, /Snapshot\(\)/);
  assert.doesNotMatch(usage, /\.Recent\(/);
});

test('download worker plans off-thread and completion does not rebuild the queue', () => {
  const source = offline();
  const schedule = source.slice(source.indexOf('private void ScheduleCache()'));
  assert.ok(schedule.indexOf('Task.Run(async () =>') < schedule.indexOf('storage.Recent()'));
  assert.doesNotMatch(schedule, /RenderQueue\(\)/);
  assert.match(schedule, /QueueCacheRefresh\(song.Id\)/);
  assert.match(source, /cacheRefreshTimer.Interval = TimeSpan.FromMilliseconds\(250\)/);
  assert.match(source, /WeakReference<TextBlock>/);
});

test('coalesced cache refresh updates offline list membership without rebuilding existing rows', () => {
  const source = offline();
  const refresh = source.slice(source.indexOf('private void QueueCacheRefresh('), source.indexOf('private void ShutdownOffline('));
  assert.doesNotMatch(refresh, /RenderOfflineBrowse\(\)/);
  assert.match(refresh, /RefreshOfflineRows\(\)/);
  const update = source.slice(source.indexOf('private void RefreshOfflineRows('), source.indexOf('private async Task DownloadSongs('));
  assert.match(update, /Items.RemoveAt\(/);
  assert.match(update, /Items.Add\(SongRow\(/);
  assert.doesNotMatch(update, /Items.Clear\(|AddSongs\(/);
});

test('same-queue local handoff reuses existing queue controls', () => {
  const render = main.slice(main.indexOf('private void RenderQueue()'), main.indexOf('private void UpdateTransport()'));
  const reuse = render.indexOf('ReferenceEquals(renderedQueue, queue)');
  assert.ok(reuse >= 0 && reuse < render.indexOf('list.Items.Clear()'));
  assert.ok(render.indexOf('return;', reuse) < render.indexOf('list.Items.Clear()'));
  assert.match(render, /UpdateQueueSelection\(list\)/);
});

test('account deletion waits for initialization and purges the explicit account even without an attached cache', () => {
  const source = offline();
  const purge = source.slice(source.indexOf('private async Task PurgeAccountCacheAsync('), source.indexOf('private async Task SetOfflineAsync('));
  assert.match(purge, /await cacheInitialization/);
  assert.match(purge, /new SongCache\(root, account\)/);
  assert.match(purge, /storage.Purge\(\)/);
  const remove = source.slice(source.indexOf('private async Task RemoveAccountAsync('), source.indexOf('private string SongAvailability('));
  assert.ok(remove.indexOf('await PurgeAccountCacheAsync(profile)') < remove.indexOf('profiles.Remove(profile)'));
  assert.doesNotMatch(remove, /storage\?\.Purge\(\)/);
});

test('credential commit and activation form a guarded non-cancellable phase', () => {
  const connect = main.slice(main.indexOf('private async Task ConnectAsync()'), main.indexOf('private void SetConnecting('));
  const commit = connect.indexOf('profiles.Commit(profile, password)');
  const activation = connect.indexOf('ActivateConnection(profile, candidate');
  assert.ok(connect.indexOf('await cacheInitialization') < commit);
  assert.ok(connect.indexOf('accountChangePending = true') < commit);
  assert.ok(connect.indexOf('cacheMaintenance++') < commit);
  assert.doesNotMatch(connect.slice(commit, activation), /!connectGate.IsCurrent\(lease\)/);
  assert.match(main, /CancelConnectButton.*Click[^\n]*if \(accountChangePending\) return/);
  assert.match(connect, /accountChangePending = false/);
});
