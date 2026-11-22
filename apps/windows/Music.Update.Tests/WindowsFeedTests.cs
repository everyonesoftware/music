using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Music.App;
using Music.Core;
using Velopack;
using Velopack.Locators;
using Velopack.Logging;
using Velopack.Sources;
using Xunit;

namespace Music.Update.Tests;

public sealed class WindowsFeedTests
{
    [Theory]
    [InlineData(429, "delta", 120)]
    [InlineData(403, "date", 120)]
    [InlineData(403, "reset", 180)]
    [InlineData(403, "both", 180)]
    [InlineData(403, "bad-reset", 60)]
    [InlineData(429, "malformed", 60)]
    [InlineData(429, "excessive", 3600)]
    [InlineData(403, "ordinary", 0)]
    [InlineData(503, "delta", 0)]
    public async Task Github_throttling_defers_only_startup_across_launches(int status, string headers, int seconds)
    {
        var directory = Directory.CreateTempSubdirectory("music-windows-cooldown-");
        try
        {
            string marker = Path.Combine(directory.FullName, "cooldown");
            var clock = new TestClock();
            var inventory = new Inventory { NextResponse = new((HttpStatusCode)status) };
            var response = inventory.NextResponse;
            if (headers is "delta" or "both") response.Headers.RetryAfter = new RetryConditionHeaderValue(TimeSpan.FromSeconds(120));
            if (headers == "date") response.Headers.RetryAfter = new RetryConditionHeaderValue(clock.Now.AddSeconds(120));
            if (headers is "reset" or "both" or "ordinary" or "bad-reset")
            {
                response.Headers.Add("X-RateLimit-Remaining", headers == "ordinary" ? "4" : "0");
                response.Headers.Add("X-RateLimit-Reset", headers == "bad-reset" ? "9223372036854775807" :
                    clock.Now.AddSeconds(180).ToUnixTimeSeconds().ToString(System.Globalization.CultureInfo.InvariantCulture));
            }
            if (headers == "malformed") response.Headers.TryAddWithoutValidation("Retry-After", "invalid");
            if (headers == "excessive") response.Headers.RetryAfter = new RetryConditionHeaderValue(TimeSpan.FromDays(2));

            using var http = new HttpClient(inventory);
            var downloader = new Downloader { MainRelease = true };
            WindowsUpdateController Launch() => new(new WindowsUpdateBackend(
                new WindowsGithubSource(downloader, http, marker, clock),
                new TestVelopackLocator("MusicWindows", "0.1.19", directory.FullName, logger: NullVelopackLogger.Instance)),
                () => throw new InvalidOperationException("Startup must never apply an update."));

            using (var first = Launch())
            {
                await first.CheckOnStartupAsync();
                Assert.Equal(WindowsUpdatePhase.Error, first.Status.Phase);
            }
            Assert.Single(inventory.Requests);
            Assert.Empty(downloader.Requests);

            if (seconds > 0)
            {
                clock.Now = clock.Now.AddSeconds(seconds - 1);
                using var next = Launch();
                await next.CheckOnStartupAsync();
                Assert.Single(inventory.Requests);
                Assert.Empty(downloader.Requests);
                Assert.Null(next.Status.Version);
                Assert.Equal(WindowsUpdatePhase.StartupDeferred, next.Status.Phase);
                await next.CheckAsync();
                Assert.Equal(WindowsUpdatePhase.Available, next.Status.Phase);
                Assert.Equal(2, inventory.Requests.Count);
                clock.Now = clock.Now.AddSeconds(1);
            }

            using var after = Launch();
            await after.CheckOnStartupAsync();
            Assert.Equal(WindowsUpdatePhase.Available, after.Status.Phase);
            Assert.Equal(seconds > 0 ? 3 : 2, inventory.Requests.Count);
            Assert.All(downloader.Requests, request => Assert.EndsWith("/releases.win-x64.json", request));
        }
        finally { directory.Delete(recursive: true); }
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task Unusable_cooldown_storage_does_not_block_future_checks(bool corruptMarker)
    {
        var directory = Directory.CreateTempSubdirectory("music-windows-cooldown-");
        try
        {
            string file = Path.Combine(directory.FullName, "invalid");
            File.WriteAllText(file, "not a deadline");
            string marker = corruptMarker ? file : Path.Combine(file, "cooldown");
            var inventory = new Inventory { NextResponse = new(HttpStatusCode.TooManyRequests) };
            using var http = new HttpClient(inventory);
            using var first = Launch();
            await first.CheckOnStartupAsync();
            Assert.Equal(WindowsUpdatePhase.Error, first.Status.Phase);
            Assert.Single(inventory.Requests);
            if (corruptMarker) File.WriteAllText(marker, "corrupt after throttling");
            using var next = Launch();
            await next.CheckOnStartupAsync();
            Assert.Equal(WindowsUpdatePhase.Available, next.Status.Phase);
            Assert.Equal(2, inventory.Requests.Count);

            WindowsUpdateController Launch() => new(new WindowsUpdateBackend(
                new WindowsGithubSource(new Downloader { MainRelease = true }, http, marker, new TestClock()),
                new TestVelopackLocator("MusicWindows", "0.1.19", directory.FullName, logger: NullVelopackLogger.Instance)),
                () => Task.CompletedTask);
        }
        finally { directory.Delete(recursive: true); }
    }

    private sealed class TestClock : TimeProvider
    {
        public DateTimeOffset Now = new(2025, 1, 1, 0, 0, 0, TimeSpan.Zero);
        public override DateTimeOffset GetUtcNow() => Now;
    }

    private sealed class Inventory : HttpMessageHandler
    {
        public HttpResponseMessage? NextResponse;
        public readonly List<Uri> Requests = new();
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            Requests.Add(request.RequestUri!);
            Assert.Equal("https://api.github.com/repos/everyonesoftware/music/releases?per_page=100&page=1", request.RequestUri!.AbsoluteUri);
            Assert.Contains(request.Headers.UserAgent, value => value.Product?.Name == "MusicWindows-Updates");
            Assert.Null(request.Headers.Authorization);
            return Task.FromResult(Interlocked.Exchange(ref NextResponse, null) ?? new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(JsonSerializer.Serialize(new[] { Release("v0.1.20") }))
            });
        }
    }

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
