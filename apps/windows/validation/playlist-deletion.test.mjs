import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';

const app = name => readFileSync(new URL(`../Music.App/${name}`, import.meta.url), 'utf8').replaceAll('\r\n', '\n');
const deletionPath = new URL('../Music.App/MainWindow.PlaylistDeletion.cs', import.meta.url);
const deletion = () => existsSync(deletionPath) ? readFileSync(deletionPath, 'utf8').replaceAll('\r\n', '\n') : '';

test('playlist list/grid ellipsis and detail overflow share guarded deletion, independent of primary navigation', () => {
  const main = app('MainWindow.cs');
  const transport = app('MainWindow.CollectionTransport.cs');
  assert.match(main, /BindPlaylistContext\(view\);[\s\S]*?view\.ItemClick[\s\S]*?await OpenCollectionAsync\(item\.Tag\)/);
  assert.match(main, /card\.Value is Playlist playlist && page == "playlists"[\s\S]*?AddPlaylistRowOverflow\(outer, playlist, layout\)/);
  assert.match(deletion(), /anchor\.ContextFlyout = menu;/);
  assert.match(deletion(), /AddPlaylistDeleteAction\(more, playlist, owner, menu, current\)/);
  assert.match(transport, /AddPlaylistDeleteAction\(device, playlist, owner, menu,/);
  assert.match(transport, /group\.Children\.Add\(play\); group\.Children\.Add\(shuffle\); group\.Children\.Add\(device\)/);
  assert.match(deletion(), /menu\.Items\.Add\(new MenuFlyoutSeparator\(\)\);\s*menu\.Items\.Add\(action\);/);
  assert.match(deletion(), /if \(eligible\(\)\) await ConfirmPlaylistDeletionAsync/);
});

test('native confirmation gate precedes the only delete write and restores focus on dismissal', () => {
  const source = deletion();
  assert.match(source, /Title = \$"Delete “\{playlist.Name\}”\?"/);
  assert.match(source, /song list from your music server\. The songs themselves stay in your library/);
  assert.match(source, /can’t be undone/);
  assert.match(source, /CloseButtonText = "Cancel", DefaultButton = ContentDialogButton.Close/);
  assert.match(source, /playlistDialog = dialog;[\s\S]*?await dialog.ShowAsync\(\)[\s\S]*?result != ContentDialogResult.Primary \|\| !current\(\)\) return;[\s\S]*?await target.DeletePlaylistAsync\(playlist, current, owner.Token\)/);
  assert.match(source, /finally[\s\S]*?playlistDialog = null;[\s\S]*?launcher.Focus\(FocusState.Programmatic\)/);
});

