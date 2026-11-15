#if MUSIC_NATIVE_ACCEPTANCE
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation.Peers;
using Microsoft.UI.Xaml.Automation.Provider;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Music.Core;
using Windows.Devices.Enumeration;
using Windows.Media.Devices;
using Windows.Media.Playback;

namespace Music.App;

// Linked only into explicit acceptance builds. Never contains a real account.
internal static partial class NativeAcceptance
{
    internal static bool IsRequested => !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("MUSIC_NATIVE_ACCEPTANCE_STAGE"));
    internal static Uri MediaUri => new(Path.GetFullPath(Environment.GetEnvironmentVariable("MUSIC_NATIVE_ACCEPTANCE_MEDIA") ?? throw new InvalidOperationException()));
    internal const string ProfileId = "native-acceptance-disposable-profile";
    internal const string Password = "native-acceptance-fixture-only";
    internal const double Position = 7.25;

    internal static async Task WaitAsync(Func<bool> condition, int milliseconds = 10000)
    {
        var deadline = Environment.TickCount64 + milliseconds;
        while (!condition())
        {
            if (Environment.TickCount64 >= deadline) throw new TimeoutException();
            await Task.Delay(50);
        }
    }

    // Deliberately inspect only the path. Never retain or report signed query strings.
    internal sealed partial class FixtureHandler : HttpMessageHandler
    {
        internal readonly HashSet<string> Seen = new();
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            token.ThrowIfCancellationRequested();
            if (request.RequestUri?.Host != "music-native-acceptance.invalid") throw new InvalidOperationException();
            string path = request.RequestUri.AbsolutePath;
            Seen.Add(path);
            const string tracks = "[{\"id\":\"native-song-a\",\"title\":\"Native fixture A\",\"artist\":\"Fixture artist\",\"album\":\"Fixture album\",\"duration\":30},{\"id\":\"native-song-b\",\"title\":\"Native fixture B\",\"artist\":\"Fixture artist\",\"album\":\"Fixture album\",\"duration\":30}]";
            string payload = path switch
            {
                "/rest/ping.view" => "",
                "/rest/getAlbumList2.view" => ",\"albumList2\":{\"album\":[{\"id\":\"native-album\",\"name\":\"Fixture album\",\"artist\":\"Fixture artist\",\"songCount\":2,\"year\":2026}]}",
                "/rest/getPlaylists.view" => ",\"playlists\":{\"playlist\":[{\"id\":\"native-playlist\",\"name\":\"Fixture playlist\",\"songCount\":2}]}",
                "/rest/search3.view" => ",\"searchResult3\":{\"song\":" + tracks + "}",
                "/rest/getAlbum.view" => ",\"album\":{\"id\":\"native-album\",\"name\":\"Fixture album\",\"song\":" + tracks + "}",
                "/rest/getPlaylist.view" => ",\"playlist\":{\"entry\":" + tracks + "}",
                _ => throw new InvalidOperationException()
            };
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent("{\"subsonic-response\":{\"status\":\"ok\"" + payload + "}}", Encoding.UTF8, "application/json") });
        }
    }

    internal static void Write(string directory, string filename, string stage, string status, string category, IReadOnlyDictionary<string, bool> checks, string? mediaStatus = null)
    {
        Directory.CreateDirectory(directory);
        using var stream = new MemoryStream();
        using var json = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = true });
        json.WriteStartObject();
        json.WriteNumber("schemaVersion", 1);
        json.WriteString("stage", stage);
        json.WriteString("TargetVersion", typeof(MainWindow).Assembly.GetName().Version?.ToString(3));
        json.WriteString("status", status);
        json.WriteString("category", category);
        json.WriteBoolean("audibleOutputVerified", false);
        if (mediaStatus is not null)
        {
            json.WriteStartObject("media");
            json.WriteString("status", mediaStatus);
            json.WriteBoolean("audibleOutputVerified", false);
            json.WriteEndObject();
        }
        json.WriteStartObject("checks");
        foreach (var entry in checks) json.WriteBoolean(entry.Key, entry.Value);
        json.WriteEndObject();
        json.WriteEndObject();
        json.Flush();
        string destination = Path.Combine(directory, filename);
        string temporary = destination + ".new";
        File.WriteAllBytes(temporary, stream.ToArray());
        File.Move(temporary, destination, true);
    }
}

