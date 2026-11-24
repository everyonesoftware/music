import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdtempSync, writeFileSync, rmSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

const app = new URL('../Music.App/', import.meta.url);
const read = name => readFileSync(new URL(name, app), 'utf8').replaceAll('\r\n', '\n');
const main = read('MainWindow.cs');
const navigation = read('MainWindow.Navigation.cs');
const artistsSource = read('MainWindow.Artists.cs');
const playlistSource = read('MainWindow.Playlists.cs');
const method = (source, start, end) => source.slice(source.indexOf(start), source.indexOf(end, source.indexOf(start)));

test('delayed real startup/navigation methods preserve user destination and Back history without cancelling catalog load', () => {
  // Compile the actual production method bodies, not a second implementation of routing.
  // Only UI/storage/network boundaries are substituted; real RequestGate and history are linked.
  const artifacts = fileURLToPath(new URL('../artifacts/', import.meta.url));
  mkdirSync(artifacts, { recursive: true });
  const dir = mkdtempSync(join(artifacts, 'startup-navigation-'));
  try {
    const linked = ['NavigationHistory.cs', 'RequestGate.cs', 'BrowsePolicy.cs'].map(name =>
      `<Compile Include="${fileURLToPath(new URL(name, app))}" Link="${name}"/>`).join('');
    writeFileSync(join(dir, 'StartupChecks.csproj'), `<Project Sdk="Microsoft.NET.Sdk"><PropertyGroup><OutputType>Exe</OutputType><TargetFramework>net10.0</TargetFramework><ImplicitUsings>enable</ImplicitUsings><Nullable>enable</Nullable><NoWarn>CS0649;CS0414</NoWarn></PropertyGroup><ItemGroup>${linked}</ItemGroup></Project>`);
    const revisionField = navigation.split('\n').filter(line => /^\s*private long navigationRevision\b/.test(line)).join('\n');
    const playlistFields = playlistSource.split('\n').filter(line => /^\s*private .+\b(?:playlistCatalogRevision;|playlistEdits =|unverifiedPlaylistIds =)/.test(line)).join('\n');
    const deletionFields = read('MainWindow.PlaylistDeletion.cs').split('\n')
      .filter(line => /^\s*private .+\b(?:deletedPlaylistIds =|pendingPlaylistDeletions =|playlistDeletionActive;)/.test(line)).join('\n');
    writeFileSync(join(dir, 'Program.cs'), `
using Music.App;
await MainWindow.Run();
class MainWindow
{
${revisionField}
${playlistFields}
${deletionFields}
${method(playlistSource, '    private IReadOnlyList<Playlist> MergePlaylistCatalog(', '\n}')}
${method(playlistSource, '    private void InvalidatePlaylistDestination(', '    private void RenderUnverifiedPlaylistAction(')}
${method(main, '    private async Task InitializeAsync()', '    private static async Task<(IReadOnlyList<Album>')}
${method(main, '    private async Task NavigateAsync(', '    private void SetNavigation()')}
${method(main, '    private async Task LoadBrowseAsync(', '    private StackPanel NewSection(')}
${method(main, '    private void AddLoadMore(', '    private string detailTitle')}
${method(main, '    private async Task OpenCollectionAsync(', '    private void RenderDetail()')}
${method(artistsSource, '    private async Task OpenArtistAsync(', '    private void RenderArtistHeader(')}
${method(navigation, '    private async Task GoBackAsync()', '\n}')}
    readonly NavigationHistory navigationHistory = new();
    readonly RequestGate connectGate = new(), browseGate = new(), artworkGate = new(), connectionGate = new();
    RequestGate.Lease connectionLease;
    ProfileStore? profiles; SessionStore? sessions;
    NavidromeClient? client;
    ConnectionProfile? connection;
    readonly OfflineSettings offlineSettings = new();
    Task cacheInitialization = Task.CompletedTask;
    bool closed, updatingNavigation, browsePending, detailLoading, detailError, accountChangePending, artistsLoaded;
    IReadOnlyList<Artist> artists = [];
    IReadOnlyList<Album> artistAlbums = [];
    bool artistSongsIncomplete;
    string artistView = "albums";
    long browseResultsVersion;
    List<Song> songs = [];
    int songOffset;
    bool moreSongs;
    string page = "library", query = "", mode = "grid", detailTitle = "";
    object? detailCollection;
    (string, string) detailIdentity;
    List<Song> detailSongs = [];
    IReadOnlyList<Album> albums = [];
    IReadOnlyList<Playlist> playlists = [];
    readonly TaskCompletionSource cache = new(), ping = new(), catalog = new();
    string delay = "";
    bool fail, restored, catalogRead;
    readonly Dictionary<string, object> controls = new();
    T C<T>(string name) where T : new()
    {
        if (!controls.ContainsKey(name)) controls[name] = new T();
        return (T)controls[name];
    }
    List<string> Rendered => C<StackPanel>("BrowsePanel").Children.OfType<StackPanel>()
        .SelectMany(section => section.Children).OfType<string>().ToList();
    StackPanel NewSection(string title, string target) => new();
    record Card(string Name, string Subtitle, string? Art, object Value);
    void AddCollection(StackPanel section, List<Card> cards, string layout, CancellationToken token)
        => section.Children.AddRange(cards.Select(card => (object)card.Name));
    void AddSongs(StackPanel section, List<Song> tracks, string layout, CancellationToken token, bool showRatings = false)
        => section.Children.AddRange(tracks.Select(song => (object)song.Id));
    string Text(string text, int size, bool muted) => text;
${method(navigation, '    private NavigationView CaptureNavigation()', '    private void InitializeNavigation()')}
    string ReadMode(string _) => "grid";
    void InitializeOfflineSettings() { }
    void ActivateConnection(ConnectionProfile profile, NavidromeClient target, IReadOnlyList<Album> a, IReadOnlyList<Playlist> p)
    {
        navigationHistory.Clear(); detailCollection = null;
        client = target; connection = profile; connectionLease = connectionGate.Begin();
        client.Ping = async () => { if (delay == "ping") await ping.Task; if (fail && delay == "ping") throw new Exception(); };
        cacheInitialization = delay == "cache" ? cache.Task : Task.CompletedTask;
    }
    void RestorePlayback() => restored = true;
    void ClearDetail() { detailLoading = false; }
    void SetNavigation() { }
    void RenderDetail() { }
    void InvalidateCollectionMembership() { }
    readonly Dictionary<(string, string), IReadOnlyList<Song>> membership = new();
    readonly List<(string Key, WeakReference<Border> Badge)> collectionBadges = [];
    void RememberCollectionMembership((string Kind, string Id) identity, IReadOnlyList<Song>? tracks)
    {
        if (tracks is null) membership.Remove(identity);
        else membership[identity] = tracks;
    }
    void RenderOfflineBrowse() { }
    void ShowError(string _) { }
    async Task<(IReadOnlyList<Album> Albums, IReadOnlyList<Playlist> Playlists)> ReadLibraryAsync(NavidromeClient target, CancellationToken token)
    {
        catalogRead = true;
        if (delay == "catalog") await catalog.Task;
        if (fail && delay == "catalog") throw new Exception();
        return (new[] { new Album("loaded", "Loaded album"), new Album("other", "Other album") }, new[] { new Playlist("loaded", "Loaded playlist"), new Playlist("other", "Other playlist") });
    }
    static void Check(bool value, string message) { if (!value) throw new Exception(message); }
    public static async Task Run()
    {
        {
            var window = new MainWindow(); await window.InitializeAsync();
            var playlist = new Playlist("checking", "Before edit");
            var delayed = new TaskCompletionSource<IReadOnlyList<Song>>();
            window.client!.PlaylistSongs = (_, _) => delayed.Task;
            var original = window.OpenCollectionAsync(playlist);
            window.detailSongs = [new Song("stale")];
            window.RememberCollectionMembership(("playlist", playlist.Id), window.detailSongs);
            var badge = new Border(); window.collectionBadges.Add(("playlist:" + playlist.Id, new(badge)));
            window.InvalidatePlaylistDestination(playlist.Id);
            delayed.SetResult([new Song("stale")]); await original;
            Check(window.detailError && !window.detailLoading && window.detailSongs.Count == 0 &&
                !window.membership.ContainsKey(("playlist", playlist.Id)) && badge.Visibility == Visibility.Collapsed,
                "ambiguous append clears stale playable detail and saved badge even if an old load finishes");
            int strictReads = 0;
            window.client.PlaylistSongs = (_, _) => throw new Exception("must not use a loose playlist read");
            window.client.PlaylistDetails = (id, _) =>
            {
                Check(id == playlist.Id, "reopen must read the exact destination");
                if (++strictReads == 1) throw new IOException("read failed");
                return Task.FromResult(new PlaylistContents(new Playlist(id, "Server title"), [new Song("new"), new Song("new")]));
            };
            await window.OpenCollectionAsync(playlist, remember: false);
            Check(window.detailError && window.detailSongs.Count == 0 && window.unverifiedPlaylistIds.Contains(playlist.Id), "failed exact read leaves details unavailable");
            await window.OpenCollectionAsync(playlist, remember: false);
            Check(!window.detailError && window.detailTitle == "Server title" && strictReads == 2 &&
                window.detailSongs.Select(song => song.Id).SequenceEqual(new[] { "new", "new" }) &&
                !window.unverifiedPlaylistIds.Contains(playlist.Id), "successful exact read restores only authoritative ordered songs");
        }
        {
            var window = new MainWindow(); await window.InitializeAsync();
            var playlist = new Playlist("pending-delete", "Not in the current catalog");
            int reads = 0;
            window.client!.PlaylistSongs = (_, _) => { reads++; throw new IOException(); };
            window.client.PlaylistDetails = (_, _) => { reads++; throw new IOException(); };
            window.pendingPlaylistDeletions[playlist.Id] = playlist;
            window.InvalidatePlaylistDestination(playlist.Id);
            await window.OpenCollectionAsync(playlist);
            Check(window.detailError && !window.detailLoading && window.detailSongs.Count == 0 && reads == 0,
                "reopening pending deletion never consumes its explicit check or restores stale songs");
        }
        {
            var window = new MainWindow { delay = "catalog" };
            var start = window.InitializeAsync();
            Check(window.catalogRead, "catalog request is held before the edit");
            await window.NavigateAsync("playlists");
            window.playlistEdits["loaded"] = (++window.playlistCatalogRevision, new Playlist("loaded", "Edited playlist"));
            window.playlistEdits["new"] = (++window.playlistCatalogRevision, new Playlist("new", "New playlist"));
            window.catalog.SetResult(); await start;
            Check(window.Rendered.Contains("Edited playlist") && window.Rendered.Contains("New playlist") &&
                window.Rendered.Contains("Other playlist") && !window.Rendered.Contains("Loaded playlist"),
                "late catalog renders both confirmed edits and unaffected server playlists");
        }
        {
            var window = new MainWindow { delay = "catalog" };
            var start = window.InitializeAsync();
            await window.NavigateAsync("playlists");
            window.deletedPlaylistIds.Add("loaded");
            window.playlistCatalogRevision++;
            window.catalog.SetResult(); await start;
            Check(window.Rendered.Contains("Other playlist") && !window.Rendered.Contains("Loaded playlist"),
                "late catalog cannot resurrect a deleted playlist or hide its sibling");
            await window.OpenCollectionAsync(new Playlist("loaded", "Loaded playlist"));
            Check(window.page == "playlists", "stale card cannot reopen deleted detail");
        }
        {
            var window = new MainWindow(); await window.InitializeAsync();
            window.artists = new[] { new Artist("a", "Same"), new Artist("b", "Same"), new Artist("c", "Other") };
            window.artistsLoaded = true;
            await window.NavigateAsync("artists"); window.query = "same"; await window.LoadBrowseAsync();
            Check(window.Rendered.SequenceEqual(new[] { "Same", "Same" }), "artist search preserves distinct same-name library artists");
            await window.NavigateAsync("library"); window.query = "Same"; await window.LoadBrowseAsync();
            Check(window.Rendered.SequenceEqual(new[] { "Same", "Same" }), "library search includes real artist matches");
            Console.WriteLine("PASS artist destination and library search retain same-name artists");
        }
        foreach (var stale in new[] { "navigation", "account" })
        {
            var window = new MainWindow(); await window.InitializeAsync();
            window.artistsLoaded = false; window.page = "artists";
            var response = new TaskCompletionSource<IReadOnlyList<Artist>>();
            window.client!.ArtistIndex = _ => response.Task;
            var load = window.LoadBrowseAsync();
            if (stale == "navigation") await window.NavigateAsync("settings");
            else window.client = new NavidromeClient(new ConnectionProfile(), "");
            response.SetResult(new[] { new Artist("old", "Old account") }); await load;
            Check(window.artists.Count == 0 && !window.artistsLoaded, "stale artist index must not publish after " + stale);
            Console.WriteLine("PASS artist index stale " + stale);
        }
        foreach (var activePage in new[] { "albums", "playlists", "library" })
        {
            var window = new MainWindow { delay = "catalog" };
            var startup = window.InitializeAsync();
            await window.NavigateAsync(activePage);
            window.query = "Loaded"; window.mode = "list";
            window.C<TextBox>("SearchBox").Text = window.query;
            await window.LoadBrowseAsync();
            Check(window.Rendered.Count == 0, "delayed catalog must initially render empty " + activePage);
            var chosen = window.CaptureNavigation();
            long revision = window.navigationRevision, version = window.browseResultsVersion;
            window.catalog.SetResult(); await startup;
            var expected = activePage == "library" ? new[] { "Loaded playlist", "Loaded album" }
                : new[] { activePage == "albums" ? "Loaded album" : "Loaded playlist" };
            Check(window.Rendered.SequenceEqual(expected), "startup must refresh RENDERED catalog for active " + activePage);
            Check(window.CaptureNavigation() == chosen && window.C<TextBox>("SearchBox").Text == "Loaded",
                "publication must preserve current route/query/layout");
            Check(window.navigationRevision == revision && window.browseResultsVersion == version,
                "publication must not navigate or replace browse work");
            await window.NavigateAsync(activePage);
            Check(window.Rendered.SequenceEqual(expected) && window.CaptureNavigation() == chosen,
                "same-page sidebar must retain published filtered catalog");
            await window.GoBackAsync();
            Check(window.page == "offline" && !window.navigationHistory.TryBack(out _), "publication must preserve exact history");
            Console.WriteLine("PASS rendered delayed catalog " + activePage + " query/layout/history");
        }
        {
            var window = new MainWindow { delay = "catalog" };
            var startup = window.InitializeAsync();
            await window.NavigateAsync("library");
            var response = new TaskCompletionSource<IReadOnlyList<Song>>();
            CancellationToken searchToken = default;
            window.client!.Search = (q, offset, token) => { searchToken = token; return response.Task; };
            window.query = "Loaded"; window.mode = "list";
            var search = window.LoadBrowseAsync();
            var chosen = window.CaptureNavigation();
            long version = window.browseResultsVersion;
            window.catalog.SetResult(); await startup;
            Check(!search.IsCompleted && !searchToken.IsCancellationRequested && window.browsePending &&
                window.C<StackPanel>("BrowsePanel").Children.Count == 0 && window.browseResultsVersion == version &&
                window.CaptureNavigation() == chosen, "publication must not cancel, replace or render over active search");
            response.SetResult(new[] { new Song("Loaded song") }); await search;
            Check(window.Rendered.SequenceEqual(new[] { "Loaded playlist", "Loaded album", "Loaded song" }),
                "pending search must render latest catalog with its own results");
            Console.WriteLine("PASS active search retains ownership and renders published catalog on completion");
        }
        {
            var window = new MainWindow { delay = "catalog" };
            var startup = window.InitializeAsync();
            window.client!.Search = (_, _, _) => Task.FromResult<IReadOnlyList<Song>>(
                Enumerable.Range(0, 100).Select(i => new Song("song" + i)).ToArray());
            await window.NavigateAsync("songs");
            var section = (StackPanel)window.C<StackPanel>("BrowsePanel").Children.Single();
            var button = section.Children.OfType<Button>().Single();
            var response = new TaskCompletionSource<IReadOnlyList<Song>>();
            CancellationToken paginationToken = default;
            window.client.Search = (_, offset, token) => {
                Check(offset == 100, "pagination must keep its offset"); paginationToken = token; return response.Task;
            };
            button.Invoke();
            var chosen = window.CaptureNavigation();
            long version = window.browseResultsVersion;
            window.catalog.SetResult(); await startup;
            Check(!paginationToken.IsCancellationRequested && !button.IsEnabled &&
                ReferenceEquals(window.C<StackPanel>("BrowsePanel").Children.Single(), section) &&
                window.songOffset == 100 && window.browseResultsVersion == version && window.CaptureNavigation() == chosen,
                "publication must not cancel, replace or render over active pagination");
            response.SetResult(new[] { new Song("next-page") });
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            while (!window.Rendered.Contains("next-page")) { timeout.Token.ThrowIfCancellationRequested(); await Task.Yield(); }
            Check(window.Rendered.Count == 101 && window.songOffset == 101, "pagination must publish its complete rendered result");
            Console.WriteLine("PASS active pagination retains controls, generation, offset and rendered results");
        }
        foreach (var destination in new[] { "detail", "settings", "offline" })
        {
            var window = new MainWindow { delay = "catalog" };
            var startup = window.InitializeAsync();
            if (destination == "detail") await window.OpenCollectionAsync(new Album("chosen", "Chosen"));
            else await window.NavigateAsync(destination);
            var panel = window.C<StackPanel>("BrowsePanel");
            var sentinel = new object(); panel.Children.Add(sentinel);
            var chosen = window.CaptureNavigation();
            window.catalog.SetResult(); await startup;
            Check(window.CaptureNavigation() == chosen && panel.Children.Contains(sentinel),
                "catalog publication must not touch " + destination);
            Console.WriteLine("PASS catalog publication leaves " + destination + " untouched");
        }
        foreach (var delay in new[] { "cache", "ping", "catalog" })
        foreach (var fail in new[] { false, true })
        {
            var window = new MainWindow { delay = delay, fail = fail };
            var startup = window.InitializeAsync();
            Check(!startup.IsCompleted, "startup must be suspended at " + delay);
            string origin = window.page;
            await window.NavigateAsync("albums");
            var album = new Album("chosen", "Chosen album");
            await window.OpenCollectionAsync(album);
            if (fail && delay == "cache") window.cache.TrySetException(new Exception());
            else window.cache.TrySetResult();
            window.ping.TrySetResult(); window.catalog.TrySetResult();
            await startup;
            Check(window.page == "detail" && Equals(window.detailCollection, album), delay + (fail ? " failure" : " success") + " startup overwrote user album");
            Check(window.restored == !(fail && delay == "cache"), "startup playback restore should continue after successful cache initialization");
            Check(window.catalogRead == !(fail && delay is "cache" or "ping"), "navigation must not cancel useful catalog work");
            if (!fail) Check(window.albums.Count == 2, "startup catalog must still publish after navigation");
            await window.GoBackAsync();
            Check(window.page == "albums", "first Back must restore Albums");
            await window.GoBackAsync();
            Check(window.page == origin, "second Back must restore original startup page");
            Check(!window.navigationHistory.TryBack(out _), "startup must not add history");
            Console.WriteLine("PASS delayed " + delay + (fail ? " failure" : " success") + " album/history/catalog");
        }
        foreach (var delay in new[] { "cache", "ping", "catalog" })
        {
            var window = new MainWindow { delay = delay };
            var startup = window.InitializeAsync();
            await window.NavigateAsync("settings");
            await window.GoBackAsync();
            string returned = window.page;
            window.cache.TrySetResult(); window.ping.TrySetResult(); window.catalog.TrySetResult();
            await startup;
            Check(window.page == returned && !window.navigationHistory.TryBack(out _), "Back during " + delay + " startup must invalidate automatic routes");
            Console.WriteLine("PASS delayed " + delay + " Settings/Back");
        }
        foreach (var delay in new[] { "cache", "ping", "catalog" })
        foreach (var action in new[] { "album", "settings", "same-page" })
        foreach (bool fail in new[] { false, true })
        {
            var window = new MainWindow { delay = delay, fail = fail };
            var startup = window.InitializeAsync();
            var origin = window.CaptureNavigation();
            var album = new Album("direct", "Direct album");
            if (action == "album") await window.OpenCollectionAsync(album);
            else await window.NavigateAsync(action == "settings" ? "settings" : origin.Page);
            var chosen = window.CaptureNavigation();
            if (fail && delay == "cache") window.cache.TrySetException(new Exception());
            else window.cache.TrySetResult();
            window.ping.TrySetResult(); window.catalog.TrySetResult();
            await startup;
            Check(window.CaptureNavigation() == chosen, delay + " startup overwrote " + action);
            if (!fail) Check(window.albums.Count == 2, action + " must not cancel catalog load");
            if (action != "same-page")
            {
                await window.GoBackAsync();
                Check(window.CaptureNavigation() == origin, action + " Back must restore original view");
            }
            Check(!window.navigationHistory.TryBack(out _), action + " startup must not manufacture history");
            Console.WriteLine("PASS delayed " + delay + " direct " + action + (fail ? " failure" : " success"));
        }
        foreach (bool fail in new[] { false, true })
        {
            var window = new MainWindow { delay = "catalog", fail = fail };
            var startup = window.InitializeAsync();
            window.catalog.SetResult(); await startup;
            Check(window.page == (fail ? "offline" : "library"), "untouched startup must retain its default route");
            Check(!window.navigationHistory.TryBack(out _), "default startup routes must not create history");
            Console.WriteLine("PASS untouched startup " + (fail ? "failure" : "success"));
        }
        foreach (var delay in new[] { "cache", "ping", "catalog" })
        foreach (bool fail in new[] { false, true })
        {
            var window = new MainWindow { delay = delay, fail = fail };
            var startup = window.InitializeAsync();
            window.connectGate.Cancel();
            await window.NavigateAsync("settings");
            if (fail && delay == "cache") window.cache.TrySetException(new Exception());
            else window.cache.TrySetResult();
            window.ping.TrySetResult(); window.catalog.TrySetResult();
            await startup;
            Check(window.page == "settings" && window.albums.Count == 0, "superseded account must fence startup routes and data");
            Console.WriteLine("PASS account fence " + delay + (fail ? " failure" : " success"));
        }
    }
}
record Album(string Id, string Name, string Artist = "", int Year = 2026, string? CoverArtId = null);
record Playlist(string Id, string Name, int SongCount = 0, string? CoverArtId = null);
record PlaylistContents(Playlist Playlist, IReadOnlyList<Song> Songs);
record Song(string Id);
record Artist(string Id, string Name, int AlbumCount = 0, string? CoverArtId = null);
record ArtistLibrary(Artist Artist, IReadOnlyList<Album> Albums, IReadOnlyList<Song> Songs, bool IsComplete = true);
record ArtistNavigation(Artist Artist, string View);
record ArtistAlbumNavigation(Album Album);
record ConnectionProfile(string Name = "Saved", string ServerUrl = "https://example.test", string Username = "user");
class ProfileStore { public string DirectoryPath => "."; public ConnectionProfile ReadProfile() => new(); public string ReadPassword(ConnectionProfile _) => ""; }
class SessionStore { public SessionStore(string path) { } }
class OfflineSettings { public bool Offline; }
class NavidromeClient : IDisposable
{
    public NavidromeClient(ConnectionProfile profile, string password) { }
    public Func<Task> Ping = () => Task.CompletedTask;
    public Task PingAsync(CancellationToken token) => Ping();
    public Func<CancellationToken, Task<IReadOnlyList<Artist>>> ArtistIndex = _ => Task.FromResult<IReadOnlyList<Artist>>([]);
    public Task<IReadOnlyList<Artist>> GetArtistsAsync(CancellationToken token) => ArtistIndex(token);
    public Task<ArtistLibrary> GetArtistAsync(string id, CancellationToken token) => Task.FromResult(new ArtistLibrary(new Artist(id, "Artist"), [], []));
    public Func<string, int, CancellationToken, Task<IReadOnlyList<Song>>> Search = (_, _, _) => Task.FromResult<IReadOnlyList<Song>>([]);
    public Task<IReadOnlyList<Song>> SearchSongsAsync(string query, int offset, int count, CancellationToken token) => Search(query, offset, token);
    public Task<IReadOnlyList<Song>> GetAlbumSongsAsync(string id, CancellationToken token, bool validateCount = false) => Task.FromResult<IReadOnlyList<Song>>([]);
    public Func<string, CancellationToken, Task<IReadOnlyList<Song>>> PlaylistSongs = (_, _) => Task.FromResult<IReadOnlyList<Song>>([]);
    public Func<string, CancellationToken, Task<PlaylistContents>> PlaylistDetails = (_, _) => throw new Exception("Unexpected strict playlist read");
    public Task<IReadOnlyList<Song>> GetPlaylistSongsAsync(string id, CancellationToken token) => PlaylistSongs(id, token);
    public Task<PlaylistContents> GetPlaylistContentsAsync(string id, CancellationToken token) => PlaylistDetails(id, token);
    public void Dispose() { }
}
enum Visibility { Visible, Collapsed }
enum Symbol { ViewAll, List }
class TextBlock { public string Text = ""; }
class TextBox { public string Text = "", PlaceholderText = ""; public Visibility Visibility; }
class Button
{
    public Visibility Visibility; public object? Content; public bool IsEnabled = true;
    public event EventHandler? Click;
    public void Invoke() => Click?.Invoke(this, EventArgs.Empty);
}
class StackPanel { public Visibility Visibility; public int Spacing; public List<object> Children = []; }
class ProgressBar { public Visibility Visibility; }
class Border { public Visibility Visibility; }
class SymbolIcon { public Symbol Symbol; }
class ScrollViewer { public void ChangeView(object? x, int y, object? z) { } }
`);
    const result = spawnSync('dotnet', ['run', '--project', join(dir, 'StartupChecks.csproj'), '-c', 'Release'], { encoding: 'utf8', timeout: 120_000 });
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    console.log(result.stdout.trim());
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