test('successful deletion invalidates catalog and history, returns to Playlists and announces success without touching playback or saved audio', () => {
  const source = deletion();
  const shell = app('Shell.xaml');
  assert.match(source, /RememberCollectionMembership\(\("playlist", playlist.Id\), null\)/);
  assert.match(source, /navigationHistory.RemoveCollection/);
  assert.match(source, /await NavigateAsync\("playlists", remember: false\)/);
  assert.match(source, /PlaylistHeading"\).Focus\(FocusState.Programmatic\)/);
  assert.match(source, /notice.Message = message;[\s\S]*?notice.IsOpen = true/);
  assert.match(shell, /<ContentControl[^>]*x:Name="PlaylistHeading"[^>]*AutomationProperties.HeadingLevel="Level1"/);
  const mutation = source.slice(source.indexOf('private async Task ConfirmPlaylistDeletionAsync'));
  assert.doesNotMatch(mutation, /StartQueue|StopPlayback|SelectTrack|RemoveFromDeviceAsync|DeleteCache/);
});

test('an unknown deletion retains its exact target and offers an explicit read-only check outside the catalog', () => {
  const source = deletion();
  const main = app('MainWindow.cs');
  const shell = app('Shell.xaml');
  assert.match(source, /outcome == PlaylistDeletionResult.Unverified[\s\S]*?pendingPlaylistDeletions\[playlist.Id\] = playlist;[\s\S]*?InvalidatePlaylistDestination\(playlist.Id\)/);
  assert.match(shell, /<StackPanel x:Name="PlaylistDeletionChecks"/);
  const render = source.slice(source.indexOf('private void RenderPlaylistDeletionChecks'), source.indexOf('private async Task CheckPlaylistDeletionAsync'));
  assert.match(render, /foreach \(var playlist in pendingPlaylistDeletions.Values\)/);
  assert.match(render, /Content = "Check deletion again"[\s\S]*?CheckPlaylistDeletionAsync\(playlist, owner, notice\)/);
  const check = source.slice(source.indexOf('private async Task CheckPlaylistDeletionAsync'), source.indexOf('private async Task PublishPlaylistDeletionAsync'));
  assert.match(check, /CanEditPlaylists\(owner\)[\s\S]*?await target.CheckPlaylistDeletionAsync\(playlist.Id, owner.Token\)[\s\S]*?CanEditPlaylists\(owner\)/);
  assert.match(check, /outcome == PlaylistDeletionResult.Unverified[\s\S]*?return;[\s\S]*?pendingPlaylistDeletions.Remove\(playlist.Id\)[\s\S]*?await PublishPlaylistDeletionAsync/);
  assert.doesNotMatch(check, /target.DeletePlaylistAsync|GetPlaylistContentsAsync/);
  assert.match(main, /pendingPlaylistDeletions.ContainsKey\(pending.Id\)[\s\S]*?detailError = true;[\s\S]*?return;[\s\S]*?GetPlaylistContentsAsync\(playlist.Id, lease.Token\)/);
  assert.match(main, /pendingPlaylistDeletions.Clear\(\)/);
  assert.match(app('MainWindow.Offline.cs'), /pendingPlaylistDeletions.Clear\(\)/);
});

test('playlist row Save to Device rechecks fresh membership and never pins an empty result', () => {
  const source = deletion();
  const save = source.slice(source.indexOf('async Task save()'), source.indexOf('var more = CreateDeviceActions'));
  assert.match(save, /if \(contents.Songs.Count == 0\)\s*\{[^{}]*\breturn;\s*\}\s*await SaveToDeviceAsync\(contents.Songs, key, owner\)/);
  assert.match(save, /await target.GetPlaylistContentsAsync\(playlist.Id, owner.Token\);\s*if \(!deviceAvailable\(\) \|\| !ReferenceEquals\(target, client\)\) return;/);
  const offline = app('MainWindow.Offline.cs');
  const pin = offline.slice(offline.indexOf('private async Task SaveToDeviceAsync('), offline.indexOf('private async Task RemoveFromDeviceAsync('));
  assert.match(pin, /if \([^\n]*tracks.Count == 0[^\n]*\) return;[\s\S]*?storage.Pin\(tracks, key\)[\s\S]*?tracks\[0\]/);
});

test('both still-present delete readbacks invalidate stale detail and require a fresh read before reuse', () => {
  const source = deletion();
  const confirm = source.slice(source.indexOf('private async Task ConfirmPlaylistDeletionAsync('), source.indexOf('private void RenderPlaylistDeletionChecks('));
  const check = source.slice(source.indexOf('private async Task CheckPlaylistDeletionAsync('), source.indexOf('private void PublishPlaylistStillPresent('));
  assert.match(confirm, /var \(outcome, present\) = await target.DeletePlaylistAsync[\s\S]*?PublishPlaylistStillPresent\(present\);/);
  assert.match(check, /pendingPlaylistDeletions.Remove\(playlist.Id\)[\s\S]*?PublishPlaylistStillPresent\(present\);/);
  assert.equal([...source.matchAll(/target.DeletePlaylistAsync\(/g)].length, 1);
  const reconcile = source.slice(source.indexOf('private void PublishPlaylistStillPresent('), source.indexOf('private async Task PublishPlaylistDeletionAsync('));
  assert.match(reconcile, /playlistEdits\[present.Id\] = \(playlistCatalogRevision, present\);[\s\S]*?MergePlaylistCatalog\(playlists, beforeCheck\)[\s\S]*?InvalidatePlaylistDestination\(present.Id\)/);
  assert.doesNotMatch(reconcile, /StartQueue|StopPlayback|SelectTrack/);
  const playlists = app('MainWindow.Playlists.cs');
  const invalidate = playlists.slice(playlists.indexOf('private void InvalidatePlaylistDestination('), playlists.indexOf('private void RenderUnverifiedPlaylistAction('));
  assert.match(invalidate, /unverifiedPlaylistIds.Add\(id\)[\s\S]*?detailIdentity != identity\) return;[\s\S]*?browseGate.Cancel\(\)[\s\S]*?detailSongs = \[\];[\s\S]*?detailError = true;[\s\S]*?RenderDetail\(\)/);
  assert.match(playlists, /Content = "Retry playlist"[\s\S]*?await OpenCollectionAsync\(playlist, remember: false\)/);
  const main = app('MainWindow.cs');
  assert.match(main, /if \(unverifiedPlaylistIds.Contains\(playlist.Id\)\)[\s\S]*?await target.GetPlaylistContentsAsync\(playlist.Id, lease.Token\)[\s\S]*?unverifiedPlaylistIds.Remove\(refreshed.Playlist.Id\)/);
});
