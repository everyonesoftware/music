using System.Text.Json;
using System.Text.RegularExpressions;
using Music.Core;
using Velopack;

namespace Music.App;

/// <summary>Validates the precomputed publication snapshot. No compare, commit, or release-body fallback.</summary>
public sealed class WindowsChangelogClient
{
    public const int MaximumManifestBytes = 1024 * 1024;
    private sealed record Segment(Version Version, string Sha, WindowsChangeCommit[] Commits, string[] Improvements, string[] Fixes);
    private readonly Segment[] segments;
    private WindowsChangelogClient(Segment[] segments) => this.segments = segments;

    public static WindowsChangelogClient Read(byte[] bytes, JsonElement publication, VelopackAsset offer)
    {
        Require(bytes.Length is > 0 and <= MaximumManifestBytes);
        using var document = JsonDocument.Parse(bytes, new JsonDocumentOptions { MaxDepth = 32 });
        var root = document.RootElement;
        UniqueProperties(root);
        var version = ParseVersion(Text(root, "version"));
        string sha = Sha(root, "sha"), tag = Text(publication, "tag_name");
        Require(version.ToString() == offer.Version.ToString() && Text(publication, "target_commitish") == sha);
        Require(tag == "v" + version || tag == "windows-v" + version);
        Require(Text(root, "tag") == tag || Text(root, "tag") == "windows-v" + version);
        Require(root.GetProperty("count").GetInt32() == version.Build && Text(root, "platform") == "windows-x64" && Text(root, "package_id") == "MusicWindows" && Text(root, "runtime") == "win-x64" && Text(root, "channel") == "win-x64");
        ValidateArtifacts(root, publication, offer, tag);
        var log = root.GetProperty("changelog");
        Require(log.GetProperty("schema").GetInt32() == 1 && Text(log, "platform") == "windows");
        var head = log.GetProperty("head");
        Require(ParseVersion(Text(head, "version")) == version && Sha(head, "sha") == sha);
        var releases = log.GetProperty("releases").EnumerateArray().ToArray();
        Require(releases.Length is > 0 and <= 65534);
        var segments = new List<Segment>();
        var commitsSeen = new HashSet<string>(StringComparer.Ordinal);
        var sourcesSeen = new HashSet<string>(StringComparer.Ordinal);
        foreach (var entry in releases)
        {
            var releaseVersion = ParseVersion(Text(entry, "version"));
            string releaseSha = Sha(entry, "sha");
            Require((segments.Count == 0 || releaseVersion > segments[^1].Version) && sourcesSeen.Add(releaseSha));
            var commits = new List<WindowsChangeCommit>();
            var improvements = new List<string>();
            var fixes = new List<string>();
            foreach (var item in entry.GetProperty("commits").EnumerateArray())
            {
                string commitSha = Sha(item, "sha"), subject = Text(item, "subject");
                // Git permits empty first lines. Preserve the producer's original verbatim;
                // only non-null user-facing bullet text must be nonblank.
                Require(commitsSeen.Add(commitSha) && !subject.Contains('\n'));
                var kind = item.GetProperty("kind");
                var text = item.GetProperty("text");
                if (kind.ValueKind == JsonValueKind.Null) Require(text.ValueKind == JsonValueKind.Null);
                else
                {
                    string category = kind.GetString() ?? "";
                    Require(category is "improvement" or "fix");
                    string bullet = text.GetString() ?? "";
                    Require(SingleLine(bullet));
                    (category == "fix" ? fixes : improvements).Add(bullet);
                }
                commits.Add(new(commitSha, subject));
            }
            if (segments.Count == 0)
            {
                Require(commits.Count == 0);
                commitsSeen.Add(releaseSha);
            }
            else Require(commits.Count == releaseVersion.Build - segments[^1].Version.Build && commits[^1].Sha == releaseSha);
            segments.Add(new(releaseVersion, releaseSha, commits.ToArray(), improvements.ToArray(), fixes.ToArray()));
        }
        Require(segments[^1].Version == version && segments[^1].Sha == sha);
        return new(segments.ToArray());
    }

    public WindowsChangelog? GetChanges(Version installed)
    {
        int index = Array.FindIndex(segments, s => s.Version == installed);
        if (index < 0 || index == segments.Length - 1) return null;
        var range = segments[(index + 1)..];
        return new(segments[index].Sha, segments[^1].Sha,
            Array.AsReadOnly(range.SelectMany(s => s.Improvements).ToArray()),
            Array.AsReadOnly(range.SelectMany(s => s.Fixes).ToArray()),
            Array.AsReadOnly(range.SelectMany(s => s.Commits).ToArray()));
    }

    private static void ValidateArtifacts(JsonElement root, JsonElement publication, VelopackAsset offer, string tag)
    {
        string[] names = ["MusicWindows-win-x64-Setup.exe", $"MusicWindows-{offer.Version}-win-x64-full.nupkg", "releases.win-x64.json", "assets.win-x64.json", "RELEASES-win-x64"];
        var artifacts = root.GetProperty("artifacts").EnumerateArray().ToArray();
        var assets = publication.GetProperty("assets").EnumerateArray().ToArray();
        Require(artifacts.Length == names.Length);
        foreach (string name in names)
        {
            var artifact = artifacts.Single(a => Text(a, "name") == name);
            var asset = assets.Single(a => Text(a, "name") == name);
            long size = artifact.GetProperty("bytes").GetInt64();
            string hash = Text(artifact, "sha256");
            Require(size > 0 && size == asset.GetProperty("size").GetInt64() && Regex.IsMatch(hash, "^[a-f0-9]{64}$", RegexOptions.CultureInvariant));
            Require(Text(asset, "state") == "uploaded" && Text(asset, "browser_download_url") == $"{WindowsGithubSource.Repository}/releases/download/{tag}/{name}");
            if (name == offer.FileName) Require(size == offer.Size && string.Equals(hash, offer.SHA256, StringComparison.OrdinalIgnoreCase));
        }
    }

    private static Version ParseVersion(string text)
    {
        Require(Version.TryParse(text, out var version) && version.Major == 0 && version.Minor == 1 && version.Build is >= 1 and <= 65534 && version.Revision == -1 && version.ToString() == text);
        return Version.Parse(text);
    }
    private static string Sha(JsonElement element, string name)
    {
        string sha = Text(element, name);
        Require(Regex.IsMatch(sha, "^[a-f0-9]{40}$", RegexOptions.CultureInvariant));
        return sha;
    }
    private static bool SingleLine(string text) => !string.IsNullOrWhiteSpace(text) && !text.Contains('\n') && !text.Contains('\r');
    private static string Text(JsonElement element, string name) => element.GetProperty(name).GetString() ?? throw new InvalidDataException("Missing Windows changelog identity.");
    private static void Require(bool valid) { if (!valid) throw new InvalidDataException("Invalid Windows changelog publication."); }
    private static void UniqueProperties(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.Object)
        {
            var seen = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in element.EnumerateObject()) { Require(seen.Add(property.Name)); UniqueProperties(property.Value); }
        }
        else if (element.ValueKind == JsonValueKind.Array)
            foreach (var item in element.EnumerateArray()) UniqueProperties(item);
    }
}
