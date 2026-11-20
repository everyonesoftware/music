import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const read=n=>readFileSync(new URL('../Music.App/'+n,import.meta.url),'utf8').replaceAll('\r\n','\n');
test('native Artists shelf follows Playlists and Albums and shares linked header, arrows and search',()=>{
 const s=read('MainWindow.cs');
 assert.ok(s.indexOf('NewSection("Artists", "artists")')>s.indexOf('NewSection("Albums", "albums")'));
 assert.match(s,/target is "playlists" or "albums" or "artists"/);
 assert.match(s,/GetArtistsAsync\(lease.Token\)/);
 assert.match(s,/artists.Where\(a => Match\(a.Name\)\)/);
 assert.match(s,/foundArtists.Select/);
 assert.match(s,/\("ArtistsNav", "artists"\)/);
 assert.match(read('Shell.xaml'),/x:Name="ArtistsNav"/);
});
test('conditional native driver exercises three shelves and artist album/song views',()=>{
 const s=readFileSync(new URL('./native/NativeAcceptance.cs',import.meta.url),'utf8');
 assert.match(s,/getArtists.view/); assert.match(s,/getArtist.view/);
 assert.match(s,/"artistDetail"/); assert.match(s,/"artistAlbumBack"/);
 assert.match(s,/"artistSongsRealized"/);
});

test('artist header has circle artwork, native placeholder, album/song views and no download control',()=>{
 const s=read('MainWindow.Artists.cs');
 assert.match(s,/Ellipse/);assert.match(s,/Symbol.Contact/);
 assert.match(s,/"Albums"/);assert.match(s,/"All songs"/);
 assert.match(s,/CreateArtistTransport/);
 assert.match(s,/Width = 56, Height = 56/);assert.match(s,/Width = 48, Height = 48/);
 assert.match(s,/group.Children.Add\(play\); group.Children.Add\(shuffle\)/);
 assert.doesNotMatch(s,/Download|All songs by|ToggleButton|SetShuffled/);
 assert.match(s,/StartQueue\(prepared, 0\)/);
 assert.match(read('Shell.xaml'),/x:Name="ArtistAlbumList"/);
 assert.match(read('MainWindow.cs'),/albumView.ItemsSource = showAlbums \? artistAlbums : null/);
});
