using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
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
    private const string IndexUrl = "https://github.com/everyonesoftware/music/releases/latest/download/latest-versions.json";
    private const string TagUrl = "https://api.github.com/repos/everyonesoftware/music/releases/tags/v0.1.20";
    private const string ValidIndex = """{"schema":1,"android":"v0.1.99","windows":"v0.1.20"}""";

    [Fact]
    public async Task Discovery_reads_index_then_only_the_selected_windows_tag_and_feed()
    {
        var handler = new Discovery();
        using var http = new HttpClient(handler);
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader, http, null, new TestClock());

        var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        var asset = Assert.Single(feed.Assets);
        Assert.Equal("0.1.20", asset.Version.ToString());
        Assert.Equal([IndexUrl, TagUrl], handler.Requests.Select(u => u.AbsoluteUri));
        Assert.Equal(Root + FeedName, Assert.Single(downloader.Requests));
        await source.DownloadReleaseEntry(NullVelopackLogger.Instance, asset, "unused", _ => {}, CancellationToken.None);
        Assert.Equal([Root + FeedName, Root + PackageName], downloader.Requests);
    }

    private const string TaggedIndexPath = "/everyonesoftware/music/releases/download/v0.1.99/latest-versions.json";
    private const string TaggedIndexUrl = "https://github.com" + TaggedIndexPath;
    private const string IndexCdnUrl = "https://release-assets.githubusercontent.com/index-fixture?sig=example";
    private const string LegacyCdnUrl = "https://objects.githubusercontent.com/index-fixture?sig=example";

    [Fact]
    public async Task Discovery_follows_the_github_latest_index_redirect_chain()
    {
        var handler = new Discovery();
        handler.Redirects.Add(IndexUrl, Redirect(TaggedIndexPath));
        handler.Redirects.Add(TaggedIndexUrl, Redirect(IndexCdnUrl));
        handler.Redirects.Add(IndexCdnUrl, Redirect(LegacyCdnUrl, HttpStatusCode.TemporaryRedirect));
        using var http = new HttpClient(handler);
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader, http, null, new TestClock());

        var offer = Assert.Single((await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64")).Assets);
        Assert.Equal("0.1.20", offer.Version.ToString());
        Assert.Equal([IndexUrl, TaggedIndexUrl, IndexCdnUrl, LegacyCdnUrl, TagUrl], handler.Requests.Select(u => u.AbsoluteUri));
        Assert.Equal(Root + FeedName, Assert.Single(downloader.Requests));
    }

    [Theory]
    [InlineData(0, null)]
    [InlineData(0, "https://outside.invalid/index.json")]
    [InlineData(0, IndexCdnUrl)]
    [InlineData(0, "/someone-else/music/releases/download/v0.1.99/latest-versions.json")]
    [InlineData(0, "/everyonesoftware/music/releases/download/v0.1.099/latest-versions.json")]
    [InlineData(0, "/everyonesoftware/music/releases/download/v0.1.99-preview/latest-versions.json")]
    [InlineData(0, "/everyonesoftware/music/releases/download/v0.1.99/windows-release.json")]
    [InlineData(0, TaggedIndexPath + "?private=fixture")]
    [InlineData(1, "http://release-assets.githubusercontent.com/index-fixture")]
    [InlineData(1, "https://release-assets.githubusercontent.com:444/index-fixture")]
    [InlineData(1, "https://fixture@release-assets.githubusercontent.com/index-fixture")]
    [InlineData(1, IndexCdnUrl + "#fragment")]
    [InlineData(1, "https://release-assets.githubusercontent.com.outside.invalid/index-fixture")]
    [InlineData(2, "https://outside.invalid/payload")]
    [InlineData(2, TaggedIndexUrl)]
    public async Task Discovery_rejects_an_unapproved_index_redirect_before_requesting_it(int hop, string? location)
    {
        var handler = new Discovery();
        string[] chain = [IndexUrl, TaggedIndexUrl, IndexCdnUrl];
        for (int i = 0; i < hop; i++) handler.Redirects.Add(chain[i], Redirect(chain[i + 1]));
        handler.Redirects.Add(chain[hop], Redirect(location));
        using var http = new HttpClient(handler);
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader, http, null, new TestClock());

        await Assert.ThrowsAsync<InvalidDataException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.Equal(chain.Take(hop + 1), handler.Requests.Select(u => u.AbsoluteUri));
        Assert.Empty(downloader.Requests);
    }

    [Fact]
    public async Task Discovery_bounds_the_number_of_index_redirects()
    {
        var handler = new Discovery();
        string[] chain = [IndexUrl, TaggedIndexUrl, .. Enumerable.Range(0, 5).Select(i => IndexCdnUrl + i)];
        for (int i = 1; i < chain.Length; i++) handler.Redirects.Add(chain[i - 1], Redirect(chain[i]));
        using var http = new HttpClient(handler);
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader, http, null, new TestClock());

        await Assert.ThrowsAsync<InvalidDataException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.Equal(chain.Take(6), handler.Requests.Select(u => u.AbsoluteUri));
        Assert.Empty(downloader.Requests);
    }

    [Theory]
    [InlineData(TagUrl)]
    [InlineData("/repos/everyonesoftware/music/releases/tags/v0.1.99")]
    [InlineData(IndexCdnUrl)]
    public async Task Discovery_never_follows_a_tag_api_redirect(string location)
    {
        var handler = new Discovery { NextResponse = Redirect(location) };
        using var http = new HttpClient(handler);
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader, http, null, new TestClock());

        await Assert.ThrowsAsync<InvalidDataException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.Equal([IndexUrl, TagUrl], handler.Requests.Select(u => u.AbsoluteUri));
        Assert.Empty(downloader.Requests);
    }

    private static HttpResponseMessage Redirect(string? location, HttpStatusCode status = HttpStatusCode.Found)
    {
        var response = new HttpResponseMessage(status);
        if (location is not null) response.Headers.Location = new Uri(location, UriKind.RelativeOrAbsolute);
        return response;
    }

    [Theory]
    [InlineData("0.1.20")]
    [InlineData("0.1.21")]
    public async Task Indexed_release_is_not_offered_when_the_installed_version_is_equal_or_newer(string installed)
    {
        var directory = Directory.CreateTempSubdirectory("music-windows-index-");
        try
        {
            var downloader = new Downloader();
            var backend = new WindowsUpdateBackend(new WindowsGithubSource(downloader),
                new TestVelopackLocator("MusicWindows", installed, directory.FullName, logger: NullVelopackLogger.Instance));
            Assert.Null(await backend.CheckAsync());
            Assert.Equal([IndexUrl, TagUrl, Root + FeedName], downloader.Requests);
        }
        finally { directory.Delete(recursive: true); }
    }

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
            var inventory = new Discovery { NextResponse = new((HttpStatusCode)status) };
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
            var downloader = new Downloader();
            WindowsUpdateController Launch() => new(new WindowsUpdateBackend(
                new WindowsGithubSource(downloader, http, marker, clock),
                new TestVelopackLocator("MusicWindows", "0.1.19", directory.FullName, logger: NullVelopackLogger.Instance)),
                () => throw new InvalidOperationException("Startup must never apply an update."));

            using (var first = Launch())
            {
                await first.CheckOnStartupAsync();
                Assert.Equal(WindowsUpdatePhase.Error, first.Status.Phase);
            }
            Assert.Equal([IndexUrl, TagUrl], inventory.Requests.Select(url => url.AbsoluteUri));
            Assert.Empty(downloader.Requests);

            if (seconds > 0)
            {
                clock.Now = clock.Now.AddSeconds(seconds - 1);
                using var next = Launch();
                await next.CheckOnStartupAsync();
                Assert.Equal([IndexUrl, TagUrl], inventory.Requests.Select(url => url.AbsoluteUri));
                Assert.Empty(downloader.Requests);
                Assert.Null(next.Status.Version);
                Assert.Equal(WindowsUpdatePhase.StartupDeferred, next.Status.Phase);
                await next.CheckAsync();
                Assert.Equal(WindowsUpdatePhase.Available, next.Status.Phase);
                Assert.Equal(4, inventory.Requests.Count);
                clock.Now = clock.Now.AddSeconds(1);
            }

            using var after = Launch();
            await after.CheckOnStartupAsync();
            Assert.Equal(WindowsUpdatePhase.Available, after.Status.Phase);
            Assert.Equal(seconds > 0 ? 6 : 4, inventory.Requests.Count);
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
            var inventory = new Discovery { NextResponse = new(HttpStatusCode.TooManyRequests) };
            using var http = new HttpClient(inventory);
            using var first = Launch();
            await first.CheckOnStartupAsync();
            Assert.Equal(WindowsUpdatePhase.Error, first.Status.Phase);
            Assert.Equal([IndexUrl, TagUrl], inventory.Requests.Select(url => url.AbsoluteUri));
            if (corruptMarker) File.WriteAllText(marker, "corrupt after throttling");
            using var next = Launch();
            await next.CheckOnStartupAsync();
            Assert.Equal(WindowsUpdatePhase.Available, next.Status.Phase);
            Assert.Equal(4, inventory.Requests.Count);

            WindowsUpdateController Launch() => new(new WindowsUpdateBackend(
                new WindowsGithubSource(new Downloader(), http, marker, new TestClock()),
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

    private sealed class Discovery : HttpMessageHandler
    {
        public HttpResponseMessage? NextResponse;
        public string Index = ValidIndex;
        public string Tag = Release().ToJsonString();
        public HttpResponseMessage? IndexResponse;
        public readonly Dictionary<string, HttpResponseMessage> Redirects = new();
        public readonly List<Uri> Requests = new();
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
        {
            var url = request.RequestUri!;
            Requests.Add(url);
            Assert.Equal(HttpMethod.Get, request.Method);
            Assert.Contains(request.Headers.UserAgent, value => value.Product?.Name == "MusicWindows-Updates");
            Assert.Null(request.Headers.Authorization);
            Assert.Null(request.Headers.ProxyAuthorization);
            if (Redirects.Remove(url.AbsoluteUri, out var redirect)) return Task.FromResult(Read("application/json", Index, redirect));
            return Task.FromResult(url.AbsoluteUri switch
            {
                IndexUrl => Read("application/json", Index, IndexResponse),
                TagUrl => Read("application/vnd.github+json", Tag, Interlocked.Exchange(ref NextResponse, null)),
                LegacyCdnUrl => Read("application/json", Index, null),
                _ => throw new InvalidOperationException($"Unexpected discovery request: {url}")
            });

            HttpResponseMessage Read(string accept, string body, HttpResponseMessage? response)
            {
                Assert.Equal(accept, Assert.Single(request.Headers.Accept).MediaType);
                return response ?? new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body) };
            }
        }
    }

    [Theory]
    [InlineData("not JSON")]
    [InlineData("[]")]
    [InlineData("""{"schema":2,"android":"v0.1.99","windows":"v0.1.20"}""")]
    [InlineData("""{"schema":"1","android":"v0.1.99","windows":"v0.1.20"}""")]
    [InlineData("""{"schema":1,"android":"v0.1.99","windows":"windows-v0.1.20"}""")]
    [InlineData("""{"schema":1,"android":"android-v0.1.99","windows":"v0.1.20"}""")]
    [InlineData("""{"schema":1,"windows":"v0.1.20"}""")]
    [InlineData("""{"schema":1,"android":"v0.1.99","windows":"v0.1.20","windows":"v0.1.20"}""")]
    [InlineData("""{"schema":1,"android":"v0.1.99","windows":"v0.1.020"}""")]
    [InlineData("""{"schema":1,"android":"v0.1.99","windows":"v0.1.20/../another-tag"}""")]
    [InlineData("""{"schema":1,"android":"v0.1.99","windows":"v0.1.65535"}""")]
    public async Task Invalid_index_stops_before_the_tag_request(string index)
    {
        var handler = new Discovery { Index = index };
        using var http = new HttpClient(handler);
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader, http, null, new TestClock());
        var error = await Record.ExceptionAsync(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.True(error is InvalidDataException or JsonException, error?.ToString());
        Assert.Equal(IndexUrl, Assert.Single(handler.Requests).AbsoluteUri);
        Assert.Empty(downloader.Requests);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task Index_read_stops_at_the_size_limit_with_or_without_a_content_length(bool declared)
    {
        using var body = new CountingStream(Encoding.UTF8.GetBytes(ValidIndex.PadRight(32 * 1024)));
        using var content = new StreamContent(body);
        if (declared) content.Headers.ContentLength = 32 * 1024;
        var handler = new Discovery { IndexResponse = new(HttpStatusCode.OK) { Content = content } };
        using var http = new HttpClient(handler);
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader, http, null, new TestClock());
        await Assert.ThrowsAsync<InvalidDataException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.Equal(declared ? 0 : 16 * 1024 + 1, body.BytesRead);
        Assert.Equal(IndexUrl, Assert.Single(handler.Requests).AbsoluteUri);
        Assert.Empty(downloader.Requests);
    }

    private sealed class CountingStream(byte[] bytes) : MemoryStream(bytes)
    {
        public override bool CanSeek => false;
        public int BytesRead { get; private set; }
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            int read = Read(buffer.Span);
            BytesRead += read;
            return ValueTask.FromResult(read);
        }
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task Missing_discovery_endpoint_does_not_fall_back_or_report_current(bool missingIndex)
    {
        var handler = new Discovery();
        if (missingIndex) handler.IndexResponse = new(HttpStatusCode.NotFound);
        else handler.NextResponse = new(HttpStatusCode.NotFound);
        using var http = new HttpClient(handler);
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader, http, null, new TestClock());
        var error = await Assert.ThrowsAsync<HttpRequestException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.Equal(HttpStatusCode.NotFound, error.StatusCode);
        Assert.Equal(missingIndex ? [IndexUrl] : [IndexUrl, TagUrl], handler.Requests.Select(url => url.AbsoluteUri));
        Assert.Empty(downloader.Requests);
    }

    [Theory]
    [InlineData("tag_name", "\"v0.1.19\"")]
    [InlineData("tag_name", "\"windows-v0.1.20\"")]
    [InlineData("draft", "true")]
    [InlineData("prerelease", "true")]
    [InlineData("published_at", "null")]
    [InlineData("published_at", "\"not a date\"")]
    [InlineData("assets", "[]")]
    public async Task Tag_response_must_be_the_exact_published_stable_release_with_windows_assets(string field, string value)
    {
        var tag = Release();
        tag[field] = JsonNode.Parse(value);
        var handler = new Discovery { Tag = tag.ToJsonString() };
        using var http = new HttpClient(handler);
        var downloader = new Downloader();
        var source = new WindowsGithubSource(downloader, http, null, new TestClock());
        await Assert.ThrowsAsync<InvalidDataException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.Equal([IndexUrl, TagUrl], handler.Requests.Select(url => url.AbsoluteUri));
        Assert.Empty(downloader.Requests);
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
    [InlineData("duplicate-entry")]
    [InlineData("delta-entry")]
    public async Task Mismatched_feed_is_rejected_instead_of_offering_a_package(string fault)
    {
        var source = new WindowsGithubSource(new Downloader { Fault = fault });
        await Assert.ThrowsAsync<InvalidDataException>(() => source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
    }

    [Fact]
    public async Task Malformed_feed_does_not_fall_back()
    {
        var downloader = new Downloader { Fault = "malformed-feed" };
        await Assert.ThrowsAsync<JsonException>(() => new WindowsGithubSource(downloader)
            .GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.Equal([IndexUrl, TagUrl, Root + FeedName], downloader.Requests);
    }

    [Theory]
    [InlineData("releases.win-x64.json")]
    [InlineData("RELEASES.WIN-X64.JSON")]
    [InlineData("MusicWindows-0.1.20-win-x64-full.nupkg")]
    [InlineData("MUSICWINDOWS-0.1.20-WIN-X64-FULL.NUPKG")]
    public async Task Ambiguous_selected_windows_asset_is_rejected_without_fetching_the_feed(string duplicate)
    {
        var tag = Release();
        tag["assets"]!.AsArray().Add(Asset(duplicate));
        var downloader = new Downloader { Tag = tag.ToJsonString() };
        await Assert.ThrowsAsync<InvalidDataException>(() => new WindowsGithubSource(downloader)
            .GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64"));
        Assert.Equal([IndexUrl, TagUrl], downloader.Requests);
    }

    private const string Root = WindowsGithubSource.Repository + "/releases/download/v0.1.20/";
    private const string PackageName = "MusicWindows-0.1.20-win-x64-full.nupkg";
    private const string FeedName = "releases.win-x64.json";

    private static JsonObject Release() => new()
    {
        ["tag_name"] = "v0.1.20", ["prerelease"] = false, ["draft"] = false,
        ["published_at"] = "2025-01-01T00:00:00Z",
        ["assets"] = new JsonArray(Asset(FeedName), Asset(PackageName), Asset("Music-0.1.20.apk"))
    };

    private static JsonObject Asset(string name) => new() { ["name"] = name, ["browser_download_url"] = Root + name };

    private sealed class Downloader : IFileDownloader
    {
        public string? Fault, Tag;
        public readonly List<string> Requests = new();
        public Task<string> DownloadString(string url, IDictionary<string, string>? headers = null, double timeout = 30) =>
            throw new InvalidOperationException($"Unexpected string request: {url}");
        public Task<byte[]> DownloadBytes(string url, IDictionary<string, string>? headers = null, double timeout = 30)
        {
            Requests.Add(url);
            if (url == IndexUrl) return Task.FromResult(Encoding.UTF8.GetBytes(ValidIndex));
            if (url == TagUrl)
            {
                var tag = Release();
                if (Fault == "wrong-origin") tag["assets"]![0]!["browser_download_url"] = "https://example.com/releases.win-x64.json";
                if (Fault == "missing-package") tag["assets"]!.AsArray().RemoveAt(1);
                return Task.FromResult(Encoding.UTF8.GetBytes(Tag ?? tag.ToJsonString()));
            }
            Assert.Equal(Root + FeedName, url);
            string feed = """
                {"Assets":[{"PackageId":"MusicWindows","Version":"0.1.20","Type":"Full","FileName":"MusicWindows-0.1.20-win-x64-full.nupkg","Size":100,"SHA256":"ABCD"},{"PackageId":"Android","Version":"0.1.99","Type":"Full","FileName":"music.apk","Size":1}]}
                """;
            if (Fault == "wrong-version") feed = feed.Replace("0.1.20", "0.1.21", StringComparison.Ordinal);
            if (Fault == "wrong-architecture") feed = feed.Replace("win-x64", "win-arm64", StringComparison.Ordinal);
            if (Fault == "empty-feed") feed = "{\"Assets\":[]}";
            if (Fault == "wrong-package") feed = feed.Replace("MusicWindows", "UnrelatedApp", StringComparison.Ordinal);
            if (Fault == "malformed-feed") feed = "not JSON";
            if (Fault == "delta-entry") feed = feed.Replace("\"Type\":\"Full\"", "\"Type\":\"Delta\"", StringComparison.Ordinal);
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