public sealed partial class MainWindow
{
    private static IEnumerable<DependencyObject> NativeTree(DependencyObject parent)
    {
        yield return parent;
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
            foreach (var child in NativeTree(VisualTreeHelper.GetChild(parent, i))) yield return child;
    }
    private static void NativeRequire(Dictionary<string, bool> checks, string name, bool value)
    {
        checks[name] = value;
        if (!value) throw new InvalidOperationException();
    }
    private static bool NativeHasRealizedFixtureText(DependencyObject parent) => NativeTree(parent).OfType<TextBlock>().Any(text =>
        text.ActualWidth > 0 && text.ActualHeight > 0 && (text.Text.StartsWith("Fixture", StringComparison.Ordinal) || text.Text.StartsWith("Native fixture", StringComparison.Ordinal)));
    private void NativeInvoke(string name) => ((IInvokeProvider)new ButtonAutomationPeer(C<Button>(name)).GetPattern(PatternInterface.Invoke)).Invoke();

    private static bool NativeVaultIsEmpty()
    {
        try { return !new Windows.Security.Credentials.PasswordVault().RetrieveAll().Any(c => c.Resource == "EveryoneSoftware.Music"); }
        catch (Exception error) when (error.HResult == unchecked((int)0x80070490)) { return true; }
    }

    private bool NativeCleanupFixtureCredential()
    {
        try
        {
            var store = profiles ?? new ProfileStore();
            if (store.ReadProfile() is not { Id: NativeAcceptance.ProfileId, Username: "fixture-user", ServerUrl: "https://music-native-acceptance.invalid" }) return false;
            if (store.ReadPassword(store.ReadProfile()!) != NativeAcceptance.Password) return false;
            var vault = new Windows.Security.Credentials.PasswordVault();
            vault.Remove(vault.Retrieve("EveryoneSoftware.Music", NativeAcceptance.ProfileId));
            try { vault.Retrieve("EveryoneSoftware.Music", NativeAcceptance.ProfileId); return false; }
            catch (Exception error) when (error.HResult == unchecked((int)0x80070490)) { return true; }
        }
        catch { return false; }
    }

