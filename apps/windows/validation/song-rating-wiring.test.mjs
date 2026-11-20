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
