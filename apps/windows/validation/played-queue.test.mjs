import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = name => readFileSync(new URL(`../Music.App/${name}`, import.meta.url), 'utf8').replaceAll('\r\n', '\n');
const main = read('MainWindow.cs');
const shell = read('Shell.xaml');

test('direct detail selection validates occurrence before starting a fresh whole collection queue', () => {
  const detail = main.slice(main.indexOf('private void InitializeDetailList()'), main.indexOf('private void ClearDetail()'));
  assert.match(detail, /var owner = item.Owner/);
  assert.match(main, /new DetailSong\(song, index\) \{ Owner = connectionLease \}/);
  assert.match(detail, /!connectionGate.IsCurrent\(owner\)/);
  assert.match(detail, /detailLoading \|\| detailError/);
  assert.match(detail, /ReferenceEquals\(detailSongs\[item.Index\], item.Song\)/);
  assert.match(detail, /StartQueue\(detailSongs, index\)/);
  assert.doesNotMatch(detail, /ShuffleButton/);
  assert.doesNotMatch(detail, /Skip\(|Distinct/);
});

test('actual native Playing evidence is source and generation fenced, not MediaOpened or selection', () => {
  assert.match(main, /startedHandler = \(session, _\) =>/);
  const evidence = main.slice(main.indexOf('startedHandler = (session, _) =>'), main.indexOf('player.PlaybackSession.PlaybackStateChanged += startedHandler'));
  assert.ok(evidence.indexOf('session.PlaybackState != MediaPlaybackState.Playing') < evidence.indexOf('DispatcherQueue.TryEnqueue'));
  assert.match(evidence, /session.PlaybackState != MediaPlaybackState.Playing/);
  assert.match(main, /version != playbackVersion.*\(player.Source as MediaPlaybackItem\) != item/);
  assert.match(main, /PlaybackState != MediaPlaybackState.Playing/);
  assert.match(main, /playbackOrder!\.CaptureStartEvidence\(\)/);
  assert.ok(evidence.indexOf('startEvidence();') < evidence.indexOf('DispatcherQueue.TryEnqueue'));
  assert.match(main, /PlaybackStateChanged -= startedHandler/);
  const opened = main.slice(main.indexOf('openedHandler ='), main.indexOf('player.MediaOpened += openedHandler'));
  assert.doesNotMatch(opened, /MarkStarted/);
  assert.match(main, /playbackOrder!\.Finish\(\)/);
  assert.match(main, /playbackOrder\?\.Ended == true\) SelectTrack\(queueIndex, true\)/);
  assert.match(main, /order.ShufflePending\(\)/);
  assert.match(read('MainWindow.Offline.cs'), /playbackOrder\?\.Ended == true.*localPlayback/);
  const previous = main.slice(main.indexOf('private void Previous()'), main.indexOf('private void Step('));
  assert.match(previous, /playbackOrder\?\.Ended != true && player.PlaybackSession.Position.TotalSeconds > 3/);
});

test('queue uses lazy data rows and bounded native Played expander above current and future', () => {
  assert.match(shell, /x:Name="QueueList"[^>]*ItemTemplate="\{StaticResource DetailRowTemplate\}"/);
  assert.match(main, /list.ContainerContentChanging \+=/);
  assert.match(main, /args.InRecycleQueue/);
  assert.match(main, /new Expander/);
  assert.match(main, /AutomationProperties.SetName\(expander, "Played songs"\)/);
  assert.match(main, /MaxHeight = 240/);
  assert.match(main, /playedList.ItemsSource = playbackOrder/);
  assert.match(main, /SelectPlayed\(entry.VisitId/);
  const render = main.slice(main.indexOf('private void RenderQueue()'), main.indexOf('private void UpdateTransport()'));
  assert.match(render, /list.ItemsSource = entries/);
  assert.ok(render.indexOf('"Played"') < render.indexOf('"Now playing"'));
  assert.ok(render.indexOf('"Now playing"') < render.indexOf('"Up next"'));
  assert.doesNotMatch(render, /new QueueSongRow|SongCell\(|\.Items.Add\(/);
});
