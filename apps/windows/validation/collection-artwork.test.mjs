import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const read = file => readFileSync(new URL(`../Music.App/${file}`, import.meta.url), 'utf8').replaceAll('\r\n', '\n');
const shell = read('Shell.xaml');
const main = read('MainWindow.cs');
const artwork = read('MainWindow.SongArtwork.cs');

test('a 120 DIP cover shares the collection header with the title, count and transport above the track list', () => {
  const header = shell.match(/<Grid x:Name="CollectionHeader"[^>]*>[\s\S]*?(?=<StackPanel x:Name="DetailHeader")/)?.[0] ?? '';
  assert.match(header, /ColumnSpacing="20"/);
  assert.match(header, /<ColumnDefinition Width="120"\/><ColumnDefinition Width="\*"\/>/);
  assert.match(header, /<Border x:Name="CollectionCover" Width="120" Height="120"/);
  assert.match(header, /<Grid Grid.Column="1"[\s\S]*?<TextBlock x:Name="CollectionTitle"[^>]*HeadingLevel="Level1"[\s\S]*?<TextBlock x:Name="CollectionSummary"[\s\S]*?<Border Grid.Row="1" x:Name="CollectionActions"/);
  assert.match(main, /C<Border>\("CollectionActions"\).Child = CreateCollectionTransport\(\)/);
  assert.match(main, /C<TextBlock>\("CollectionTitle"\).Text = detailTitle/);
  assert.match(main, /C<Grid>\("PageHeader"\).Visibility = isCollection \? Visibility.Collapsed : Visibility.Visible/);
});

test('details load their own artwork on entry, preserve it when tracks arrive, and remove it on exit', () => {
  assert.match(main, /if \(!ReferenceEquals\(renderedCollectionCover, detailCollection\)\)[\s\S]*?Album album => album.CoverArtId[\s\S]*?Playlist playlist => playlist.CoverArtId[\s\S]*?ArtistAlbumNavigation origin => origin.Album.CoverArtId[\s\S]*?\.Child = CoverArtwork\(coverArtId, CancellationToken.None, connectionLease, 120\)/);
  assert.match(main, /private void ClearDetail\(\)[\s\S]*?ClearCollectionHeader\(\)/);
  assert.match(main, /if \(!isCollection\)[\s\S]*?ClearCollectionHeader\(\)[\s\S]*?RenderArtistHeader\(panel\)/);
  assert.match(main, /private void ClearCollectionHeader\(\)[\s\S]*?renderedCollectionCover = null;[\s\S]*?C<Border>\("CollectionCover"\).Child = null/);
  assert.match(artwork, /string.IsNullOrWhiteSpace\(id\)\) return/);
  assert.match(artwork, /image.ImageOpened[^\n]*Visibility.Collapsed/);
  assert.match(artwork, /image.ImageFailed[^\n]*Visibility.Visible/);
  assert.match(artwork, /cover.Unloaded[^\n]*pending\?\.Cancel\(\)[^\n]*image.Source = null/);
});
