using System.Buffers.Binary;
using System.Globalization;
using System.Net;
using System.Text.Json;
using Music.Core;
using Velopack;
using Velopack.Exceptions;
using Velopack.Locators;
using Velopack.Logging;
using Velopack.Sources;

namespace Music.App;

/// <summary>Resolve the Windows release from the platform index, then use Velopack for its feed and package.</summary>
public sealed class WindowsGithubSource : GithubSource
{
    public const string Repository = "https://github.com/everyonesoftware/music";
    public const string Channel = "win-x64";
    private const string IndexName = "latest-versions.json";
    private const string IndexUrl = Repository + "/releases/latest/download/" + IndexName;
    private const string TaggedAssetPath = "/everyonesoftware/music/releases/download/";
    private const string TagApi = "https://api.github.com/repos/everyonesoftware/music/releases/tags/";
    private const string FeedName = "releases." + Channel + ".json";
    private const int MaximumIndexBytes = 16 * 1024;
    private const int MaximumReleaseBytes = 1024 * 1024;
    private const int MaximumIndexRedirects = 5;
    private JsonElement publication;
    private WindowsChangelogClient? changelog;
    private VelopackAsset? manifestOffer;
    private static readonly HttpClient PublicManifestHttp = new() { Timeout = TimeSpan.FromSeconds(30) };
    private static readonly HttpClient PublicDiscoveryHttp = new(new HttpClientHandler
    {
        AllowAutoRedirect = false,
        AutomaticDecompression = DecompressionMethods.All
    });
    private static readonly TimeSpan MaximumCooldown = TimeSpan.FromHours(1);
    private readonly HttpClient? manifestHttp;
    private readonly HttpClient? discoveryHttp;
    private readonly string? cooldownPath;
    private readonly TimeProvider clock = TimeProvider.System;