    internal async Task RunNativeAcceptanceAsync()
    {
        string requested = Environment.GetEnvironmentVariable("MUSIC_NATIVE_ACCEPTANCE_STAGE") ?? "invalid";
        string stage = requested is "seed" or "restore" ? requested : "invalid";
        string? directory = Environment.GetEnvironmentVariable("MUSIC_NATIVE_ACCEPTANCE_DIRECTORY");
        var checks = new Dictionary<string, bool>();
        string status = "failed", category = "configuration";
        bool mediaWritten = false;
        string? mediaStatus = null;
        try
        {
            if (stage == "invalid" || string.IsNullOrWhiteSpace(directory) || !File.Exists(NativeAcceptance.MediaUri.LocalPath)) throw new InvalidOperationException();
            category = "shell";
            await NativeAcceptance.WaitAsync(() => root.IsLoaded && initialized);
            root.UpdateLayout();
            NativeRequire(checks, "renderedShell", root.ActualWidth > 0 && root.ActualHeight > 0 && NativeTree(root).OfType<Button>().Count() >= 10);
            category = "profile-vault";
            profiles = new ProfileStore();
            var profile = profiles.ReadProfile();
            if (stage == "seed")
            {
                NativeRequire(checks, "freshProfile", profile is null && !File.Exists(Path.Combine(profiles.DirectoryPath, "connection.json")) && !File.Exists(Path.Combine(profiles.DirectoryPath, "session.sqlite3")) && NativeVaultIsEmpty());
                profile = new ConnectionProfile(NativeAcceptance.ProfileId, "Native acceptance fixture", "https://music-native-acceptance.invalid", "fixture-user");
                profiles.Commit(profile, NativeAcceptance.Password);
            }
            NativeRequire(checks, "legacyProfile", profile is { Id: NativeAcceptance.ProfileId, Username: "fixture-user", ServerUrl: "https://music-native-acceptance.invalid" });
            string password = profiles.ReadPassword(profile!);
            NativeRequire(checks, "passwordVault", password == NativeAcceptance.Password);
            sessions = new SessionStore(Path.Combine(profiles.DirectoryPath, "session.sqlite3"));
            using var handler = new NativeAcceptance.FixtureHandler();
            using var http = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(5) };
            var target = new NavidromeClient(profile!, password, http);
            category = "fixture-library";
            await target.PingAsync();
            var library = await ReadLibraryAsync(target, CancellationToken.None);
            ActivateConnection(profile!, target, library.Albums, library.Playlists);
            timer.Stop(); // No periodic mutation while observing upgrade state.
            NativeRequire(checks, "libraryLoaded", albums.Count == 1 && playlists.Count == 1);
            if (stage == "restore")
            {
                category = "session-restore";
                var saved = sessions.LoadPlayback(profile!.Id);
                NativeRequire(checks, "persistedSession", saved is { Index: 2, Shuffle: true, Repeat: true } && Math.Abs(saved.PositionSeconds - NativeAcceptance.Position) < .1);
                RestorePlayback(); // Actual app restoration; never reconstruct expected queue on this path.
                NativeRequire(checks, "duplicateQueue", queue.Select(s => s.Id).SequenceEqual(new[] { "native-song-a", "native-song-b", "native-song-a" }) && queueIndex == 2);
                NativeRequire(checks, "pausedRestoration", player.PlaybackSession.PlaybackState != MediaPlaybackState.Playing && !player.AutoPlay);
                NativeRequire(checks, "positionRestoration", Math.Abs((restoredPosition > 0 ? restoredPosition : player.PlaybackSession.Position.TotalSeconds) - NativeAcceptance.Position) < .5);
                NativeRequire(checks, "transportRestoration", C<ToggleButton>("ShuffleButton").IsChecked == true && C<ToggleButton>("RepeatButton").IsChecked == true && C<ListView>("QueueList").Items.Count == 3 && C<ListView>("QueueList").SelectedIndex == 2);
            }
            category = "native-controls";
            await NavigateAsync("library");
            root.UpdateLayout();
            NativeRequire(checks, "populatedLibraryTree", NativeTree(C<StackPanel>("BrowsePanel")).OfType<ListViewBase>().Count(v => v.Items.Count > 0) == 3);
            await NativeAcceptance.WaitAsync(() => NativeHasRealizedFixtureText(C<StackPanel>("BrowsePanel")));
            NativeRequire(checks, "realizedLibraryTree", NativeHasRealizedFixtureText(C<StackPanel>("BrowsePanel")));
            foreach (var entry in new[] { ("AlbumsNav", "albums", "list"), ("PlaylistsNav", "playlists", "list"), ("SongsNav", "songs", "grid") })
            {
                NativeInvoke(entry.Item1);
                await NativeAcceptance.WaitAsync(() => page == entry.Item2 && !browsePending);
                if (stage == "seed") SetMode(entry.Item3);
                else NativeRequire(checks, "viewRestoration-" + entry.Item2, mode == entry.Item3);
                root.UpdateLayout();
                NativeRequire(checks, "populatedTree-" + entry.Item2, NativeTree(C<StackPanel>("BrowsePanel")).OfType<ListViewBase>().Any(v => v.Items.Count > 0));
                await NativeAcceptance.WaitAsync(() => NativeHasRealizedFixtureText(C<StackPanel>("BrowsePanel")));
                NativeRequire(checks, "realizedTree-" + entry.Item2, NativeHasRealizedFixtureText(C<StackPanel>("BrowsePanel")));
            }
            NativeRequire(checks, "viewRestoration", ReadMode("albums") == "list" && ReadMode("playlists") == "list" && ReadMode("songs") == "grid");
            C<TextBox>("SearchBox").Text = "Native fixture";
            await NativeAcceptance.WaitAsync(() => !browsePending && query == "Native fixture");
            NativeRequire(checks, "searchControl", songs.Count == 2);
            await OpenCollectionAsync(albums[0]);
            NativeRequire(checks, "albumDetail", detailSongs.Count == 2 && page == "detail");
            await OpenCollectionAsync(playlists[0]);
            NativeRequire(checks, "playlistDetail", detailSongs.Count == 2);
            if (stage == "seed")
            {
                queue = new List<Song> { detailSongs[0], detailSongs[1], detailSongs[0] };
                SelectTrack(2, false, NativeAcceptance.Position);
                C<ToggleButton>("ShuffleButton").IsChecked = true;
                C<ToggleButton>("RepeatButton").IsChecked = true;
            }
            NativeInvoke("QueueButton");
            await NativeAcceptance.WaitAsync(() => C<Border>("QueuePane").Visibility == Visibility.Visible);
            root.UpdateLayout();
            NativeRequire(checks, "populatedQueueTree", C<ListView>("QueueList").Items.Count == 3 && NativeTree(C<ListView>("QueueList")).OfType<TextBlock>().Any(t => t.Text == "Native fixture A"));
            NativeRequire(checks, "fixtureRequests", handler.Seen.Count == 6);
            category = "native-media";
            mediaStatus = await NativeMediaAsync(directory, stage);
            mediaWritten = true;
            // Preserve the exact paused upgrade fixture after the independent media probe.
            player.Pause();
            restoredPosition = NativeAcceptance.Position;
            category = "session-save";
            NativeRequire(checks, "sessionSaved", TrySavePlayback());
            var reread = sessions.LoadPlayback(profile!.Id);
            NativeRequire(checks, "duplicateQueue", reread is { Index: 2, Shuffle: true, Repeat: true } && reread.Queue.Select(s => s.Id).SequenceEqual(new[] { "native-song-a", "native-song-b", "native-song-a" }) && Math.Abs(reread.PositionSeconds - NativeAcceptance.Position) < .1);
            category = "native-media";
            if (mediaStatus == "failed") throw new InvalidOperationException();
            status = "passed";
            category = "none";
        }
        catch (TimeoutException) { category += "-timeout"; }
        catch (Exception) { /* Only fixed phase codes leave this process. */ }
        finally
        {
            try
            {
                if (stage == "restore")
                {
                    checks["vaultCleanup"] = NativeCleanupFixtureCredential();
                    if (!checks["vaultCleanup"] && status == "passed") { status = "failed"; category = "fixture-vault-cleanup"; }
                }
                if (!string.IsNullOrWhiteSpace(directory))
                {
                    if (!mediaWritten) NativeAcceptance.Write(directory, stage + "-media-result.json", stage, "skipped", "prerequisite-failed", new Dictionary<string, bool> { ["audibleOutputVerified"] = false });
                    NativeAcceptance.Write(directory, stage + "-result.json", stage, status, category, checks, mediaStatus);
                    await Task.Delay(5000); // Bounded screenshot window for installed-app orchestration.
                }
            }
            finally { Close(); }
        }
    }

    private async Task<string> NativeMediaAsync(string directory, string stage)
    {
        var checks = new Dictionary<string, bool> { ["originalProductionPlayer"] = true, ["mediaOpened"] = false, ["seekVerified"] = false, ["pauseVerified"] = false, ["positionAdvanced"] = false, ["audibleOutputVerified"] = false };
        string status = "failed", category = "media-open";
        bool failed = false;
        Windows.Foundation.TypedEventHandler<MediaPlayer, MediaPlayerFailedEventArgs> onFailed = (_, _) => Volatile.Write(ref failed, true);
        try
        {
            var devices = await DeviceInformation.FindAllAsync(MediaDevice.GetAudioRenderSelector()).AsTask().WaitAsync(TimeSpan.FromSeconds(10));
            checks["rendererAvailable"] = devices.Any(d => d.IsEnabled);
            // Missing renderer is a known prerequisite, not a catch-all excuse
            // for source, decoder, seek or pause failures later in this probe.
            if (!checks["rendererAvailable"])
            {
                status = "skipped";
                category = "no-audio-renderer";
                return "renderer-unavailable";
            }
            var expectedSource = player.Source ?? throw new InvalidOperationException();
            player.MediaFailed += onFailed;
            // SelectTrack/RestorePlayback already opened the supplied WAV via
            // the production MediaPlayer. Observe that one source throughout;
            // never let an earlier source event attest to a replacement opening.
            await NativeAcceptance.WaitAsync(() => Volatile.Read(ref failed) ||
                (ReferenceEquals(player.Source, expectedSource) && player.PlaybackSession.CanSeek && player.PlaybackSession.NaturalDuration.TotalSeconds >= 12), 15000);
            if (Volatile.Read(ref failed) || !ReferenceEquals(player.Source, expectedSource)) throw new InvalidOperationException();
            checks["mediaOpened"] = true;
            checks["sourceStable"] = true;
            if (stage == "restore")
            {
                category = "restored-native-position";
                await NativeAcceptance.WaitAsync(() => Volatile.Read(ref failed) || (player.PlaybackSession.CanSeek && player.PlaybackSession.NaturalDuration.TotalSeconds > 0 && restoredPosition == 0), 15000);
                checks["restoredNativeSeek"] = !Volatile.Read(ref failed) && Math.Abs(player.PlaybackSession.Position.TotalSeconds - NativeAcceptance.Position) < .5;
                checks["restoredNativePaused"] = player.PlaybackSession.PlaybackState == MediaPlaybackState.Paused;
                if (!checks["restoredNativeSeek"] || !checks["restoredNativePaused"]) throw new InvalidOperationException();
            }
            category = "media-seek";
            if (!player.PlaybackSession.CanSeek || player.PlaybackSession.NaturalDuration.TotalSeconds < 12) throw new InvalidOperationException();
            player.PlaybackSession.Position = TimeSpan.FromSeconds(NativeAcceptance.Position);
            await NativeAcceptance.WaitAsync(() => Math.Abs(player.PlaybackSession.Position.TotalSeconds - NativeAcceptance.Position) < .5);
            checks["seekVerified"] = true;
            player.Pause();
            await NativeAcceptance.WaitAsync(() => player.PlaybackSession.PlaybackState == MediaPlaybackState.Paused);
            double initialPause = player.PlaybackSession.Position.TotalSeconds;
            await Task.Delay(400);
            checks["pauseVerified"] = Math.Abs(player.PlaybackSession.Position.TotalSeconds - initialPause) < .15;
            if (!checks["pauseVerified"]) throw new InvalidOperationException();
            category = "media-advancement";
            double start = player.PlaybackSession.Position.TotalSeconds;
            player.Play();
            await NativeAcceptance.WaitAsync(() => Volatile.Read(ref failed) || (player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing && player.PlaybackSession.Position.TotalSeconds > start + .25));
            if (Volatile.Read(ref failed)) throw new InvalidOperationException();
            checks["positionAdvanced"] = true;
            category = "media-pause";
            player.Pause();
            await NativeAcceptance.WaitAsync(() => player.PlaybackSession.PlaybackState == MediaPlaybackState.Paused);
            double paused = player.PlaybackSession.Position.TotalSeconds;
            await Task.Delay(400);
            checks["pauseVerified"] = Math.Abs(player.PlaybackSession.Position.TotalSeconds - paused) < .15;
            if (!checks["pauseVerified"] || !ReferenceEquals(player.Source, expectedSource)) throw new InvalidOperationException();
            status = "verified";
            category = "transport-only-not-audibility";
        }
        catch (Exception) { /* Independent source/seek/pause failures remain failed. */ }
        finally
        {
            player.MediaFailed -= onFailed;
            player.Pause();
            NativeAcceptance.Write(directory, stage + "-media-result.json", stage, status, category, checks);
        }
        return status switch { "verified" => "passed", "skipped" => "renderer-unavailable", _ => "failed" };
    }
}
#endif
