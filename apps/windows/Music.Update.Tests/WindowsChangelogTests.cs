using System.Text;
using System.Text.Json;
using Music.App;
using Velopack;
using Velopack.Logging;
using Velopack.Sources;
using Xunit;

namespace Music.Update.Tests;

public sealed class WindowsChangelogTests
{
    private const string Base = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private const string Head = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private const string Middle = "cccccccccccccccccccccccccccccccccccccccc";
    private const string Hash = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

    [Fact]
    public async Task Update_check_fetches_only_selected_manifest_alongside_feed()
    {
        var downloader = new Fixture();
        var source = new WindowsGithubSource(downloader);
        var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        Assert.Single(feed.Assets);
        Assert.Single(downloader.Requests, u => u.EndsWith("/v0.1.30/windows-release.json", StringComparison.Ordinal));
        Assert.Equal(3, downloader.Requests.Count);
        Assert.DoesNotContain(downloader.Requests, u => u.Contains("/compare/") || u.Contains("/commits/") || u.Contains("git/ref") || u.Contains("/v0.1.28/"));
    }

    [Fact]
    public async Task Cached_manifest_concatenates_skipped_segments_without_disclosure_network()
    {
        var downloader = new Fixture();
        var source = new WindowsGithubSource(downloader);
        var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        var offer = Assert.Single(feed.Assets);
        int requests = downloader.Requests.Count;
        var changes = Assert.IsType<Music.Core.WindowsChangelog>(source.GetChangelog(new Version("0.1.28"), offer));
        Assert.Equal(Base, changes.BaseRevision);
        Assert.Equal(Head, changes.HeadRevision);
        Assert.Equal([Middle, Head], changes.Commits.Select(c => c.Sha));
        Assert.Equal(["docs: Original docs", "[verified] fix(windows): Preserve playback"], changes.Commits.Select(c => c.Subject));
        Assert.Equal(["Preserve playback"], changes.Fixes);
        Assert.Empty(changes.Improvements);
        Assert.Single(source.GetChangelog(new Version("0.1.29"), offer)!.Commits);
        Assert.Null(source.GetChangelog(new Version("0.1.11"), offer));
        Assert.Null(source.GetChangelog(new Version("0.1.30"), offer));
        Assert.Equal(requests, downloader.Requests.Count);
    }

