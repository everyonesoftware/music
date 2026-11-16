using System.Text;
using System.Text.Json;
using Music.App;
using Music.Core;
using Velopack;
using Velopack.Logging;
using Velopack.Sources;

if (JsonSerializer.IsReflectionEnabledByDefault)
    throw new InvalidOperationException("This executable must run with JSON reflection disabled.");
if (System.Runtime.CompilerServices.RuntimeFeature.IsDynamicCodeSupported)
    throw new InvalidOperationException("This executable must match the app's dynamic-code-disabled trimming mode.");

const string profileJson = """
    {"Id":"home","Name":"Home","ServerUrl":"https://music.invalid","Username":"listener"}
    """;
var profile = JsonSerializer.Deserialize(profileJson, PersistenceJsonContext.Default.ConnectionProfile)
    ?? throw new InvalidOperationException("Legacy profile was not read.");
Require(profile.Id == "home" && profile.Username == "listener", "profile compatibility");
var savedProfile = JsonSerializer.Serialize(profile, PersistenceJsonContext.Default.ConnectionProfile);
Require(JsonSerializer.Deserialize(savedProfile, PersistenceJsonContext.Default.ConnectionProfile) == profile, "profile roundtrip");

string directory = Path.Combine(Path.GetTempPath(), "music-trim-check-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(directory);
try
{
    string database = Path.Combine(directory, "session.db");
    var song = new Song("track", "Title", "Artist", "Album", null, 120, 4);
    var queue = Enumerable.Repeat(song, 602).ToArray();
    using (var store = new SessionStore(database))
    {
        store.SavePlayback(new PlaybackSnapshot("home", queue, 601, 42.25, true, false));
        store.SaveView("albums", "list");
        try
        {
            store.SavePlayback(new PlaybackSnapshot("home", [song with { Id = "https://music.invalid/stream?token=forbidden" }], 0, 0, false, false));
            throw new InvalidOperationException("Signed media was accepted.");
        }
        catch (ArgumentException) { }
    }
    using (var store = new SessionStore(database))
    {
        var restored = store.LoadPlayback("home") ?? throw new InvalidOperationException("Session was not restored.");
        Require(restored.Queue.Count == 602 && restored.Queue[601] == song && restored.Index == 601 &&
            restored.PositionSeconds == 42.25 && restored.Shuffle && !restored.Repeat, "queue reopen");
        Require(store.LoadPlayback("other") is null && store.GetView("albums", "grid") == "list", "session isolation and views");
    }
    Require(!Encoding.UTF8.GetString(File.ReadAllBytes(database)).Contains("forbidden", StringComparison.Ordinal), "no signed-media persistence");
}
finally { Directory.Delete(directory, true); }

foreach (bool mainRelease in new[] { true, false })
{
    var downloader = new FixtureDownloader { MainRelease = mainRelease };
    var source = new WindowsGithubSource(downloader);
    var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
    Require(feed.Assets.Length == 1 && feed.Assets[0].Version.ToString() == "0.1.32", "Windows update feed");
    await source.DownloadReleaseEntry(NullVelopackLogger.Instance, feed.Assets[0], "unused", _ => { }, CancellationToken.None);
    string tag = mainRelease ? "v0.1.32" : "windows-v0.1.32";
    Require(downloader.LastDownload == $"https://github.com/everyonesoftware/music/releases/download/{tag}/MusicWindows-0.1.32-win-x64-full.nupkg", "package origin");
}
try
{
    await new WindowsGithubSource(new FixtureDownloader { WrongOrigin = true })
        .GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
    throw new InvalidOperationException("Cross-repository update was accepted.");
}
catch (InvalidDataException) { }
Console.WriteLine("PASS: reflection-disabled profile compatibility, SQLite queue reopen, signed-media rejection, views and real Velopack Windows-feed parsing (explicit HTTP fixtures; no native UI/upgrade acceptance)");

static void Require(bool condition, string check)
{
    if (!condition) throw new InvalidOperationException("Failed: " + check);
}

sealed class FixtureDownloader : IFileDownloader
{
    public bool MainRelease { get; init; } = true;
    public bool WrongOrigin { get; init; }
    public string? LastDownload { get; private set; }
    public Task<string> DownloadString(string url, IDictionary<string, string>? headers = null, double timeout = 30)
    {
        if (url != "https://api.github.com/repos/everyonesoftware/music/releases?per_page=100&page=1")
            throw new InvalidOperationException("Unexpected fixture request.");
        string json = """
            [{"tag_name":"windows-v0.1.32","draft":false,"prerelease":true,"assets":[
            {"name":"releases.win-x64.json","browser_download_url":"https://github.com/everyonesoftware/music/releases/download/windows-v0.1.32/releases.win-x64.json"},
            {"name":"MusicWindows-0.1.32-win-x64-full.nupkg","browser_download_url":"https://github.com/everyonesoftware/music/releases/download/windows-v0.1.32/MusicWindows-0.1.32-win-x64-full.nupkg"}]}]
            """;
        if (MainRelease) json = json.Replace("windows-v0.1.32", "v0.1.32", StringComparison.Ordinal)
            .Replace("\"prerelease\":true", "\"prerelease\":false", StringComparison.Ordinal);
        return Task.FromResult(WrongOrigin ? json.Replace("https://github.com/everyonesoftware/music/releases/download/", "https://other.invalid/", StringComparison.Ordinal) : json);
    }
    public Task<byte[]> DownloadBytes(string url, IDictionary<string, string>? headers = null, double timeout = 30)
    {
        string tag = MainRelease ? "v0.1.32" : "windows-v0.1.32";
        if (url != $"https://github.com/everyonesoftware/music/releases/download/{tag}/releases.win-x64.json")
            throw new InvalidOperationException("Unexpected fixture feed.");
        return Task.FromResult(Encoding.UTF8.GetBytes("""
            {"Assets":[{"PackageId":"MusicWindows","Version":"0.1.32","Type":"Full","FileName":"MusicWindows-0.1.32-win-x64-full.nupkg","Size":100,"SHA256":"ABCD"}]}
            """));
    }
    public Task DownloadFile(string url, string targetFile, Action<int>? progress = null, IDictionary<string, string>? headers = null, double timeout = 30, CancellationToken cancelToken = default)
    {
        LastDownload = url;
        return Task.CompletedTask;
    }
}