    public WindowsGithubSource(IFileDownloader? downloader = null, HttpClient? manifestClient = null) : base(Repository, accessToken: null, prerelease: false, downloader: downloader)
    {
        // Production streams under a byte/time budget. Injected Velopack fixtures may reuse their
        // external boundary; they still pass the same parser byte limit and identity validation.
        manifestHttp = manifestClient ?? (downloader is null ? PublicManifestHttp : null);
        if (downloader is null)
        {
            discoveryHttp = PublicDiscoveryHttp;
            string localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData, Environment.SpecialFolderOption.DoNotVerify);
            if (!string.IsNullOrEmpty(localData))
                cooldownPath = Path.Combine(localData, "EveryoneSoftware", "Music", "windows-update-cooldown");
        }
    }

    internal WindowsGithubSource(IFileDownloader? downloader, HttpClient discoveryHttp, string? cooldownPath, TimeProvider clock) : this(downloader)
    {
        this.discoveryHttp = discoveryHttp;
        this.cooldownPath = cooldownPath;
        this.clock = clock;
    }

    private async Task<byte[]> ReadDiscoveryAsync(string url, string accept, int maximumBytes)
    {
        if (discoveryHttp is null)
        {
            var bytes = await Downloader.DownloadBytes(url, GetRequestHeaders(accept));
            if (bytes.Length > maximumBytes) throw new InvalidDataException("Windows update metadata exceeds the size limit.");
            return bytes;
        }
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        var current = new Uri(url);
        for (int hop = 0; ; hop++)
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, current);
            request.Headers.UserAgent.ParseAdd("MusicWindows-Updates/1.0");
            request.Headers.Accept.ParseAdd(accept);
            using var response = await discoveryHttp.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
            if (response.StatusCode is HttpStatusCode.MovedPermanently or HttpStatusCode.Found or HttpStatusCode.SeeOther
                or HttpStatusCode.TemporaryRedirect or HttpStatusCode.PermanentRedirect)
            {
                if (url != IndexUrl || hop >= MaximumIndexRedirects || response.Headers.Location is not { } location
                    || !Uri.TryCreate(current, location, out var next) || !IsIndexRedirectAllowed(next, hop))
                    throw new InvalidDataException("Unexpected Windows update metadata redirect.");
                current = next;
                continue;
            }
            if (cooldownPath is { } path && GetCooldownDeadline(response) is { } deadline)
                await Task.Run(() => SaveCooldown(path, deadline)).ConfigureAwait(false);
            response.EnsureSuccessStatusCode();
            return await ReadBoundedAsync(response.Content, maximumBytes, timeout.Token).ConfigureAwait(false);
        }
    }

    private static bool IsIndexRedirectAllowed(Uri next, int hop)
    {
        if (next.Scheme != Uri.UriSchemeHttps || !next.IsDefaultPort || next.UserInfo.Length != 0 || next.Fragment.Length != 0)
            return false;
        if (hop != 0) return next.IdnHost is "release-assets.githubusercontent.com" or "objects.githubusercontent.com";

        const string suffix = "/" + IndexName;
        string path = next.AbsolutePath;
        if (next.IdnHost != "github.com" || next.Query.Length != 0
            || !path.StartsWith(TaggedAssetPath, StringComparison.Ordinal) || !path.EndsWith(suffix, StringComparison.Ordinal)
            || path.Length <= TaggedAssetPath.Length + suffix.Length)
            return false;
        return IsCanonicalStableTag(path[TaggedAssetPath.Length..^suffix.Length], int.MaxValue);
    }

    private long? GetCooldownDeadline(HttpResponseMessage response)
    {
        if (response.StatusCode is not (HttpStatusCode.Forbidden or HttpStatusCode.TooManyRequests)) return null;
        var headers = response.Headers;
        var retryAfter = headers.RetryAfter;
        bool exhausted = headers.TryGetValues("X-RateLimit-Remaining", out var remaining) && remaining.FirstOrDefault() == "0";
        if (response.StatusCode == HttpStatusCode.Forbidden && retryAfter is null && !exhausted) return null;

        var now = clock.GetUtcNow();
        var delay = retryAfter?.Delta ?? (retryAfter?.Date is { } date ? date - now : TimeSpan.Zero);
        if (exhausted && headers.TryGetValues("X-RateLimit-Reset", out var values)
            && long.TryParse(values.FirstOrDefault(), NumberStyles.None, CultureInfo.InvariantCulture, out long reset)
            && reset <= DateTimeOffset.MaxValue.ToUnixTimeSeconds())
        {
            var untilReset = DateTimeOffset.FromUnixTimeSeconds(reset) - now;
            if (untilReset > delay) delay = untilReset;
        }
        if (delay <= TimeSpan.Zero) delay = TimeSpan.FromMinutes(1);
        return now.UtcTicks + Math.Min(delay.Ticks, MaximumCooldown.Ticks);
    }

    internal bool IsStartupCheckDeferred => cooldownPath is { } path && ReadActiveDeadline(path) is not null;

    private long? ReadActiveDeadline(string path)
    {
        try
        {
            using var file = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete);
            Span<byte> bytes = stackalloc byte[sizeof(long) + 1];
            if (file.ReadAtLeast(bytes, bytes.Length, throwOnEndOfStream: false) != sizeof(long)) return null;
            long deadline = BinaryPrimitives.ReadInt64LittleEndian(bytes);
            long now = clock.GetUtcNow().UtcTicks;
            return deadline > now && deadline <= now + MaximumCooldown.Ticks ? deadline : null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { return null; }
    }

    private void SaveCooldown(string path, long deadline)
    {
        // Keep only the deadline, not the HTTP payload, headers or any account metadata.
        string temp = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            if (ReadActiveDeadline(path) >= deadline) return;
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            byte[] bytes = new byte[sizeof(long)];
            BinaryPrimitives.WriteInt64LittleEndian(bytes, deadline);
            File.WriteAllBytes(temp, bytes);
            File.Move(temp, path, overwrite: true);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            try { File.Delete(temp); }
            catch (Exception cleanup) when (cleanup is IOException or UnauthorizedAccessException) { }
        }
    }

    protected override async Task<GithubRelease[]> GetReleases(bool includePrereleases)
    {
        using var index = JsonDocument.Parse(await ReadDiscoveryAsync(IndexUrl, "application/json", MaximumIndexBytes),
            new JsonDocumentOptions { AllowDuplicateProperties = false, MaxDepth = 4 });
        var root = index.RootElement;
        if (root.ValueKind != JsonValueKind.Object || root.GetPropertyCount() != 3
            || !root.TryGetProperty("schema", out var schema) || schema.ValueKind != JsonValueKind.Number
            || !schema.TryGetInt32(out int revision) || revision != 1)
            throw new InvalidDataException("Unexpected Windows update index schema.");
        ParseIndexTag(root, "android", int.MaxValue);
        string tag = ParseIndexTag(root, "windows", 65534);

        using var document = JsonDocument.Parse(await ReadDiscoveryAsync(TagApi + tag, "application/vnd.github+json", MaximumReleaseBytes));
        var item = document.RootElement;
        // Scope duplicate validation so optional notes cannot poison an otherwise valid release.
        ValidateUniqueProperties(item, releaseRoot: true);
        if (item.ValueKind != JsonValueKind.Object || ReadText(item, "tag_name") != tag
            || !item.TryGetProperty("draft", out var draft) || draft.ValueKind != JsonValueKind.False
            || !item.TryGetProperty("prerelease", out var prerelease) || prerelease.ValueKind != JsonValueKind.False
            || !item.TryGetProperty("published_at", out var published) || published.ValueKind != JsonValueKind.String
            || !published.TryGetDateTimeOffset(out var publishedAt)
            || !item.TryGetProperty("assets", out var assets) || assets.ValueKind != JsonValueKind.Array)
            throw new InvalidDataException("Expected the indexed stable Windows release.");

        var verifiedAssets = new List<GithubReleaseAsset>();
        var names = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        string prefix = $"{Repository}/releases/download/{tag}/";
        foreach (var asset in assets.EnumerateArray())
        {
            if (asset.ValueKind != JsonValueKind.Object || ReadText(asset, "name") is not { Length: > 0 } name)
                throw new InvalidDataException("Invalid Windows release asset.");
            // Optional changelog metadata does not participate in Velopack asset resolution.
            if (IsNotesAsset(name)) continue;
            string url = prefix + name;
            if (ReadText(asset, "browser_download_url") != url)
                throw new InvalidDataException("Unexpected Windows release asset origin.");
            if (!names.Add(name)) throw new InvalidDataException("Ambiguous Windows release assets.");
            verifiedAssets.Add(new GithubReleaseAsset { Name = name, BrowserDownloadUrl = url });
        }
        if (!verifiedAssets.Any(a => a.Name == FeedName) || !verifiedAssets.Any(a => a.Name == FullPackageName(tag[1..])))
            throw new InvalidDataException("Missing Windows update feed or package on the indexed release.");

        publication = item.Clone();
        return [new GithubRelease { Name = tag, PublishedAt = publishedAt.UtcDateTime, Assets = verifiedAssets.ToArray() }];
    }

    private static string? ReadText(JsonElement item, string property) =>
        item.TryGetProperty(property, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static string ParseIndexTag(JsonElement index, string platform, int maximumBuild)
    {
        string? tag = ReadText(index, platform);
        if (tag is null || !IsCanonicalStableTag(tag, maximumBuild))
            throw new InvalidDataException("Invalid stable tag in the Windows update index.");
        return tag;
    }

    private static bool IsCanonicalStableTag(string tag, int maximumBuild) =>
        tag.StartsWith("v0.1.", StringComparison.Ordinal) && Version.TryParse(tag.AsSpan(1), out var version)
        && version.Revision == -1 && version.Build >= 1 && version.Build <= maximumBuild && tag == "v" + version;

    private static string FullPackageName(string version) => $"MusicWindows-{version}-{Channel}-full.nupkg";

    public WindowsChangelog? GetChangelog(Version installed, VelopackAsset offer) =>
        ReferenceEquals(offer, manifestOffer) ? changelog?.GetChanges(installed) : null;

    private static bool IsNotesAsset(string? name) => string.Equals(name, "windows-release.json", StringComparison.OrdinalIgnoreCase);

    private static bool IsOnlyNotesAsset(JsonElement asset)
    {
        if (asset.ValueKind != JsonValueKind.Object) return false;
        bool foundName = false;
        foreach (var property in asset.EnumerateObject())
        {
            if (!property.NameEquals("name")) continue;
            // Conflicting names must never hide an ambiguity in the required asset inventory.
            if (property.Value.ValueKind != JsonValueKind.String || !IsNotesAsset(property.Value.GetString())) return false;
            foundName = true;
        }
        return foundName;
    }

    private static void ValidateUniqueProperties(JsonElement item, bool releaseRoot = false)
    {
        if (item.ValueKind == JsonValueKind.Object)
        {
            var names = new HashSet<string>(StringComparer.Ordinal);
            foreach (var property in item.EnumerateObject())
            {
                if (!names.Add(property.Name)) throw new JsonException("Duplicate Windows release metadata property.");
                if (releaseRoot && property.NameEquals("assets") && property.Value.ValueKind == JsonValueKind.Array)
                {
                    foreach (var asset in property.Value.EnumerateArray())
                        if (!IsOnlyNotesAsset(asset)) ValidateUniqueProperties(asset);
                }
                else ValidateUniqueProperties(property.Value);
            }
        }
        else if (item.ValueKind == JsonValueKind.Array)
            foreach (var value in item.EnumerateArray()) ValidateUniqueProperties(value);
    }

    public override async Task<VelopackAssetFeed> GetReleaseFeed(IVelopackLogger logger, string? appId, string channel, Guid? stagingId = null, VelopackAsset? latestLocalRelease = null)
    {
        changelog = null;
        manifestOffer = null;
        publication = default;
        if (channel != Channel || appId is not null && appId != "MusicWindows")
            throw new InvalidDataException("Unexpected Windows update identity.");
        var feed = await base.GetReleaseFeed(logger, appId, channel, stagingId, latestLocalRelease);
        string version = publication.GetProperty("tag_name").GetString()![1..];
        var safe = new List<VelopackAsset>();
        foreach (var asset in feed.Assets)
        {
            if (asset.PackageId != "MusicWindows") continue;
            if (asset is not GitBaseAsset || asset.Version.ToString() != version || asset.Type != VelopackAssetType.Full || asset.FileName != FullPackageName(version))
                throw new InvalidDataException("Unexpected Windows update package.");
            safe.Add(asset);
        }
        if (safe.Count != 1)
            throw new InvalidDataException("Windows release must contain exactly one matching full package.");
        var selected = safe[0];
        // Optional metadata must never invalidate an otherwise valid downloadable package.
        try
        {
            var notes = publication.GetProperty("assets").EnumerateArray().Single(a => IsNotesAsset(ReadText(a, "name")));
            ValidateUniqueProperties(notes);
            string url = notes.GetProperty("browser_download_url").GetString()!;
            string tag = publication.GetProperty("tag_name").GetString()!;
            if (ReadText(notes, "name") != "windows-release.json" || ReadText(notes, "state") != "uploaded" || notes.GetProperty("size").GetInt64() is <= 0 or > WindowsChangelogClient.MaximumManifestBytes || url != $"{Repository}/releases/download/{tag}/windows-release.json")
                throw new InvalidDataException("Invalid Windows changelog asset.");
            var bytes = await ReadManifestAsync(url);
            changelog = WindowsChangelogClient.Read(bytes, publication, selected);
            manifestOffer = selected;
        }
        catch { }
        return new VelopackAssetFeed { Assets = [selected] };
    }

    private async Task<byte[]> ReadManifestAsync(string url)
    {
        if (manifestHttp is null) return await Downloader.DownloadBytes(url, GetRequestHeaders("application/json"));
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.UserAgent.ParseAdd("MusicWindows-Updates/1.0");
        request.Headers.Accept.ParseAdd("application/json");
        using var response = await manifestHttp.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token);
        response.EnsureSuccessStatusCode();
        return await ReadBoundedAsync(response.Content, WindowsChangelogClient.MaximumManifestBytes, timeout.Token);
    }

    private static async Task<byte[]> ReadBoundedAsync(HttpContent content, int maximumBytes, CancellationToken token)
    {
        if (content.Headers.ContentLength > maximumBytes)
            throw new InvalidDataException("Windows update metadata exceeds the size limit.");
        await using var stream = await content.ReadAsStreamAsync(token);
        using var buffer = new MemoryStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = await stream.ReadAsync(chunk.AsMemory(0, Math.Min(chunk.Length, maximumBytes - (int)buffer.Length + 1)), token)) != 0)
        {
            if (buffer.Length + read > maximumBytes)
                throw new InvalidDataException("Windows update metadata exceeds the size limit.");
            buffer.Write(chunk, 0, read);
        }
        return buffer.ToArray();
    }
}

