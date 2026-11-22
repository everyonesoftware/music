using System.Buffers.Binary;
using System.Globalization;
using System.Net;
using System.Text.Json;
using System.Text.Json.Serialization;
using Music.Core;
using Velopack;
using Velopack.Locators;
using Velopack.Logging;
using Velopack.Sources;

namespace Music.App;

/// <summary>Velopack 1.2.161 GithubSource only reads ten releases. Page explicitly and isolate the Windows feed.</summary>
public sealed class WindowsGithubSource : GithubSource
{
    public const string Repository = "https://github.com/everyonesoftware/music";
    public const string Channel = "win-x64";
    private readonly Dictionary<GithubRelease, string> versions = new();
    private readonly Dictionary<GithubRelease, JsonElement> publications = new();
    private WindowsChangelogClient? changelog;
    private VelopackAsset? manifestOffer;
    private static readonly HttpClient PublicManifestHttp = new() { Timeout = TimeSpan.FromSeconds(30) };
    private static readonly HttpClient PublicInventoryHttp = new(new HttpClientHandler { AutomaticDecompression = DecompressionMethods.All });
    private static readonly TimeSpan MaximumCooldown = TimeSpan.FromHours(1);
    private readonly HttpClient? manifestHttp;
    private readonly HttpClient? inventoryHttp;
    private readonly string? cooldownPath;
    private readonly TimeProvider clock = TimeProvider.System;

    public WindowsGithubSource(IFileDownloader? downloader = null, HttpClient? manifestClient = null) : base(Repository, accessToken: null, prerelease: true, downloader: downloader)
    {
        // Production streams under a byte/time budget. Injected Velopack fixtures may reuse their
        // external boundary; they still pass the same parser byte limit and identity validation.
        manifestHttp = manifestClient ?? (downloader is null ? PublicManifestHttp : null);
        if (downloader is null)
        {
            inventoryHttp = PublicInventoryHttp;
            string localData = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData, Environment.SpecialFolderOption.DoNotVerify);
            if (!string.IsNullOrEmpty(localData))
                cooldownPath = Path.Combine(localData, "EveryoneSoftware", "Music", "windows-update-cooldown");
        }
    }

    internal WindowsGithubSource(IFileDownloader? downloader, HttpClient inventoryHttp, string cooldownPath, TimeProvider clock) : this(downloader)
    {
        this.inventoryHttp = inventoryHttp;
        this.cooldownPath = cooldownPath;
        this.clock = clock;
    }

    private async Task<string> ReadInventoryAsync(string url)
    {
        if (inventoryHttp is null) return await Downloader.DownloadString(url, GetRequestHeaders("application/vnd.github+json"));
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.UserAgent.ParseAdd("MusicWindows-Updates/1.0");
        request.Headers.Accept.ParseAdd("application/vnd.github+json");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        using var response = await inventoryHttp.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, timeout.Token).ConfigureAwait(false);
        if (cooldownPath is { } path && GetCooldownDeadline(response) is { } deadline)
            await Task.Run(() => SaveCooldown(path, deadline)).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadAsStringAsync(timeout.Token).ConfigureAwait(false);
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
        versions.Clear();
        publications.Clear();
        var result = new List<(GithubRelease Release, Version Version, bool MainRelease)>();
        // Bound API use; never silently call a truncated inventory "up to date".
        for (int page = 1; page <= 10; page++)
        {
            string json = await ReadInventoryAsync($"https://api.github.com/repos/everyonesoftware/music/releases?per_page=100&page={page}");
            using var document = JsonDocument.Parse(json);
            var releases = document.RootElement.EnumerateArray().ToArray();
            foreach (var item in releases)
            {
                if (item.GetProperty("draft").GetBoolean()) continue;
                string? tag = item.GetProperty("tag_name").GetString();
                bool mainRelease = tag is not null && tag.StartsWith("v", StringComparison.Ordinal) && !item.GetProperty("prerelease").GetBoolean();
                bool legacyRelease = tag is not null && tag.StartsWith("windows-v", StringComparison.Ordinal);
                if (!mainRelease && !legacyRelease) continue;
                string version = tag![(mainRelease ? 1 : 9)..];
                if (!Version.TryParse(version, out var parsed) || parsed.Major != 0 || parsed.Minor != 1 || parsed.Build is < 1 or > 65534 || parsed.Revision != -1 || version != parsed.ToString()) continue;
                var release = item.Deserialize(WindowsUpdateJsonContext.Default.GithubRelease) ?? throw new InvalidDataException("Missing Windows release.");
                if (!release.Assets.Any(a => a.Name == $"releases.{Channel}.json")) continue;
                // A release's metadata must not redirect us to another tag, repository, or platform.
                string prefix = $"{Repository}/releases/download/{tag}/";
                if (release.Assets.Any(a => !IsNotesAsset(a.Name) && a.BrowserDownloadUrl != prefix + a.Name))
                    throw new InvalidDataException("Unexpected Windows release asset origin.");
                versions.Add(release, version);
                publications.Add(release, item.Clone());
                result.Add((release, parsed, mainRelease));
            }
            // Releases are not guaranteed to be published in numeric version order. Deltas are disabled,
            // so only the highest Windows full-package feed is needed, not every historical feed.
            if (releases.Length < 100)
            {
                var selected = result.OrderByDescending(r => r.Version).ThenByDescending(r => r.MainRelease).Take(1).Select(r => r.Release).ToArray();
                if (selected.Any(r => r.Assets.Where(a => !IsNotesAsset(a.Name)).GroupBy(a => a.Name, StringComparer.OrdinalIgnoreCase).Any(g => g.Count() != 1)))
                    throw new InvalidDataException("Ambiguous Windows release assets.");
                return selected;
            }
        }
        throw new InvalidDataException("GitHub release inventory exceeded the safe paging limit.");
    }

    public WindowsChangelog? GetChangelog(Version installed, VelopackAsset offer) =>
        ReferenceEquals(offer, manifestOffer) ? changelog?.GetChanges(installed) : null;

    private static bool IsNotesAsset(string? name) => string.Equals(name, "windows-release.json", StringComparison.OrdinalIgnoreCase);

    public override async Task<VelopackAssetFeed> GetReleaseFeed(IVelopackLogger logger, string? appId, string channel, Guid? stagingId = null, VelopackAsset? latestLocalRelease = null)
    {
        changelog = null;
        manifestOffer = null;
        if (channel != Channel || appId is not null && appId != "MusicWindows")
            throw new InvalidDataException("Unexpected Windows update identity.");
        var feed = await base.GetReleaseFeed(logger, appId, channel, stagingId, latestLocalRelease);
        var safe = new List<VelopackAsset>();
        foreach (var asset in feed.Assets)
        {
            if (asset.PackageId != "MusicWindows") continue;
            if (asset is not GitBaseAsset entry || !versions.TryGetValue(entry.Release, out string? version) || asset.Version.ToString() != version || asset.Type != VelopackAssetType.Full || asset.FileName != $"MusicWindows-{version}-{Channel}-full.nupkg")
                throw new InvalidDataException("Unexpected Windows update package.");
            if (!entry.Release.Assets.Any(a => a.Name == asset.FileName))
                throw new InvalidDataException("Missing Windows update package.");
            safe.Add(asset);
        }
        if (versions.Count > 0 && safe.Count != 1)
            throw new InvalidDataException("Windows release must contain exactly one matching full package.");
        if (safe.SingleOrDefault() is GitBaseAsset selected)
        {
            // Optional metadata must never invalidate an otherwise valid downloadable package.
            try
            {
                var publication = publications[selected.Release];
                var notes = publication.GetProperty("assets").EnumerateArray().Single(a => IsNotesAsset(a.GetProperty("name").GetString()));
                string url = notes.GetProperty("browser_download_url").GetString()!;
                string tag = publication.GetProperty("tag_name").GetString()!;
                if (notes.GetProperty("name").GetString() != "windows-release.json" || notes.GetProperty("state").GetString() != "uploaded" || notes.GetProperty("size").GetInt64() is <= 0 or > WindowsChangelogClient.MaximumManifestBytes || url != $"{Repository}/releases/download/{tag}/windows-release.json")
                    throw new InvalidDataException("Invalid Windows changelog asset.");
                var bytes = await ReadManifestAsync(url);
                changelog = WindowsChangelogClient.Read(bytes, publication, selected);
                manifestOffer = selected;
            }
            catch { }
        }
        return new VelopackAssetFeed { Assets = safe.ToArray() };
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
        if (response.Content.Headers.ContentLength > WindowsChangelogClient.MaximumManifestBytes)
            throw new InvalidDataException("Windows manifest exceeds the size limit.");
        await using var stream = await response.Content.ReadAsStreamAsync(timeout.Token);
        using var buffer = new MemoryStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = await stream.ReadAsync(chunk.AsMemory(), timeout.Token)) != 0)
        {
            if (buffer.Length + read > WindowsChangelogClient.MaximumManifestBytes)
                throw new InvalidDataException("Windows manifest exceeds the size limit.");
            buffer.Write(chunk, 0, read);
        }
        return buffer.ToArray();
    }
}

