using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.RegularExpressions;
using Music.Core;

namespace Music.App;

/// <summary>Public, credential-independent history of the actual Windows publications, not release bodies.</summary>
public sealed class WindowsChangelogClient : IDisposable
{
    private const string Api = "https://api.github.com/repos/everyonesoftware/music/";
    private const string Repository = "https://github.com/everyonesoftware/music";
    private readonly HttpClient client;
    private readonly bool ownsClient;

    public WindowsChangelogClient(HttpClient? client = null)
    {
        ownsClient = client is null;
        this.client = client ?? new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
    }

    public async Task<WindowsChangelog> LoadAsync(Version installed, Version offered, CancellationToken token)
    {
        if (offered <= installed) throw new InvalidDataException("No newer Windows publication.");
        var sources = await ResolveSourcesAsync(installed, offered, token);
        string from = sources[installed], to = sources[offered];
        var commits = await CompareAsync(from, to, token);
        var improvements = new List<string>();
        var fixes = new List<string>();
        foreach (var commit in commits)
        {
            if (!await IsRelevantAsync(commit, token)) continue;
            string subject = Regex.Replace(commit.Subject, @"^(?:\[verified\]\s*)+", "", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            bool fix = Regex.IsMatch(subject, @"^(?:fix|bugfix|hotfix)(?:\([^)]*\))?!?:|^fix(?:es|ed)?\b", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            subject = Regex.Replace(subject, @"^[a-z]+(?:\([^)]*\))?!?:\s*", "", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            (fix ? fixes : improvements).Add(subject);
        }
        return new(from, to, improvements.AsReadOnly(), fixes.AsReadOnly(), Array.AsReadOnly(commits));
    }

    private async Task<bool> IsRelevantAsync(WindowsChangeCommit commit, CancellationToken token)
    {
        // Shared/unscoped subjects are retained without N+1 requests. Consult files only before
        // discarding an explicitly unrelated scope, so mixed shared/Windows changes remain visible.
        bool unrelatedScope = Regex.IsMatch(commit.Subject, @"^(?:\[verified\]\s*)*(?:(?:docs|ci)(?:\([^)]*\))?!?:|[a-z]+\(android\)!?:|android:)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
        if (!unrelatedScope) return true;
        bool anyFiles = false, relevant = false;
        var seen = new HashSet<string>(StringComparer.Ordinal);
        for (int page = 1; page <= 30; page++)
        {
            using var document = await GetAsync($"{Api}commits/{commit.Sha}?per_page=100&page={page}", token);
            var root = document.RootElement;
            if (Text(root, "sha") != commit.Sha) throw new InvalidDataException("Windows commit detail identity mismatch.");
            var files = root.GetProperty("files").EnumerateArray().ToArray();
            foreach (var file in files)
            {
                string path = Text(file, "filename");
                if (string.IsNullOrWhiteSpace(path) || !seen.Add(path)) throw new InvalidDataException("Invalid Windows commit file inventory.");
                anyFiles = true;
                relevant |= IsRelevantPath(path);
                // A rename crossing from shared/Windows into Android must not hide its removal.
                if (file.TryGetProperty("previous_filename", out var previous)) relevant |= IsRelevantPath(previous.GetString() ?? "");
            }
            if (files.Length < 100)
            {
                if (anyFiles) return relevant;
                // Empty merge commits have no file evidence. Only explicit unrelated scopes can be discarded.
                return !Regex.IsMatch(commit.Subject, @"^(?:\[verified\]\s*)*(?:(?:docs|ci)(?:\([^)]*\))?!?:|[a-z]+\(android\)!?:|android:)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant);
            }
        }
        throw new InvalidDataException("Windows commit file inventory exceeded the safe paging limit.");
    }

    private static bool IsRelevantPath(string path)
    {
        if (path.StartsWith("android/", StringComparison.Ordinal) || path.StartsWith("apps/android/", StringComparison.Ordinal) || path.StartsWith("docs/", StringComparison.Ordinal) || path.StartsWith(".github/", StringComparison.Ordinal)) return false;
        return !path.EndsWith(".md", StringComparison.OrdinalIgnoreCase) && !path.EndsWith(".mdx", StringComparison.OrdinalIgnoreCase) && path != ".gitignore" && path != ".gitattributes";
    }

    private async Task<WindowsChangeCommit[]> CompareAsync(string from, string to, CancellationToken token)
    {
        var commits = new List<WindowsChangeCommit>();
        var seen = new HashSet<string>(StringComparer.Ordinal);
        int? expected = null;
        for (int page = 1; page <= 10; page++)
        {
            using var document = await GetAsync($"{Api}compare/{from}...{to}?per_page=100&page={page}", token);
            var root = document.RootElement;
            int total = root.GetProperty("total_commits").GetInt32();
            if (Text(root, "status") != "ahead" || root.GetProperty("behind_by").GetInt32() != 0 || root.GetProperty("ahead_by").GetInt32() != total || Text(root.GetProperty("base_commit"), "sha") != from || Text(root.GetProperty("merge_base_commit"), "sha") != from)
                throw new InvalidDataException("Windows publication history is not a forward range.");
            if (total is < 1 or > 1000 || expected is not null && expected != total) throw new InvalidDataException("Windows history is unavailable or exceeds the safe paging limit.");
            expected = total;
            var items = root.GetProperty("commits").EnumerateArray().ToArray();
            if (items.Length != Math.Min(100, total - commits.Count)) throw new InvalidDataException("Incomplete Windows publication history.");
            foreach (var item in items)
            {
                string sha = Text(item, "sha");
                string subject = Text(item.GetProperty("commit"), "message").Split('\n')[0].TrimEnd('\r');
                if (!IsSha(sha) || sha == from || !seen.Add(sha) || string.IsNullOrWhiteSpace(subject)) throw new InvalidDataException("Invalid Windows history commit.");
                commits.Add(new(sha, subject));
            }
            if (commits.Count == total)
            {
                if (commits[^1].Sha != to) throw new InvalidDataException("Windows history head mismatch.");
                return commits.ToArray();
            }
        }
        throw new InvalidDataException("Windows history exceeded the safe paging limit.");
    }

    private async Task<Dictionary<Version, string>> ResolveSourcesAsync(Version installed, Version offered, CancellationToken token)
    {
        var sources = new Dictionary<Version, string>();
        for (int page = 1; page <= 10; page++)
        {
            using var document = await GetAsync($"{Api}releases?per_page=100&page={page}", token);
            var releases = document.RootElement.EnumerateArray().ToArray();
            foreach (var release in releases)
            {
                if (release.GetProperty("draft").GetBoolean()) continue;
                string tag = Text(release, "tag_name");
                bool legacy = tag.StartsWith("windows-v", StringComparison.Ordinal);
                if (!Regex.IsMatch(tag, @"^(?:windows-)?v0\.1\.[1-9][0-9]*$", RegexOptions.CultureInvariant) || !legacy && release.GetProperty("prerelease").GetBoolean()) continue;
                // Use only an observed inventory tag to select candidate manifests; the tag is not source evidence.
                if (!Version.TryParse(tag[(legacy ? 9 : 1)..], out var observedVersion) || observedVersion != installed && observedVersion != offered) continue;
                var assets = release.GetProperty("assets").EnumerateArray().ToArray();
                var manifests = assets.Where(a => Text(a, "name") == "windows-release.json").ToArray();
                if (manifests.Length == 0) continue;
                if (manifests.Length != 1 || assets.GroupBy(a => Text(a, "name"), StringComparer.OrdinalIgnoreCase).Any(g => g.Count() != 1)) throw new InvalidDataException("Ambiguous Windows publication assets.");
                string url = $"{Repository}/releases/download/{tag}/windows-release.json";
                if (Text(manifests[0], "browser_download_url") != url) throw new InvalidDataException("Unexpected Windows manifest origin.");
                using var manifestDocument = await GetAsync(url, token, 65536);
                var manifest = manifestDocument.RootElement;
                if (!Version.TryParse(Text(manifest, "version"), out var version) || version.Major != 0 || version.Minor != 1 || version.Build is < 1 or > 65534 || version.Revision != -1 || version.ToString() != Text(manifest, "version")) throw new InvalidDataException("Invalid Windows publication version.");
                // Publication tags are discovered from the inventory, never manufactured from the requested versions.
                if (version != installed && version != offered) continue;
                string sha = Text(manifest, "sha");
                string manifestTag = Text(manifest, "tag");
                if (!IsSha(sha) || Text(release, "target_commitish") != sha || manifest.GetProperty("count").GetInt32() != version.Build || tag != (legacy ? "windows-v" : "v") + version || manifestTag != tag && manifestTag != "windows-v" + version || Text(manifest, "platform") != "windows-x64" || Text(manifest, "package_id") != "MusicWindows" || Text(manifest, "runtime") != "win-x64" || Text(manifest, "channel") != "win-x64" || Text(manifest, "signing") != "unsigned-preview") throw new InvalidDataException("Windows publication identity mismatch.");
                string[] names = ["MusicWindows-win-x64-Setup.exe", $"MusicWindows-{version}-win-x64-full.nupkg", "releases.win-x64.json", "assets.win-x64.json", "RELEASES-win-x64"];
                var artifacts = manifest.GetProperty("artifacts").EnumerateArray().ToArray();
                if (artifacts.Length != names.Length) throw new InvalidDataException("Incomplete Windows artifact inventory.");
                foreach (string name in names)
                {
                    var matchingAssets = assets.Where(a => Text(a, "name") == name).ToArray();
                    var entries = artifacts.Where(a => Text(a, "name") == name).ToArray();
                    if (matchingAssets.Length != 1 || entries.Length != 1 || Text(matchingAssets[0], "state") != "uploaded" || Text(matchingAssets[0], "browser_download_url") != $"{Repository}/releases/download/{tag}/{name}" || matchingAssets[0].GetProperty("size").GetInt64() <= 0 || entries[0].GetProperty("bytes").GetInt64() != matchingAssets[0].GetProperty("size").GetInt64() || !Regex.IsMatch(Text(entries[0], "sha256"), "^[a-f0-9]{64}$", RegexOptions.CultureInvariant)) throw new InvalidDataException("Windows artifact identity mismatch.");
                }
                using var reference = await GetAsync($"{Api}git/ref/tags/{tag}", token);
                var obj = reference.RootElement.GetProperty("object").Clone();
                var seen = new HashSet<string>(StringComparer.Ordinal);
                while (Text(obj, "type") == "tag")
                {
                    string objectSha = Text(obj, "sha");
                    if (!IsSha(objectSha) || !seen.Add(objectSha) || seen.Count > 16) throw new InvalidDataException("Invalid Windows publication tag chain.");
                    using var annotated = await GetAsync($"{Api}git/tags/{objectSha}", token);
                    obj = annotated.RootElement.GetProperty("object").Clone();
                }
                if (Text(obj, "type") != "commit" || Text(obj, "sha") != sha) throw new InvalidDataException("Windows publication tag/source mismatch.");
                if (sources.TryGetValue(version, out string? existing) && existing != sha) throw new InvalidDataException("Ambiguous Windows publication source.");
                sources[version] = sha;
            }
            if (releases.Length < 100)
            {
                if (!sources.ContainsKey(installed) || !sources.ContainsKey(offered)) throw new InvalidDataException("Windows publication source unavailable.");
                return sources;
            }
        }
        throw new InvalidDataException("Windows publication inventory exceeded the safe paging limit.");
    }

    private async Task<JsonDocument> GetAsync(string url, CancellationToken token, int maximumBytes = 4 * 1024 * 1024)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.UserAgent.ParseAdd("MusicWindows-Changelog/1.0");
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/vnd.github+json"));
        using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, token);
        response.EnsureSuccessStatusCode();
        if (response.Content.Headers.ContentLength > maximumBytes) throw new InvalidDataException("Windows history response exceeded the safe size limit.");
        await using var stream = await response.Content.ReadAsStreamAsync(token);
        using var buffer = new MemoryStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = await stream.ReadAsync(chunk, token)) != 0)
        {
            if (buffer.Length + read > maximumBytes) throw new InvalidDataException("Windows history response exceeded the safe size limit.");
            buffer.Write(chunk, 0, read);
        }
        return JsonDocument.Parse(buffer.ToArray());
    }

    private static string Text(JsonElement element, string property) => element.GetProperty(property).GetString() ?? throw new InvalidDataException("Missing Windows history identity.");
    private static bool IsSha(string sha) => Regex.IsMatch(sha, "^[a-f0-9]{40}$", RegexOptions.CultureInvariant);
    public void Dispose() { if (ownsClient) client.Dispose(); }
}