/// <summary>Lazy construction defers native locator work until a check. Download never implies apply.</summary>
public sealed class WindowsUpdateBackend : IWindowsUpdateBackend
{
    private readonly WindowsGithubSource source;
    private readonly Lazy<PackageUpdateManager> manager;
    public WindowsUpdateBackend() : this(new WindowsGithubSource()) { }

    internal WindowsUpdateBackend(WindowsGithubSource source, IVelopackLocator? locator = null)
    {
        this.source = source;
        manager = new(() => new PackageUpdateManager(source, new UpdateOptions
        {
            ExplicitChannel = WindowsGithubSource.Channel,
            AllowVersionDowngrade = false,
            MaximumDeltasBeforeFallback = -1
        }, locator));
    }
    private UpdateInfo? offered;
    private VelopackAsset? staged;
    private bool downloaded;
    public bool IsInstalled => manager.Value.IsInstalled;
    public bool IsStartupCheckDeferred => source.IsStartupCheckDeferred;
    public Version CurrentVersion => Version.Parse(manager.Value.CurrentVersion?.ToString() ?? "0.0.0");
    public WindowsChangelog? Changelog { get; private set; }

    public async Task<Version?> CheckAsync()
    {
        var update = await manager.Value.CheckForUpdatesAsync();
        if (update is null)
        {
            offered = null;
            Changelog = null;
            downloaded = false;
            return null;
        }
        var target = update.TargetFullRelease;
        var version = Version.Parse(target.Version.ToString());
        if (version <= CurrentVersion || update.IsDowngrade || target.PackageId != "MusicWindows")
            throw new InvalidDataException("Unsafe Windows update target.");

        // An intervening check can replace the offer without removing the verified package from disk.
        bool sameVersion = staged is not null && staged.Version == target.Version;
        if (staged is not null && sameVersion && (staged.FileName != target.FileName || staged.Size != target.Size
            || !string.Equals(staged.SHA256, target.SHA256, StringComparison.OrdinalIgnoreCase)
            || !string.Equals(staged.SHA1, target.SHA1, StringComparison.OrdinalIgnoreCase)))
            throw new InvalidDataException("The staged Windows release differs from the published package.");

        var changelog = source.GetChangelog(CurrentVersion, target);
        offered = update;
        Changelog = changelog;
        downloaded = downloaded && sameVersion;
        return version;
    }

