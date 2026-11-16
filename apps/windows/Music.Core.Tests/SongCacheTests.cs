using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class SongCacheTests : IDisposable
{
    private readonly string directory = Path.Combine(Path.GetTempPath(), "music-cache-" + Guid.NewGuid().ToString("N"));
    private static readonly Song Track = new("song", "Title", "Artist", "Album", null, 90);
    [Fact]
    public async Task Complete_song_reopens_without_network_and_is_account_scoped()
    {
        using (var cache = new SongCache(directory, "account"))
        {
            Assert.Null(cache.Find(Track.Id));
            await cache.SaveAsync(Track, false, async (output, token) => { await output.WriteAsync(new byte[] { 1, 2, 3 }, token); });
            Assert.Equal(new byte[] { 1, 2, 3 }, File.ReadAllBytes(cache.Find(Track.Id)!));
        }
        using var reopened = new SongCache(directory, "account");
        Assert.NotNull(reopened.Find(Track.Id));
        Assert.Equal(Track, reopened.Recent().Single().Song);
        using var other = new SongCache(directory, "other");
        Assert.Null(other.Find(Track.Id));
    }
    [Fact]
    public async Task Deleted_or_truncated_file_cannot_keep_a_complete_badge()
    {
        using var cache = new SongCache(directory, "account");
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[4], t).AsTask());
        var path = cache.Find(Track.Id)!;
        File.WriteAllBytes(path, new byte[1]);
        Assert.Null(cache.Find(Track.Id));
        Assert.False(cache.Recent().Single().Complete);
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[] { 8, 9 }, t).AsTask());
        Assert.Equal(new byte[] { 8, 9 }, File.ReadAllBytes(cache.Find(Track.Id)!));
    }
    [Fact]
    public async Task Interrupted_process_reconciles_partial_bytes_and_removes_unindexed_files()
    {
        using (var cache = new SongCache(directory, "account"))
            await Assert.ThrowsAsync<IOException>(() => cache.SaveAsync(Track, false, async (s, t) => { await s.WriteAsync(new byte[5], t); throw new IOException(); }));
        var account = Directory.GetDirectories(directory).Single();
        var partial = Directory.GetFiles(account, "*.partial").Single();
        File.WriteAllBytes(partial, new byte[9]);
        File.WriteAllBytes(Path.Combine(account, "orphan.media"), new byte[4]);
        using var reopened = new SongCache(directory, "account");
        Assert.Equal(9, reopened.Recent().Single().Bytes);
        Assert.False(File.Exists(Path.Combine(account, "orphan.media")));
    }
    [Fact]
    public async Task Disposed_account_lease_cannot_expose_metadata_or_local_hits()
    {
        var cache = new SongCache(directory, "account");
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[4], t).AsTask());
        cache.Dispose();
        Assert.Null(cache.Find(Track.Id));
        Assert.Empty(cache.Recent());
    }
    [Fact]
    public async Task Media_mime_type_survives_reopen_without_relying_on_filename_extension()
    {
        using (var cache = new SongCache(directory, "account"))
            await cache.SaveMediaAsync(Track, false, async (s, t) => { await s.WriteAsync(new byte[4], t); return "audio/flac"; });
        using var reopened = new SongCache(directory, "account");
        Assert.Equal("audio/flac", reopened.Recent().Single().ContentType);
    }
    [Fact]
    public async Task Readable_complete_song_still_plays_when_recency_metadata_cannot_be_written()
    {
        using var cache = new SongCache(directory, "account");
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[4], t).AsTask());
        Directory.CreateDirectory(Path.Combine(Directory.GetDirectories(directory).Single(), "index.json.tmp"));
        Assert.NotNull(cache.Find(Track.Id));
        using var reopened = new SongCache(directory, "account");
        Assert.NotNull(reopened.Find(Track.Id));
    }
    [Fact]
    public void Pin_intent_survives_restart_before_a_download_starts()
    {
        using (var cache = new SongCache(directory, "account")) cache.Pin(new[] { Track, Track with { Id = "two" } });
        using var reopened = new SongCache(directory, "account");
        Assert.Equal(2, reopened.Recent().Count);
        Assert.All(reopened.Recent(), x => { Assert.True(x.Pinned); Assert.False(x.Complete); });
        reopened.ClearAutomatic([]);
        Assert.Equal(2, reopened.Recent().Count);
    }
    [Fact]
    public async Task Partial_failure_is_never_available_offline_and_retry_replaces_it()
    {
        using var cache = new SongCache(directory, "account");
        await Assert.ThrowsAsync<IOException>(() => cache.SaveAsync(Track, false, async (output, token) =>
        { await output.WriteAsync(new byte[] { 1 }, token); throw new IOException("Interrupted"); }));
        Assert.Null(cache.Find(Track.Id));
        Assert.False(cache.Recent().Single().Complete);
        await cache.SaveAsync(Track, false, (output, token) => output.WriteAsync(new byte[] { 2, 3 }, token).AsTask());
        Assert.Equal(new byte[] { 2, 3 }, File.ReadAllBytes(cache.Find(Track.Id)!));
    }
    [Fact]
    public async Task Lru_preserves_active_recent_and_pins_and_clear_only_removes_automatic()
    {
        using var cache = new SongCache(directory, "account");
        foreach (var id in new[] { "old", "recent", "active", "pin" })
            await cache.SaveAsync(Track with { Id = id }, id == "pin", (s, t) => s.WriteAsync(new byte[4], t).AsTask());
        cache.Trim(4, new[] { "active", "recent" });
        Assert.Null(cache.Find("old"));
        Assert.NotNull(cache.Find("active"));
        Assert.NotNull(cache.Find("recent"));
        cache.ClearAutomatic(new[] { "active" });
        Assert.Null(cache.Find("recent"));
        Assert.NotNull(cache.Find("pin"));
        cache.RemoveDownload("pin");
        Assert.Null(cache.Find("pin"));
        cache.Purge();
        Assert.Empty(cache.Recent());
    }
    [Fact]
    public async Task Old_worker_trim_cannot_evict_selected_local_song_while_open_is_pending()
    {
        using var cache = new SongCache(directory, "account");
        foreach (var id in new[] { "previous", "selected", "unprotected" })
            await cache.SaveAsync(Track with { Id = id }, false, (s, t) => s.WriteAsync(new byte[] { 4, 5 }, t).AsTask());
        string[] capturedProtection = ["previous"];
        var downloading = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var finishDownload = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var openPending = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var finishOpen = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var oldWorker = Task.Run(async () =>
        {
            await cache.SaveAsync(Track with { Id = "late" }, false, async (s, t) =>
            {
                downloading.SetResult();
                await finishDownload.Task.WaitAsync(TimeSpan.FromSeconds(10));
                await s.WriteAsync(new byte[] { 6, 7 }, t);
            });
            // Model a worker already past its cancellation check, with stale captured IDs.
            cache.Trim(0, capturedProtection);
        });
        await downloading.Task.WaitAsync(TimeSpan.FromSeconds(10));
        cache.SetProtectedSongs(["selected", "previous"]);
        var selection = Task.Run(async () =>
        {
            string path = cache.Find("selected")!;
            openPending.SetResult();
            await finishOpen.Task.WaitAsync(TimeSpan.FromSeconds(10));
            return await File.ReadAllBytesAsync(path);
        });
        await openPending.Task.WaitAsync(TimeSpan.FromSeconds(10));
        try
        {
            finishDownload.SetResult();
            await oldWorker.WaitAsync(TimeSpan.FromSeconds(10));
        }
        finally { finishOpen.TrySetResult(); }
        Assert.Equal(new byte[] { 4, 5 }, await selection.WaitAsync(TimeSpan.FromSeconds(10)));
        Assert.NotNull(cache.Find("previous"));
        Assert.Null(cache.Find("unprotected"));
        Assert.Null(cache.Find("late"));
    }
    [Fact]
    public async Task Automatic_clear_honors_live_selection_and_replaced_protection_releases_old_songs()
    {
        using var cache = new SongCache(directory, "account");
        foreach (var id in new[] { "active", "previous", "old", "pin" })
            await cache.SaveAsync(Track with { Id = id }, id == "pin", (s, t) => s.WriteAsync(new byte[2], t).AsTask());
        cache.SetProtectedSongs(["active", "previous"]);
        await Task.Run(() => cache.ClearAutomatic([]));
        Assert.NotNull(cache.Find("active"));
        Assert.NotNull(cache.Find("previous"));
        Assert.Null(cache.Find("old"));
        Assert.NotNull(cache.Find("pin"));
        cache.SetProtectedSongs(["active"]);
        cache.Trim(0, []);
        Assert.Null(cache.Find("previous"));
        Assert.NotNull(cache.Find("active"));
        cache.SetProtectedSongs([]);
        cache.ClearAutomatic([]);
        Assert.Null(cache.Find("active"));
        Assert.NotNull(cache.Find("pin"));
    }
    public void Dispose() { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
}
