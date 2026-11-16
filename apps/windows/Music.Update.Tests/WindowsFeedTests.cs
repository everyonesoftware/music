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
    public async Task Stable_main_release_with_windows_feed_is_an_in_app_update()
    {
        var downloader = new Downloader { MainRelease = true };
        var source = new WindowsGithubSource(downloader);
        var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        var asset = Assert.Single(feed.Assets);
        Assert.Equal("0.1.20", asset.Version.ToString());
        await source.DownloadReleaseEntry(NullVelopackLogger.Instance, asset, "unused", _ => {}, CancellationToken.None);
        Assert.EndsWith("/v0.1.20/MusicWindows-0.1.20-win-x64-full.nupkg", downloader.Requests.Last());
        Assert.DoesNotContain(downloader.Requests, u => u.Contains("/latest"));
    }

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

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Stable_main_feed_wins_equal_version_tie_regardless_of_inventory_order(bool mainFirst)
    {
        object main = Release("v0.1.20");
        object legacy = Release("windows-v0.1.20", prerelease: true);
        var downloader = new Downloader { Pages = new() { [1] = mainFirst ? [main, legacy] : [legacy, main] } };
        var source = new WindowsGithubSource(downloader);
        var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        await source.DownloadReleaseEntry(NullVelopackLogger.Instance, Assert.Single(feed.Assets), "unused", _ => {}, CancellationToken.None);
        Assert.EndsWith("/v0.1.20/MusicWindows-0.1.20-win-x64-full.nupkg", downloader.Requests.Last());
        Assert.DoesNotContain(downloader.Requests, u => u.Contains("/windows-v0.1.20/"));
    }

    [Theory]
    [InlineData("releases.win-x64.json")]
    [InlineData("RELEASES.WIN-X64.JSON")]
    [InlineData("MusicWindows-0.1.20-win-x64-full.nupkg")]
    [InlineData("MUSICWINDOWS-0.1.20-WIN-X64-FULL.NUPKG")]
    public async Task Ambiguous_selected_windows_asset_is_rejected_without_falling_back(string duplicate)
    {
        var downloader = new Downloader { Pages = new() { [1] = [
            Release("v0.1.20", assetNames: ["releases.win-x64.json", "MusicWindows-0.1.20-win-x64-full.nupkg", duplicate]),
            Release("windows-v0.1.19", prerelease: true)
        ] } };
        await Assert.ThrowsAsync<InvalidDataException>(() => new WindowsGithubSource(downloader)
            .GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.DoesNotContain(downloader.Requests, u => u.Contains("/windows-v0.1.19/"));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Highest_numeric_windows_version_wins_across_out_of_order_mixed_pages(bool mainTarget)
    {
        string target = mainTarget ? "v0.1.20" : "windows-v0.1.20";
        var firstPage = Enumerable.Repeat(Release("v0.1.99", assetNames: ["music.apk"]), 99)
            .Prepend(Release("windows-v0.1.9", prerelease: true)).ToArray();
        var downloader = new Downloader { Pages = new() {
            [1] = firstPage,
            [2] = [Release("v0.1.19"), Release(target, prerelease: !mainTarget),
                Release("v0.1.98", assetNames: ["MusicWindows-win-x64-Setup.exe"]),
                Release("v0.1.97", draft: true), Release("v0.1.96", prerelease: true)]
        } };
        var feed = await new WindowsGithubSource(downloader).GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        Assert.Equal("0.1.20", Assert.Single(feed.Assets).Version.ToString());
        Assert.Equal(3, downloader.Requests.Count);
        Assert.EndsWith("page=2", downloader.Requests[1]);
        Assert.EndsWith($"/{target}/releases.win-x64.json", downloader.Requests[2]);
    }

    [Fact]
    public async Task Android_or_installer_only_releases_are_not_in_app_updates()
    {
        var downloader = new Downloader { Pages = new() { [1] = [
            Release("v0.1.30", assetNames: ["music.apk"]),
            Release("v0.1.31", assetNames: ["MusicWindows-win-x64-Setup.exe"])
        ] } };
        var feed = await new WindowsGithubSource(downloader).GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        Assert.Empty(feed.Assets);
        Assert.Single(downloader.Requests);
    }

    [Fact]
    public async Task Full_tenth_inventory_page_is_an_error_even_if_a_windows_feed_was_found()
    {
        object[] page = Enumerable.Repeat(Release("v0.1.21", assetNames: ["music.apk"]), 99)
            .Prepend(Release("v0.1.20")).ToArray();
        var downloader = new Downloader { Pages = Enumerable.Range(1, 10).ToDictionary(n => n, _ => page) };
        var error = await Assert.ThrowsAsync<InvalidDataException>(() => new WindowsGithubSource(downloader)
            .GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.Contains("paging limit", error.Message);
        Assert.Equal(10, downloader.Requests.Count);
        Assert.EndsWith("page=10", downloader.Requests.Last());
    }

    [Theory]
    [InlineData("empty-feed")]
    [InlineData("wrong-version")]
    [InlineData("wrong-package")]
    [InlineData("wrong-architecture")]
    [InlineData("malformed-feed")]
    [InlineData("duplicate-entry")]
    [InlineData("missing-package")]
    [InlineData("wrong-origin")]
    public async Task Invalid_highest_main_feed_never_falls_back_to_older_legacy_feed(string fault)
    {
        var downloader = new Downloader { MainRelease = true, OlderWindowsRelease = true, Fault = fault };
        var source = new WindowsGithubSource(downloader);
        if (fault == "malformed-feed")
            await Assert.ThrowsAsync<JsonException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        else
            await Assert.ThrowsAsync<InvalidDataException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.DoesNotContain(downloader.Requests, u => u.Contains("/windows-v0.1.19/"));
    }

    private static object Release(string tag, bool prerelease = false, bool draft = false, string[]? assetNames = null)
    {
        string version = tag.StartsWith("windows-v", StringComparison.Ordinal) ? tag[9..] : tag[1..];
        return new {
            tag_name = tag, prerelease, draft,
            assets = (assetNames ?? ["releases.win-x64.json", $"MusicWindows-{version}-win-x64-full.nupkg"])
                .Select(name => new { name, browser_download_url = $"{WindowsGithubSource.Repository}/releases/download/{tag}/{name}" }).ToArray()
        };
    }

    private sealed class Downloader : IFileDownloader
    {
        public bool FullFirstPage, OlderWindowsRelease, MainRelease;
        public string? Fault;
        public Dictionary<int, object[]>? Pages;
        public readonly List<string> Requests = new();
        private const string Root = "https://github.com/everyonesoftware/music/releases/download/";
        public Task<string> DownloadString(string url, IDictionary<string, string>? headers = null, double timeout = 30)
        {
            Requests.Add(url);
            if (Pages is not null)
                return Task.FromResult(JsonSerializer.Serialize(Pages.GetValueOrDefault(int.Parse(url.Split("&page=")[1]), [])));
            if (FullFirstPage && url.EndsWith("page=1"))
                return Task.FromResult(JsonSerializer.Serialize(Enumerable.Repeat(new { tag_name = "v0.1.50", draft = false, prerelease = false }, 100)));
            string package = Fault == "missing-package" ? "missing.nupkg" : "MusicWindows-0.1.20-win-x64-full.nupkg";
            string tag = MainRelease ? "v0.1.20" : "windows-v0.1.20";
            string feedUrl = Fault == "wrong-origin" ? "https://example.com/releases.win-x64.json" : Root + tag + "/releases.win-x64.json";
            var releases = new List<object> {
                new { tag_name = "v0.1.21", draft = false, prerelease = false, assets = new[] { new { name = "music.apk", browser_download_url = Root + "v0.1.21/music.apk" } } },
                new { tag_name = tag, draft = false, prerelease = !MainRelease, assets = new[] { new { name = "releases.win-x64.json", browser_download_url = feedUrl }, new { name = package, browser_download_url = Root + tag + "/" + package } } }
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
            if (Fault == "malformed-feed") feed = "not JSON";
            if (Fault == "duplicate-entry")
            {
                using var document = JsonDocument.Parse(feed);
                string entry = document.RootElement.GetProperty("Assets")[0].GetRawText();
                feed = "{\"Assets\":[" + entry + "," + entry + "]}";
            }
            return Task.FromResult(Encoding.UTF8.GetBytes(feed));
        }
        public Task DownloadFile(string url, string targetFile, Action<int>? progress = null, IDictionary<string, string>? headers = null, double timeout = 30, CancellationToken cancelToken = default)
        { Requests.Add(url); return Task.CompletedTask; }
    }
}