    [Theory]
    [InlineData("schema")]
    [InlineData("platform")]
    [InlineData("manifest-version")]
    [InlineData("manifest-source")]
    [InlineData("manifest-platform")]
    [InlineData("package")]
    [InlineData("channel")]
    [InlineData("tag")]
    [InlineData("count")]
    [InlineData("head-version")]
    [InlineData("head-source")]
    [InlineData("uppercase-sha")]
    [InlineData("short-sha")]
    [InlineData("missing-baseline")]
    [InlineData("baseline-commits")]
    [InlineData("empty-segment")]
    [InlineData("wrong-segment-head")]
    [InlineData("duplicate-commit")]
    [InlineData("baseline-sha-repeated")]
    [InlineData("duplicate-version")]
    [InlineData("descending-version")]
    [InlineData("noncanonical-version")]
    [InlineData("version-bound")]
    [InlineData("unknown-kind")]
    [InlineData("null-kind-text")]
    [InlineData("kind-null-text")]
    [InlineData("blank-text")]
    [InlineData("multiline-subject")]
    [InlineData("artifact-hash")]
    [InlineData("artifact-size")]
    [InlineData("missing-artifact")]
    [InlineData("missing-changelog")]
    [InlineData("too-large")]
    public async Task Invalid_metadata_is_unavailable_without_breaking_the_update(string fault)
    {
        var node = Manifest();
        var log = node["changelog"]!;
        var entries = log["releases"]!;
        var commit = entries[2]!["commits"]![0]!;
        switch (fault)
        {
            case "schema": log["schema"] = "1"; break;
            case "platform": log["platform"] = "android"; break;
            case "manifest-version": node["version"] = "0.1.31"; break;
            case "manifest-source": node["sha"] = Base; break;
            case "manifest-platform": node["platform"] = "android"; break;
            case "package": node["package_id"] = "Other"; break;
            case "channel": node["channel"] = "win-arm64"; break;
            case "tag": node["tag"] = "windows-v0.1.31"; break;
            case "count": node["count"] = 31; break;
            case "head-version": log["head"]!["version"] = "0.1.29"; break;
            case "head-source": log["head"]!["sha"] = Base; break;
            case "uppercase-sha": entries[0]!["sha"] = Base.ToUpperInvariant(); break;
            case "short-sha": entries[0]!["sha"] = "aaaaaaa"; break;
            case "missing-baseline": entries.AsArray().RemoveAt(0); break;
            case "baseline-commits": entries[0]!["commits"]!.AsArray().Add(commit.DeepClone()); break;
            case "empty-segment": entries[1]!["commits"]!.AsArray().Clear(); break;
            case "wrong-segment-head": entries[1]!["sha"] = Base; break;
            case "duplicate-commit": entries[2]!["commits"]!.AsArray().Insert(0, entries[1]!["commits"]![0]!.DeepClone()); break;
            case "baseline-sha-repeated": entries[2]!["commits"]!.AsArray().Insert(0, new System.Text.Json.Nodes.JsonObject { ["sha"] = Base, ["subject"] = "repeat", ["kind"] = null, ["text"] = null }); break;
            case "duplicate-version": entries[1]!["version"] = "0.1.28"; break;
            case "descending-version": entries[1]!["version"] = "0.1.9"; break;
            case "noncanonical-version": entries[1]!["version"] = "0.1.020"; break;
            case "version-bound": entries[1]!["version"] = "0.1.65535"; break;
            case "unknown-kind": commit["kind"] = "feature"; break;
            case "null-kind-text": commit["kind"] = null; break;
            case "kind-null-text": commit["text"] = null; break;
            case "blank-text": commit["text"] = "  "; break;
            case "multiline-subject": commit["subject"] = "fix: one\ntwo"; break;
            case "artifact-hash": node["artifacts"]![1]!["sha256"] = new string('e', 64); break;
            case "artifact-size": node["artifacts"]![1]!["bytes"] = 124; break;
            case "missing-artifact": node["artifacts"]!.AsArray().RemoveAt(0); break;
            case "missing-changelog": node.Remove("changelog"); break;
            case "too-large": node["padding"] = new string('x', 1024 * 1024); break;
        }
        var downloader = new Fixture { ManifestValue = node };
        var source = new WindowsGithubSource(downloader);
        var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        var offer = Assert.Single(feed.Assets);
        Assert.Null(source.GetChangelog(new Version("0.1.28"), offer));
        await source.DownloadReleaseEntry(NullVelopackLogger.Instance, offer, "unused", _ => {}, CancellationToken.None);
        Assert.EndsWith("/MusicWindows-0.1.30-win-x64-full.nupkg", downloader.Requests.Last());
    }

    [Theory]
    [InlineData("missing")]
    [InlineData("duplicate")]
    [InlineData("origin")]
    [InlineData("unpublished")]
    [InlineData("wrong-source")]
    [InlineData("transport")]
    [InlineData("json")]
    [InlineData("duplicate-json-property")]
    public async Task Optional_manifest_failures_do_not_disable_a_valid_package(string fault)
    {
        var downloader = new Fixture { Fault = fault };
        var source = new WindowsGithubSource(downloader);
        var feed = await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64");
        Assert.Null(source.GetChangelog(new Version("0.1.28"), Assert.Single(feed.Assets)));
        Assert.DoesNotContain(downloader.Requests, u => u.Contains("attacker.invalid"));
    }