    private UpdateInfo RequireOffer(Version version)
    {
        if (offered is null || offered.TargetFullRelease.Version.ToString() != version.ToString() || version <= CurrentVersion)
            throw new InvalidOperationException("No matching newer Windows update.");
        return offered;
    }

    public async Task DownloadAsync(Version version, Action<int> progress, CancellationToken token)
    {
        downloaded = false;
        var update = RequireOffer(version);
        await manager.Value.DownloadUpdatesAsync(update, progress, token);
        token.ThrowIfCancellationRequested();
        // Velopack can return without checking an already-cached package. Verify before granting Ready.
        await manager.Value.VerifyStagedPackageAsync(update.TargetFullRelease, token);
        token.ThrowIfCancellationRequested();
        staged = update.TargetFullRelease;
        downloaded = true;
    }

    public void ApplyAndRestart(Version version)
    {
        var update = RequireOffer(version);
        if (!downloaded) throw new InvalidOperationException("Download the Windows update first.");
        manager.Value.ApplyUpdatesAndRestart(update.TargetFullRelease, Array.Empty<string>());
    }

    private sealed class PackageUpdateManager(IUpdateSource source, UpdateOptions options, IVelopackLocator? locator)
        : UpdateManager(source, options, locator)
    {
        public async Task VerifyStagedPackageAsync(VelopackAsset asset, CancellationToken token)
        {
            using var updateLock = await AcquireUpdateLock().ConfigureAwait(false);
            token.ThrowIfCancellationRequested();
            // The feed validator requires the exact, version-derived package filename.
            string path = Path.Combine(Locator.PackagesDir!, asset.FileName);
            try { await VerifyPackageChecksumAsync(asset, path).ConfigureAwait(false); }
            catch (ChecksumFailedException)
            {
                // Clear only the rejected package so the next explicit Download can fetch fresh bytes.
                File.Delete(path);
                throw;
            }
        }
    }
}