[JsonSerializable(typeof(GithubRelease))]
internal partial class WindowsUpdateJsonContext : JsonSerializerContext { }

/// <summary>Lazy construction defers native locator work until a check. Download never implies apply.</summary>
public sealed class WindowsUpdateBackend : IWindowsUpdateBackend
{
    private readonly WindowsGithubSource source;
    private readonly Lazy<UpdateManager> manager;
    public WindowsUpdateBackend() : this(new WindowsGithubSource()) { }

    internal WindowsUpdateBackend(WindowsGithubSource source, IVelopackLocator? locator = null)
    {
        this.source = source;
        manager = new(() => new UpdateManager(source, new UpdateOptions
        {
            ExplicitChannel = WindowsGithubSource.Channel,
            AllowVersionDowngrade = false,
            MaximumDeltasBeforeFallback = -1
        }, locator));
    }
    private UpdateInfo? offered;
    private bool downloaded;
    public bool IsInstalled => manager.Value.IsInstalled;
    public bool IsStartupCheckDeferred => source.IsStartupCheckDeferred;
    public Version CurrentVersion => Version.Parse(manager.Value.CurrentVersion?.ToString() ?? "0.0.0");
    public WindowsChangelog? Changelog { get; private set; }

    public async Task<Version?> CheckAsync()
    {
        offered = null;
        Changelog = null;
        downloaded = false;
        var update = await manager.Value.CheckForUpdatesAsync();
        if (update is null) return null;
        var version = Version.Parse(update.TargetFullRelease.Version.ToString());
        if (version <= CurrentVersion || update.IsDowngrade || update.TargetFullRelease.PackageId != "MusicWindows")
            throw new InvalidDataException("Unsafe Windows update target.");
        offered = update;
        Changelog = source.GetChangelog(CurrentVersion, update.TargetFullRelease);
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
        await manager.Value.DownloadUpdatesAsync(RequireOffer(version), progress, token);
        token.ThrowIfCancellationRequested();
        downloaded = true;
    }

    public void ApplyAndRestart(Version version)
    {
        var update = RequireOffer(version);
        if (!downloaded) throw new InvalidOperationException("Download the Windows update first.");
        manager.Value.ApplyUpdatesAndRestart(update.TargetFullRelease, Array.Empty<string>());
    }
}