    [Fact]
    public async Task Rechecking_same_version_invalidates_old_asset_snapshot()
    {
        var downloader = new Fixture();
        var source = new WindowsGithubSource(downloader);
        var first = Assert.Single((await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64")).Assets);
        Assert.NotNull(source.GetChangelog(new Version("0.1.28"), first));
        downloader.Fault = "missing";
        var second = Assert.Single((await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64")).Assets);
        Assert.Null(source.GetChangelog(new Version("0.1.28"), first));
        Assert.Null(source.GetChangelog(new Version("0.1.28"), second));
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Http_manifest_read_is_bounded_for_declared_and_chunked_oversize(bool declared)
    {
        var downloader = new Fixture();
        using var handler = new ManifestHandler(declared);
        using var http = new HttpClient(handler);
        var source = new WindowsGithubSource(downloader, http);
        var offer = Assert.Single((await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64")).Assets);
        Assert.Null(source.GetChangelog(new Version("0.1.28"), offer));
        Assert.Equal(1, handler.Requests);
        Assert.True(handler.Body.BytesRead <= (declared ? 0 : WindowsChangelogClient.MaximumManifestBytes + 8192));
        Assert.DoesNotContain(downloader.Requests, u => u.EndsWith("/windows-release.json", StringComparison.Ordinal));
    }

    private sealed class ManifestHandler(bool declared) : HttpMessageHandler
    {
        public int Requests;
        public readonly CountingStream Body = new();
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests++;
            Assert.Null(request.Headers.Authorization);
            Assert.EndsWith("/v0.1.30/windows-release.json", request.RequestUri!.AbsoluteUri);
            var content = new StreamContent(Body);
            if (declared) content.Headers.ContentLength = 2 * WindowsChangelogClient.MaximumManifestBytes;
            return Task.FromResult(new HttpResponseMessage(System.Net.HttpStatusCode.OK) { Content = content });
        }
    }
    private sealed class CountingStream : Stream
    {
        public int BytesRead;
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => BytesRead; set => throw new NotSupportedException(); }
        public override int Read(byte[] buffer, int offset, int count) { Array.Fill(buffer, (byte)'x', offset, count); BytesRead += count; return count; }
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default) { buffer.Span.Fill((byte)'x'); BytesRead += buffer.Length; return ValueTask.FromResult(buffer.Length); }
        public override void Flush() => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    [Fact]
    public async Task Real_pinned_velopack_check_preserves_exact_manifest_offer_identity()
    {
        string directory = Path.Combine(Path.GetTempPath(), "music-windows-precomputed-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(directory);
        try
        {
            var downloader = new Fixture();
            var source = new WindowsGithubSource(downloader);
            var locator = new Velopack.Locators.TestVelopackLocator("MusicWindows", "0.1.28", directory, logger: NullVelopackLogger.Instance);
            var manager = new UpdateManager(source, new UpdateOptions { ExplicitChannel = "win-x64", AllowVersionDowngrade = false, MaximumDeltasBeforeFallback = -1 }, locator);
            var update = Assert.IsType<UpdateInfo>(await manager.CheckForUpdatesAsync());
            var notes = Assert.IsType<Music.Core.WindowsChangelog>(source.GetChangelog(Version.Parse(manager.CurrentVersion!.ToString()), update.TargetFullRelease));
            Assert.Equal(Head, notes.HeadRevision);
            Assert.Equal(3, downloader.Requests.Count);
        }
        finally { Directory.Delete(directory, true); }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Producer_kinds_and_text_are_authoritative_even_for_docs_subjects(bool summarized)
    {
        var node = Manifest();
        var releases = node["changelog"]!["releases"]!;
        var commit = releases[2]!["commits"]![0]!;
        commit["subject"] = "docs: Mixed Windows change";
        commit["kind"] = summarized ? "improvement" : null;
        commit["text"] = summarized ? "Producer-cleaned text [unchanged]" : null;
        var downloader = new Fixture { ManifestValue = node };
        var source = new WindowsGithubSource(downloader);
        var offer = Assert.Single((await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64")).Assets);
        var notes = Assert.IsType<Music.Core.WindowsChangelog>(source.GetChangelog(new Version("0.1.28"), offer));
        Assert.Equal(summarized ? ["Producer-cleaned text [unchanged]"] : Array.Empty<string>(), notes.Improvements);
        Assert.Empty(notes.Fixes);
        Assert.Equal(["docs: Original docs", "docs: Mixed Windows change"], notes.Commits.Select(c => c.Subject));
    }

    [Theory]
    [InlineData("")]
    [InlineData("  ")]
    [InlineData("docs: Original carriage return\r")]
    public async Task Raw_first_line_is_preserved_even_when_producer_has_no_summary(string subject)
    {
        var node = Manifest();
        var commit = node["changelog"]!["releases"]![2]!["commits"]![0]!;
        commit["subject"] = subject;
        commit["kind"] = null;
        commit["text"] = null;
        var downloader = new Fixture { ManifestValue = node };
        var source = new WindowsGithubSource(downloader);
        var offer = Assert.Single((await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64")).Assets);
        var notes = Assert.IsType<Music.Core.WindowsChangelog>(source.GetChangelog(new Version("0.1.29"), offer));
        Assert.Equal(subject, Assert.Single(notes.Commits).Subject);
        Assert.Empty(notes.Improvements);
        Assert.Empty(notes.Fixes);
    }

    [Theory]
    [InlineData('\r')]
    [InlineData('\0')]
    public async Task Normalized_bullets_preserve_original_control_characters_in_details(char control)
    {
        var node = Manifest();
        var commit = node["changelog"]!["releases"]![2]!["commits"]![0]!;
        string subject = $"fix: Original{control}subject";
        commit["subject"] = subject;
        commit["text"] = "Original subject";
        var downloader = new Fixture { ManifestValue = node };
        var source = new WindowsGithubSource(downloader);
        var offer = Assert.Single((await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64")).Assets);
        var notes = Assert.IsType<Music.Core.WindowsChangelog>(source.GetChangelog(new Version("0.1.29"), offer));
        Assert.Equal(subject, Assert.Single(notes.Commits).Subject);
        Assert.Equal(["Original subject"], notes.Fixes);
    }

    [Theory]
    [InlineData(1)]
    [InlineData(2)]
    public async Task Missing_nonfinal_commit_makes_cached_notes_unavailable_without_breaking_update(int segment)
    {
        var node = Manifest();
        var releases = node["changelog"]!["releases"]!;
        releases[0]!["version"] = "0.1.27";
        releases[1]!["version"] = segment == 1 ? "0.1.29" : "0.1.28";
        var commits = releases[segment]!["commits"]!.AsArray();
        commits.Insert(0, new System.Text.Json.Nodes.JsonObject
        {
            ["sha"] = new string('e', 40), ["subject"] = "docs: Nonfinal original", ["kind"] = null, ["text"] = null
        });
        var downloader = new Fixture { ManifestValue = node };
        var source = new WindowsGithubSource(downloader);
        var completeOffer = Assert.Single((await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64")).Assets);
        Assert.Equal(3, Assert.IsType<Music.Core.WindowsChangelog>(source.GetChangelog(new Version("0.1.27"), completeOffer)).Commits.Count);

        commits.RemoveAt(0);
        Assert.Equal(releases[segment]!["sha"]!.GetValue<string>(), commits.Last()!["sha"]!.GetValue<string>());
        var offer = Assert.Single((await source.GetReleaseFeed(NullVelopackLogger.Instance, "MusicWindows", "win-x64")).Assets);
        int requests = downloader.Requests.Count;
        Assert.Null(source.GetChangelog(new Version("0.1.27"), offer));
        Assert.Equal(requests, downloader.Requests.Count);
        await source.DownloadReleaseEntry(NullVelopackLogger.Instance, offer, "unused", _ => {}, CancellationToken.None);
        Assert.EndsWith("/MusicWindows-0.1.30-win-x64-full.nupkg", downloader.Requests.Last());
    }

    internal static System.Text.Json.Nodes.JsonObject Manifest() => System.Text.Json.Nodes.JsonNode.Parse(JsonSerializer.Serialize(new
    {
        version = "0.1.30", count = 30, sha = Head, tag = "windows-v0.1.30", platform = "windows-x64", package_id = "MusicWindows", runtime = "win-x64", channel = "win-x64", signing = "unsigned-preview",
        artifacts = Names.Select(name => new { name, bytes = 123, sha256 = Hash }),
        changelog = new { schema = 1, platform = "windows", head = new { version = "0.1.30", sha = Head }, releases = new object[] {
            new { version = "0.1.28", sha = Base, commits = Array.Empty<object>() },
            new { version = "0.1.29", sha = Middle, commits = new object[] { new { sha = Middle, subject = "docs: Original docs", kind = (string?)null, text = (string?)null } } },
            new { version = "0.1.30", sha = Head, commits = new object[] { new { sha = Head, subject = "[verified] fix(windows): Preserve playback", kind = "fix", text = "Preserve playback" } } }
        } }
    }))!.AsObject();

    private static readonly string[] Names = ["MusicWindows-win-x64-Setup.exe", "MusicWindows-0.1.30-win-x64-full.nupkg", "releases.win-x64.json", "assets.win-x64.json", "RELEASES-win-x64"];

    private sealed class Fixture : IFileDownloader
    {
        public object? ManifestValue { get; init; }
        public string? Fault { get; set; }
        public List<string> Requests { get; } = [];
        public Task<string> DownloadString(string url, IDictionary<string, string>? headers = null, double timeout = 30)
        {
            Requests.Add(url);
            var manifestNames = Fault == "missing" ? Names : Fault == "duplicate" ? Names.Concat(["windows-release.json", "WINDOWS-RELEASE.JSON"]) : Names.Append("windows-release.json");
            return Task.FromResult(JsonSerializer.Serialize(new[] { new {
                tag_name = "v0.1.30", target_commitish = Fault == "wrong-source" ? Base : Head, draft = false, prerelease = false,
                assets = manifestNames.Select(name => new { name, state = Fault == "unpublished" && name == "windows-release.json" ? "new" : "uploaded", size = 123, browser_download_url = Fault == "origin" && name == "windows-release.json" ? "https://attacker.invalid/windows-release.json" : $"{WindowsGithubSource.Repository}/releases/download/v0.1.30/{name}" })
            } }));
        }
        public Task<byte[]> DownloadBytes(string url, IDictionary<string, string>? headers = null, double timeout = 30)
        {
            Requests.Add(url);
            if (url.EndsWith("/windows-release.json", StringComparison.Ordinal))
            {
                if (Fault == "transport") throw new HttpRequestException("private URL");
                if (Fault == "json") return Task.FromResult(Encoding.UTF8.GetBytes("not JSON"));
                if (Fault == "duplicate-json-property") return Task.FromResult(Encoding.UTF8.GetBytes(Manifest().ToJsonString().Replace("\"schema\":1", "\"schema\":2,\"schema\":1", StringComparison.Ordinal)));
            }
            object value = url.EndsWith("/windows-release.json", StringComparison.Ordinal) ? ManifestValue ?? Manifest() : new { Assets = new[] { new { PackageId = "MusicWindows", Version = "0.1.30", Type = "Full", FileName = Names[1], Size = 123, SHA256 = Hash } } };
            return Task.FromResult(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(value)));
        }
        public Task DownloadFile(string url, string targetFile, Action<int>? progress = null, IDictionary<string, string>? headers = null, double timeout = 30, CancellationToken cancelToken = default)
        { Requests.Add(url); return Task.CompletedTask; }
    }
}
