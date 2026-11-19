using System.Net;
using System.Text;
using System.Text.Json;
using Music.App;
using Xunit;

namespace Music.Update.Tests;

public sealed class WindowsChangelogTests
{
    private const string Base = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private const string Head = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private const string Api = "https://api.github.com/repos/everyonesoftware/music/";
    private const string Repo = "https://github.com/everyonesoftware/music";

    [Fact]
    public async Task Independently_resolves_published_windows_manifests_and_compares_exact_sources()
    {
        using var handler = new Fixture();
        using var http = new HttpClient(handler);
        using var client = new WindowsChangelogClient(http);
        var result = await client.LoadAsync(new Version("0.1.10"), new Version("0.1.30"), CancellationToken.None);
        Assert.Equal(Base, result.BaseRevision);
        Assert.Equal(Head, result.HeadRevision);
        Assert.Equal("Preserve playback", Assert.Single(result.Fixes));
        Assert.Empty(result.Improvements);
        Assert.Equal("[verified] fix(windows): Preserve playback", Assert.Single(result.Commits).Subject);
        Assert.Contains(handler.Requests, u => u == Api + $"compare/{Base}...{Head}?per_page=100&page=1");
        Assert.DoesNotContain(handler.Requests, u => u.Contains("/latest") || u.Contains("compare/v"));
    }

