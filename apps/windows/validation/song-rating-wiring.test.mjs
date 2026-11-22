import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
const app = new URL('../Music.App/', import.meta.url);
const read = name => readFileSync(new URL(name, app), 'utf8');
const main = read('MainWindow.cs');
const shell = read('Shell.xaml');
const file = new URL('MainWindow.Ratings.cs', app);

test('Songs and collection tracks share the rating column with the list row renderer', () => {
  assert.equal((main.match(/"#", "Title \/ artist", "Album", "Rating", "Time"/g) ?? []).length, 2);
  assert.match(main, /Grid\.SetColumn\(rating, 3\); grid\.Children\.Add\(rating\)/);
  assert.match(main, /Grid\.SetColumn\(duration, showRatings \? 4 : 3\)/);
});

test('both native compact entry points use the same account-bound rating path', () => {
  assert.match(shell, /x:Name="PlayerRatingButton"/);
  assert.match(main, /CreateQueueRating\(song, connectionLease\)/);
  assert.ok(existsSync(file), 'missing shared rating integration');
  const body = read('MainWindow.Ratings.cs');
  assert.match(body, /ShowRatingPicker\(button, song, owner\)/);
  assert.match(body, /new SongRatingSession\(target\.RateSongAsync, target\.GetSongRatingAsync\)/);
  assert.match(body, /connectionGate\.IsCurrent\(owner\)/);
  assert.match(main, /queue\.Select\(ApplyConfirmedRating\)/);
});

test('the player rating sits immediately to the right of Repeat in the transport row', () => {
  const transport = shell.match(/<StackPanel\b[^>]*Orientation="Horizontal"[^>]*>\s*<Button x:Name="NextButton"[\s\S]*?<\/StackPanel>/)?.[0] ?? '';
  assert.match(transport, /<ToggleButton x:Name="RepeatButton"[^>]*\/>\s*<Button x:Name="PlayerRatingButton"[^>]*\/>/);
  assert.match(transport, /x:Name="PlayerRatingButton"[^>]*\bWidth="40"[^>]*MinHeight="36"[^>]*VerticalAlignment="Center"[^>]*Visibility="Collapsed"/);
  assert.match(shell, /x:Name="PlayerComposition"[^>]*>\s*<Grid\.ColumnDefinitions>[^\n]*<ColumnDefinition Width="1\.2\*" MinWidth="328"\/>/);
});

test('now-playing metadata uses flexible space without sharing its row with the rating', () => {
  const row = shell.match(/<Grid>\s*<Grid\.ColumnDefinitions>(?:(?!<\/Grid>)[\s\S])*?x:Name="TrackArtist"[^>]*\/>(?:(?!<\/Grid>)[\s\S])*?<\/Grid>/)?.[0];
  assert.ok(row, 'missing now-playing artwork and metadata row');
  const columns = Array.from(row.matchAll(/<ColumnDefinition\b[^>]*\bWidth="([^"]+)"/g), match => match[1]);
  assert.deepEqual(columns, ['48', '*'], 'track text must take only the space left after artwork');
  for (const name of ['TrackTitle', 'TrackArtist']) {
    assert.match(row, new RegExp(`<TextBlock x:Name="${name}"[^>]*TextTrimming="CharacterEllipsis"`));
  }
  assert.doesNotMatch(row, /PlayerRatingButton/);
});

test('ratings are opted into only for Songs and playlist details', () => {
  assert.match(main, /AddSongs\(section,[^\n]+showRatings: page == "songs"/);
  assert.match(main, /SongRow\(item\.Song,[^\n]+showRatings: detailIdentity\.Kind == "playlist"/);
  assert.match(main, /bool showRatings = false/);
  assert.doesNotMatch(read('MainWindow.Offline.cs'), /showRatings:/);
});

test('pending writes disable every rating surface and the displayed rating can be picked after an uncertain write', () => {
  const body = read('MainWindow.Ratings.cs');
  assert.match(body, /RatingAvailable\(RequestGate\.Lease owner\) => [^;]+!ratingWritePending/);
  assert.match(body, /paint\(GetRating\(song, owner\), RatingAvailable\(owner\)\)/);
  assert.match(body, /var display = \(song,[^\n]+RatingAvailable\(connectionLease\)\)/);
  assert.doesNotMatch(body, /state\.Get\(song\) == rating/);
  assert.match(body, /await state\.SetAsync\(song, rating, owner\.Token\)/);
});

test('confirmed song ratings update silently while failures still show an error', () => {
  const body = read('MainWindow.Ratings.cs');
  assert.doesNotMatch(body, /InfoBarSeverity\.Success|\.ActionButton\s*=/);
  assert.match(shell, /<InfoBar\b[^>]*x:Name="RatingNotice"[^>]*Severity="Error"[^>]*IsOpen="False"/);
  const change = body.slice(body.indexOf('private async Task ChangeRatingAsync'), body.indexOf('private bool OwnsRatingResult'));
  const [success, , failure] = change.split('catch');
  assert.match(success, /SavePlayback\(\)/);
  assert.doesNotMatch(success, /IsOpen\s*=\s*true/);
  assert.match(failure, /Could not confirm the rating[^\n]+\n\s*notice\.IsOpen = true/);
  assert.match(change, /finally[\s\S]*RefreshRatingViews\(\)/);
});
