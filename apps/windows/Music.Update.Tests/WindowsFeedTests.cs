using System.Text;
using System.Text.Json;
using Music.App;
using Velopack;
using Velopack.Logging;
using Velopack.Sources;
using Xunit;

namespace Music.Update.Tests;

public sealed class WindowsFeedTests
{
    [Fact]
    public async Task Windows_prerelease_feed_is_read_instead_of_android_latest()
    {
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader);
        var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        var asset = Assert.Single(feed.Assets);
        Assert.Equal("MusicWindows", asset.PackageId);
        Assert.Equal("MusicWindows-0.1.20-win-x64-full.nupkg", asset.FileName);
        Assert.Contains(downloader.Requests, u => u.Contains("releases?per_page=100&page=1"));
        Assert.DoesNotContain(downloader.Requests, u => u.Contains("/latest"));
        await source.DownloadReleaseEntry(NullVelopackLogger.Instance, asset, "unused", _ => {}, CancellationToken.None);
        Assert.EndsWith("/windows-v0.1.20/MusicWindows-0.1.20-win-x64-full.nupkg", downloader.Requests.Last());
    }

    [Fact]
    public async Task Windows_feed_is_found_beyond_first_hundred_mixed_releases()
    {
        var downloader = new Downloader { FullFirstPage = true };
        var source = new WindowsGithubSource(downloader);
        var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        Assert.Single(feed.Assets);
        Assert.Contains(downloader.Requests, u => u.EndsWith("page=2"));
    }

    [Theory]
    [InlineData("win-arm64", "MusicWindows")]
    [InlineData("win-x64", "Android")]
    public async Task Wrong_channel_or_app_is_rejected_before_network(string channel, string appId)
    {
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader);
        await Assert.ThrowsAsync<InvalidDataException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, appId, channel));
        Assert.Empty(downloader.Requests);
    }

    [Theory]
    [InlineData("wrong-origin")]
    [InlineData("wrong-version")]
    [InlineData("wrong-architecture")]
    [InlineData("missing-package")]
    [InlineData("empty-feed")]
    [InlineData("wrong-package")]
    public async Task Mismatched_feed_is_rejected_instead_of_offering_a_package(string fault)
    {
        var source = new WindowsGithubSource(new Downloader { Fault = fault });
        await Assert.ThrowsAsync<InvalidDataException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
    }

    [Fact]
    public async Task Only_the_highest_numeric_windows_feed_is_downloaded()
    {
        var downloader = new Downloader { OlderWindowsRelease = true };
        var source = new WindowsGithubSource(downloader);
        var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        Assert.Single(feed.Assets);
        Assert.DoesNotContain(downloader.Requests, u => u.Contains("/windows-v0.1.19/"));
    }

    private sealed class Downloader : IFileDownloader
    {
        public bool FullFirstPage, OlderWindowsRelease;
        public string? Fault;
        public readonly List<string> Requests = new();
        private const string Root = "https://github.com/everyonesoftware/music/releases/download/";
        public Task<string> DownloadString(string url, IDictionary<string, string>? headers = null, double timeout = 30)
        {
            Requests.Add(url);
            if (FullFirstPage && url.EndsWith("page=1"))
                return Task.FromResult(JsonSerializer.Serialize(Enumerable.Repeat(new { tag_name = "v0.1.50", draft = false, prerelease = false }, 100)));
            string package = Fault == "missing-package" ? "missing.nupkg" : "MusicWindows-0.1.20-win-x64-full.nupkg";
            string feedUrl = Fault == "wrong-origin" ? "https://example.com/releases.win-x64.json" : Root + "windows-v0.1.20/releases.win-x64.json";
            var releases = new List<object> {
                new { tag_name = "v0.1.21", draft = false, prerelease = false, assets = new[] { new { name = "releases.win-x64.json", browser_download_url = Root + "v0.1.21/releases.win-x64.json" } } },
                new { tag_name = "windows-v0.1.20", draft = false, prerelease = true, assets = new[] { new { name = "releases.win-x64.json", browser_download_url = feedUrl }, new { name = package, browser_download_url = Root + "windows-v0.1.20/" + package } } }
            };
            if (OlderWindowsRelease) releases.Add(new { tag_name = "windows-v0.1.19", draft = false, prerelease = true, assets = new[] { new { name = "releases.win-x64.json", browser_download_url = Root + "windows-v0.1.19/releases.win-x64.json" } } });
            return Task.FromResult(JsonSerializer.Serialize(releases));
        }
        public Task<byte[]> DownloadBytes(string url, IDictionary<string, string>? headers = null, double timeout = 30)
        {
            Requests.Add(url);
            string feed = """
                {"Assets":[{"PackageId":"MusicWindows","Version":"0.1.20","Type":"Full","FileName":"MusicWindows-0.1.20-win-x64-full.nupkg","Size":100,"SHA256":"ABCD"},{"PackageId":"Android","Version":"0.1.99","Type":"Full","FileName":"music.apk","Size":1}]}
                """;
            if (Fault == "wrong-version") feed = feed.Replace("0.1.20", "0.1.21", StringComparison.Ordinal);
            if (Fault == "wrong-architecture") feed = feed.Replace("win-x64", "win-arm64", StringComparison.Ordinal);
            if (Fault == "empty-feed") feed = "{\"Assets\":[]}";
            if (Fault == "wrong-package") feed = feed.Replace("MusicWindows", "UnrelatedApp", StringComparison.Ordinal);
            return Task.FromResult(Encoding.UTF8.GetBytes(feed));
        }
        public Task DownloadFile(string url, string targetFile, Action<int>? progress = null, IDictionary<string, string>? headers = null, double timeout = 30, CancellationToken cancelToken = default)
        { Requests.Add(url); return Task.CompletedTask; }
    }
}