    [Fact]
    public async Task Collects_every_compare_page_including_skipped_releases()
    {
        string[] shas = Enumerable.Range(1, 100).Select(n => n.ToString("x40")).Append(Head).ToArray();
        using var handler = new Fixture { Override = url => url.Contains("/compare/")
            ? Comparison(url.EndsWith("page=1", StringComparison.Ordinal) ? shas[..100] : shas[100..], 101) : null };
        using var http = new HttpClient(handler);
        using var client = new WindowsChangelogClient(http);
        var result = await client.LoadAsync(new Version("0.1.10"), new Version("0.1.30"), CancellationToken.None);
        Assert.Equal(shas, result.Commits.Select(c => c.Sha));
        Assert.Contains(handler.Requests, u => u.EndsWith("page=2", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Omits_docs_ci_and_android_only_bullets_but_retains_all_original_commit_details()
    {
        string[] subjects = ["docs: Update setup notes", "ci: Adjust Android build", "fix(android): Repair notification", "[verified] feat(windows): Add output chooser", "fix(shared): Preserve playback", "fix(android): Repair shared protocol"];
        string[] shas = Enumerable.Range(1, subjects.Length - 1).Select(n => n.ToString("x40")).Append(Head).ToArray();
        string[][] paths = [["docs/SETUP.md"], [".github/workflows/ci.yml"], ["android/app/src/Main.kt"], ["apps/windows/Music.App/MainWindow.cs"], ["shared/protocol.json"], ["android/Main.kt", "shared/protocol.json"]];
        using var handler = new Fixture { Override = url =>
        {
            if (url.Contains("/compare/")) return new { status = "ahead", ahead_by = subjects.Length, behind_by = 0, total_commits = subjects.Length, base_commit = new { sha = Base }, merge_base_commit = new { sha = Base }, commits = shas.Select((sha, i) => Commit(sha, subjects[i])) };
            if (!url.Contains("/commits/")) return null;
            string sha = url.Split("/commits/")[1].Split('?')[0];
            return new { sha, files = paths[Array.IndexOf(shas, sha)].Select(filename => new { filename, status = "modified" }) };
        } };
        using var http = new HttpClient(handler);
        using var client = new WindowsChangelogClient(http);
        var result = await client.LoadAsync(new Version("0.1.10"), new Version("0.1.30"), CancellationToken.None);
        Assert.Equal(["Add output chooser"], result.Improvements);
        Assert.Equal(["Preserve playback", "Repair shared protocol"], result.Fixes);
        Assert.Equal(subjects, result.Commits.Select(c => c.Subject));
    }

    [Fact]
    public async Task Does_not_download_manifests_for_unrelated_observed_release_versions()
    {
        using var handler = new Fixture();
        using var http = new HttpClient(handler);
        using var client = new WindowsChangelogClient(http);
        await client.LoadAsync(new Version("0.1.10"), new Version("0.1.30"), CancellationToken.None);
        Assert.DoesNotContain(handler.Requests, url => url.Contains("/v0.1.20/"));
        Assert.DoesNotContain(handler.Requests, url => url.Contains("/commits/"));
    }

    [Fact]
    public async Task Finds_independent_windows_publications_beyond_first_mixed_inventory_page()
    {
        using var handler = new Fixture { Override = url => url == Api + "releases?per_page=100&page=1"
            ? Enumerable.Repeat(Release("v0.1.99", Head), 100).ToArray()
            : url == Api + "releases?per_page=100&page=2" ? new[] { Release("v0.1.30", Head), Release("windows-v0.1.10", Base) } : null };
        using var http = new HttpClient(handler);
        using var client = new WindowsChangelogClient(http);
        var result = await client.LoadAsync(new Version("0.1.10"), new Version("0.1.30"), CancellationToken.None);
        Assert.Single(result.Commits);
        Assert.DoesNotContain(handler.Requests, u => u.Contains("/v0.1.99/"));
    }

    [Fact]
    public async Task Full_tenth_release_inventory_page_is_unavailable_even_when_sources_were_found()
    {
        object[] releases = Enumerable.Repeat(Release("v0.1.99", Head), 98).Concat([Release("v0.1.30", Head), Release("windows-v0.1.10", Base)]).ToArray();
        using var handler = new Fixture { Override = url => url.Contains("releases?per_page") ? releases : null };
        using var http = new HttpClient(handler);
        using var client = new WindowsChangelogClient(http);
        await Assert.ThrowsAsync<InvalidDataException>(() => client.LoadAsync(new Version("0.1.10"), new Version("0.1.30"), CancellationToken.None));
        Assert.DoesNotContain(handler.Requests, u => u.Contains("/compare/"));
    }

    [Theory]
    [InlineData("missing-source")]
    [InlineData("wrong-platform")]
    [InlineData("wrong-version")]
    [InlineData("wrong-source")]
    [InlineData("tag-mismatch")]
    [InlineData("wrong-origin")]
    [InlineData("missing-package")]
    public async Task Missing_or_unverified_windows_source_fails_closed(string fault)
    {
        using var handler = new Fixture { Override = url =>
        {
            if (url == Api + "releases?per_page=100&page=1" && fault == "missing-source") return new[] { Release("v0.1.30", Head) };
            if (url == Api + "releases?per_page=100&page=1" && fault == "wrong-origin")
            {
                var release = Node(Release("v0.1.30", Head));
                release["assets"]![5]!["browser_download_url"] = "https://attacker.invalid/windows-release.json";
                return new[] { release, Node(Release("windows-v0.1.10", Base)) };
            }
            if (url.EndsWith("/v0.1.30/windows-release.json", StringComparison.Ordinal))
            {
                var manifest = Node(Manifest("0.1.30", Head));
                if (fault == "wrong-platform") manifest["platform"] = "android";
                if (fault == "wrong-version") manifest["version"] = "0.1.31";
                if (fault == "wrong-source") manifest["sha"] = new string('c', 40);
                if (fault == "missing-package") manifest["artifacts"]!.AsArray().RemoveAt(1);
                return manifest;
            }
            if (url.EndsWith("git/ref/tags/v0.1.30", StringComparison.Ordinal) && fault == "tag-mismatch") return new { @object = new { type = "commit", sha = Base } };
            return null;
        } };
        using var http = new HttpClient(handler);
        using var client = new WindowsChangelogClient(http);
        await Assert.ThrowsAsync<InvalidDataException>(() => client.LoadAsync(new Version("0.1.10"), new Version("0.1.30"), CancellationToken.None));
        Assert.DoesNotContain(handler.Requests, u => u.Contains("attacker.invalid") || u.Contains("/compare/"));
    }

    [Theory]
    [InlineData("diverged")]
    [InlineData("truncated")]
    [InlineData("too-many")]
    [InlineData("duplicate")]
    [InlineData("wrong-head")]
    [InlineData("wrong-base")]
    [InlineData("changed-total")]
    public async Task Unavailable_or_incomplete_compare_history_never_becomes_a_partial_changelog(string fault)
    {
        using var handler = new Fixture { Override = url =>
        {
            if (!url.Contains("/compare/")) return null;
            int count = fault == "too-many" ? 1001 : fault == "truncated" || fault == "duplicate" ? 2 : 1;
            var result = Node(Comparison(fault == "duplicate" ? [Head, Head] : fault == "wrong-head" ? [new string('c', 40)] : [Head], count, fault == "diverged" ? "diverged" : "ahead"));
            if (fault == "wrong-base") result["merge_base_commit"]!["sha"] = new string('c', 40);
            if (fault == "changed-total")
            {
                string[] first = Enumerable.Range(1, 100).Select(n => n.ToString("x40")).ToArray();
                return Comparison(url.EndsWith("page=1", StringComparison.Ordinal) ? first : [Head], url.EndsWith("page=1", StringComparison.Ordinal) ? 101 : 102);
            }
            return result;
        } };
        using var http = new HttpClient(handler);
        using var client = new WindowsChangelogClient(http);
        await Assert.ThrowsAsync<InvalidDataException>(() => client.LoadAsync(new Version("0.1.10"), new Version("0.1.30"), CancellationToken.None));
    }

    [Fact]
    public async Task Paginates_file_evidence_before_excluding_a_mixed_shared_change()
    {
        using var handler = new Fixture { Override = url =>
        {
            if (url.Contains("/compare/")) return new { status = "ahead", ahead_by = 1, behind_by = 0, total_commits = 1, base_commit = new { sha = Base }, merge_base_commit = new { sha = Base }, commits = new[] { Commit(Head, "docs: Update shared validation") } };
            if (!url.Contains("/commits/")) return null;
            string[] files = url.EndsWith("page=1", StringComparison.Ordinal) ? Enumerable.Range(1, 100).Select(n => $"docs/{n}.md").ToArray() : ["shared/validation.json"];
            return new { sha = Head, files = files.Select(filename => new { filename }) };
        } };
        using var http = new HttpClient(handler);
        using var client = new WindowsChangelogClient(http);
        var result = await client.LoadAsync(new Version("0.1.10"), new Version("0.1.30"), CancellationToken.None);
        Assert.Equal("Update shared validation", Assert.Single(result.Improvements));
        Assert.Contains(handler.Requests, u => u.Contains("/commits/") && u.EndsWith("page=2", StringComparison.Ordinal));
    }

    private static System.Text.Json.Nodes.JsonObject Node(object value) => System.Text.Json.Nodes.JsonNode.Parse(JsonSerializer.Serialize(value))!.AsObject();

    private static object Comparison(string[] shas, int total, string status = "ahead") => new
    {
        status, ahead_by = total, behind_by = 0, total_commits = total,
        base_commit = new { sha = Base }, merge_base_commit = new { sha = Base },
        commits = shas.Select(sha => Commit(sha, "feat: Improve playback")).ToArray()
    };

    private sealed class Fixture : HttpMessageHandler
    {
        public List<string> Requests { get; } = [];
        public Func<string, object?>? Override { get; init; }
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            string url = request.RequestUri!.AbsoluteUri;
            Requests.Add(url);
            Assert.Null(request.Headers.Authorization);
            object value;
            if (Override?.Invoke(url) is { } custom) value = custom;
            else if (url == Api + "releases?per_page=100&page=1")
                value = new[] { Release("v0.1.30", Head), Release("v0.1.20", new string('c', 40)), Release("windows-v0.1.10", Base) };
            else if (url.EndsWith("/windows-release.json"))
            {
                bool old = url.Contains("/windows-v0.1.10/");
                string version = old ? "0.1.10" : url.Contains("/v0.1.20/") ? "0.1.20" : "0.1.30";
                value = Manifest(version, old ? Base : version == "0.1.20" ? new string('c', 40) : Head);
            }
            else if (url.Contains("git/ref/tags/"))
                value = new { @object = new { type = "commit", sha = url.EndsWith("windows-v0.1.10") ? Base : url.EndsWith("v0.1.20") ? new string('c', 40) : Head } };
            else if (url.Contains("/compare/"))
                value = new { status = "ahead", ahead_by = 1, behind_by = 0, total_commits = 1, base_commit = new { sha = Base }, merge_base_commit = new { sha = Base }, commits = new[] { Commit(Head, "[verified] fix(windows): Preserve playback") } };
            else if (url.Contains("/commits/"))
                value = new { sha = url.Split("/commits/")[1].Split('?')[0], files = new[] { new { filename = "apps/windows/Music.App/MainWindow.cs", status = "modified" } } };
            else throw new InvalidOperationException("Unexpected fixture request: " + url);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json") });
        }
    }

    private static object Commit(string sha, string subject) => new { sha, commit = new { message = subject + "\n\nOriginal body" } };
    private static string[] Names(string version) => ["MusicWindows-win-x64-Setup.exe", $"MusicWindows-{version}-win-x64-full.nupkg", "releases.win-x64.json", "assets.win-x64.json", "RELEASES-win-x64"];
    private static object Release(string tag, string sha) => new
    {
        tag_name = tag, target_commitish = sha, draft = false, prerelease = tag.StartsWith("windows-", StringComparison.Ordinal), body = "NOT A CHANGELOG",
        assets = Names(tag[(tag.StartsWith("windows-", StringComparison.Ordinal) ? 9 : 1)..]).Append("windows-release.json").Select(name => new { name, state = "uploaded", size = 123, browser_download_url = $"{Repo}/releases/download/{tag}/{name}" }).ToArray()
    };
    private static object Manifest(string version, string sha) => new
    {
        version, sha, count = int.Parse(version.Split('.')[2]), tag = "windows-v" + version, platform = "windows-x64", package_id = "MusicWindows", runtime = "win-x64", channel = "win-x64", signing = "unsigned-preview",
        artifacts = Names(version).Select(name => new { name, bytes = 123, sha256 = new string('d', 64) }).ToArray()
    };
}
