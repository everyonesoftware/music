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
    public async Task Old_worker_trim_preserves_sync_selection_while_queued_local_lookup_is_delayed()
    {
        using var cache = new SongCache(directory, "account");
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[] { 4, 5 }, t).AsTask());
        string path = cache.Find(Track.Id)!;
        var downloading = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var finishDownload = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var lookupBlocked = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var finishLookup = new ManualResetEventSlim();
        var storageWork = new BackgroundStorage();
        var predecessor = storageWork.RunAsync(() =>
        {
            lookupBlocked.SetResult();
            if (!finishLookup.Wait(TimeSpan.FromSeconds(10))) throw new TimeoutException();
        });
        var oldWorker = Task.Run(async () =>
        {
            await cache.SaveAsync(Track with { Id = "late" }, false, async (s, t) =>
            {
                downloading.SetResult();
                await finishDownload.Task.WaitAsync(TimeSpan.FromSeconds(10));
                await s.WriteAsync(new byte[] { 6, 7 }, t);
            });
            cache.Trim(0, ["previous"]); // Already past cancellation; stale captured IDs.
        });
        Task<string?>? lookup = null;
        try
        {
            await downloading.Task.WaitAsync(TimeSpan.FromSeconds(10));
            await lookupBlocked.Task.WaitAsync(TimeSpan.FromSeconds(10));
            await WhileStorageGateHeld(cache, () => cache.SetProtectedSongs([Track.Id, "previous"]));
            lookup = storageWork.RunAsync(() => cache.Find(Track.Id));
            finishDownload.SetResult();
            await oldWorker.WaitAsync(TimeSpan.FromSeconds(10));
            Assert.False(lookup.IsCompleted);
            Assert.Equal(new byte[] { 4, 5 }, await File.ReadAllBytesAsync(path));
            Assert.Null(cache.Find("late"));
        }
        finally
        {
            finishDownload.TrySetResult();
            finishLookup.Set();
            await oldWorker.WaitAsync(TimeSpan.FromSeconds(10));
            await predecessor.WaitAsync(TimeSpan.FromSeconds(10));
        }
        Assert.Equal(path, await lookup!.WaitAsync(TimeSpan.FromSeconds(10)));
        Assert.Equal(new byte[] { 4, 5 }, await File.ReadAllBytesAsync(path));
    }

    [Theory]
    [InlineData("selected")]
    [InlineData("previous")]
    public async Task Queued_explicit_removal_preserves_live_selected_and_previous_pins(string id)
    {
        using var cache = new SongCache(directory, "account");
        await cache.SaveAsync(Track with { Id = id }, true, (s, t) => s.WriteAsync(new byte[] { 4, 5 }, t).AsTask());
        var storageWork = new BackgroundStorage();
        var blocked = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var release = new ManualResetEventSlim();
        var blocker = storageWork.RunAsync(() =>
        {
            blocked.SetResult();
            if (!release.Wait(TimeSpan.FromSeconds(10))) throw new TimeoutException();
        });
        Task<bool>? removal = null;
        Task<string?>? lookup = null;
        try
        {
            await blocked.Task.WaitAsync(TimeSpan.FromSeconds(10));
            removal = storageWork.RunAsync(() => cache.RemoveDownload(id));
            await WhileStorageGateHeld(cache, () => cache.SetProtectedSongs(["selected", "previous"]));
            lookup = storageWork.RunAsync(() => cache.Find(id));
            Assert.False(removal.IsCompleted);
            Assert.False(lookup.IsCompleted);
        }
        finally { release.Set(); await blocker.WaitAsync(TimeSpan.FromSeconds(10)); }
        Assert.False(await removal!.WaitAsync(TimeSpan.FromSeconds(10)));
        var path = await lookup!.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.NotNull(path);
        Assert.Equal(new byte[] { 4, 5 }, await File.ReadAllBytesAsync(path));
        Assert.True(cache.Snapshot()[id].Pinned);
        using (var reopened = new SongCache(directory, "account"))
        {
            Assert.True(reopened.Snapshot()[id].Pinned);
            Assert.Equal(new byte[] { 4, 5 }, File.ReadAllBytes(reopened.Find(id)!));
        }
        cache.SetProtectedSongs([]);
        Assert.True(cache.RemoveDownload(id));
        Assert.Null(cache.Find(id));
        Assert.Empty(cache.Snapshot());
        Assert.True(cache.RemoveDownload(id)); // Already absent is an accepted idempotent removal.
        cache.SetProtectedSongs([id]);
        Assert.True(cache.RemoveDownload(id)); // No protected file exists to refuse.
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
    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Selection_protection_publishes_and_clears_without_waiting_for_storage_gate(bool clear)
    {
        using var cache = new SongCache(directory, "account");
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[] { 4, 5 }, t).AsTask());
        await WhileStorageGateHeld(cache, () => cache.SetProtectedSongs([Track.Id]));
        if (clear) cache.ClearAutomatic([]);
        else cache.Trim(0, []);
        Assert.Equal(new byte[] { 4, 5 }, File.ReadAllBytes(cache.Find(Track.Id)!));
        await WhileStorageGateHeld(cache, () => cache.SetProtectedSongs([]));
        if (clear) cache.ClearAutomatic([]);
        else cache.Trim(0, []);
        Assert.Null(cache.Find(Track.Id));
    }

    [Fact]
    public async Task Snapshot_reads_metadata_without_waiting_for_storage_gate()
    {
        using var cache = new SongCache(directory, "account");
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[4], t).AsTask());
        await WhileStorageGateHeld(cache, () =>
        {
            var snapshot = cache.Snapshot();
            Assert.True(snapshot[Track.Id].Complete);
            Assert.Equal(4, snapshot[Track.Id].Bytes);
        });
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Recent_publishes_reconciled_completion_without_mutating_old_snapshot(bool delete)
    {
        using var cache = new SongCache(directory, "account");
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[4], t).AsTask());
        var path = cache.Find(Track.Id)!;
        var before = cache.Snapshot();
        if (delete) File.Delete(path);
        else File.WriteAllBytes(path, new byte[1]);
        Assert.True(cache.Snapshot()[Track.Id].Complete); // Metadata reads never inspect storage.
        Assert.False(cache.Recent().Single().Complete);
        Assert.False(cache.Snapshot()[Track.Id].Complete);
        Assert.True(before[Track.Id].Complete);
    }

    [Fact]
    public void Disposed_snapshot_stays_empty_even_after_late_mutations()
    {
        using var cache = new SongCache(directory, "account");
        cache.Pin([Track]);
        var before = cache.Snapshot();
        cache.Dispose();
        Assert.Empty(cache.Snapshot());
        cache.Trim(0, []);
        cache.ClearAutomatic([]);
        Assert.Empty(cache.Snapshot());
        Assert.True(before[Track.Id].Pinned);
    }

    [Fact]
    public async Task Snapshot_tracks_pin_download_mime_recency_and_reopen_as_immutable_metadata()
    {
        using var cache = new SongCache(directory, "account");
        Assert.Empty(cache.Snapshot());
        cache.Pin([Track with { CoverArtId = "https://server/art?secret=hidden" }]);
        var pinned = cache.Snapshot();
        Assert.True(pinned[Track.Id].Pinned);
        Assert.False(pinned[Track.Id].Complete);
        Assert.Null(pinned[Track.Id].Song.CoverArtId);
        var mutable = Assert.IsAssignableFrom<IDictionary<string, CachedSong>>(pinned);
        Assert.Throws<NotSupportedException>(() => mutable.Clear());
        await cache.SaveMediaAsync(Track, false, async (s, t) =>
        {
            Assert.False(cache.Snapshot()[Track.Id].Complete);
            Assert.True(cache.Snapshot()[Track.Id].Pinned);
            await s.WriteAsync(new byte[4], t);
            return "audio/flac";
        });
        var saved = cache.Snapshot();
        Assert.True(saved[Track.Id].Complete);
        Assert.True(saved[Track.Id].Pinned);
        Assert.Equal(4, saved[Track.Id].Bytes);
        Assert.Equal("audio/flac", saved[Track.Id].ContentType);
        Assert.False(pinned[Track.Id].Complete);
        Assert.NotNull(cache.Find(Track.Id));
        Assert.NotSame(saved, cache.Snapshot());
        Assert.True(cache.Snapshot()[Track.Id].Accessed >= saved[Track.Id].Accessed);
        using var reopened = new SongCache(directory, "account");
        Assert.Equal(cache.Snapshot()[Track.Id], reopened.Snapshot()[Track.Id]);
        using var other = new SongCache(directory, "other");
        Assert.Empty(other.Snapshot());
    }

    [Fact]
    public async Task Snapshot_tracks_partial_failure_and_completed_save_repin()
    {
        using var cache = new SongCache(directory, "account");
        await Assert.ThrowsAsync<IOException>(() => cache.SaveAsync(Track, false, async (s, t) =>
        { await s.WriteAsync(new byte[3], t); throw new IOException("interrupted"); }));
        Assert.False(cache.Snapshot()[Track.Id].Complete);
        Assert.Equal(3, cache.Snapshot()[Track.Id].Bytes);
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[4], t).AsTask());
        await cache.SaveAsync(Track, true, (_, _) => throw new InvalidOperationException("must not download"));
        Assert.True(cache.Snapshot()[Track.Id].Complete);
        Assert.True(cache.Snapshot()[Track.Id].Pinned);
        Assert.Equal(4, cache.Snapshot()[Track.Id].Bytes);
    }

    [Fact]
    public async Task Snapshot_publishes_mutations_even_when_metadata_persistence_fails()
    {
        using var cache = new SongCache(directory, "account");
        var blocker = Path.Combine(Directory.GetDirectories(directory).Single(), "index.json.tmp");
        var failure = await Record.ExceptionAsync(() => cache.SaveAsync(Track, false, async (s, t) =>
        { await s.WriteAsync(new byte[4], t); Directory.CreateDirectory(blocker); }));
        Assert.True(failure is IOException or UnauthorizedAccessException);
        Assert.True(cache.Snapshot()[Track.Id].Complete);
        Assert.Equal(4, cache.Snapshot()[Track.Id].Bytes);
        var before = cache.Snapshot();
        Assert.NotNull(cache.Find(Track.Id)); // Recency persistence is best-effort.
        Assert.NotSame(before, cache.Snapshot());
        Assert.True(Record.Exception(() => cache.Pin([Track])) is IOException or UnauthorizedAccessException);
        Assert.True(cache.Snapshot()[Track.Id].Pinned);
        Assert.True(Record.Exception(() => cache.RemoveDownload(Track.Id)) is IOException or UnauthorizedAccessException);
        Assert.Empty(cache.Snapshot());
    }

    [Theory]
    [InlineData("trim")]
    [InlineData("clear")]
    [InlineData("remove")]
    [InlineData("purge")]
    public async Task Snapshot_publishes_each_removal_without_mutating_prior_view(string operation)
    {
        using var cache = new SongCache(directory, "account");
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[4], t).AsTask());
        var before = cache.Snapshot();
        switch (operation)
        {
            case "trim": cache.Trim(0, []); break;
            case "clear": cache.ClearAutomatic([]); break;
            case "remove": cache.RemoveDownload(Track.Id); break;
            case "purge": cache.Purge(); break;
        }
        Assert.Empty(cache.Snapshot());
        Assert.True(before[Track.Id].Complete);
    }

    [Theory]
    [InlineData(false, false)]
    [InlineData(true, false)]
    [InlineData(false, true)]
    [InlineData(true, true)]
    public async Task Failed_automatic_deletion_retains_tracking_and_publishes_partial_batch_before_retry(bool clear, bool purge)
    {
        using var cache = new SongCache(directory, "account");
        await cache.SaveAsync(Track with { Id = "old" }, false, (s, t) => s.WriteAsync(new byte[2], t).AsTask());
        var oldPath = cache.Find("old")!;
        await cache.SaveAsync(Track, false, (s, t) => s.WriteAsync(new byte[] { 4, 5, 6, 7 }, t).AsTask());
        var path = cache.Find(Track.Id)!;
        var before = cache.Snapshot();
        var failedEntry = before[Track.Id];
        var backup = path + ".backup";
        File.Move(path, backup);
        Directory.CreateDirectory(path); // File.Delete(directory) fails even when running as root.
        try
        {
            var failure = Record.Exception(() =>
            {
                if (clear) cache.ClearAutomatic([]);
                else cache.Trim(0, []);
            });
            Assert.True(failure is IOException or UnauthorizedAccessException);
            Assert.False(File.Exists(oldPath));
            Assert.False(cache.Snapshot().ContainsKey("old"));
            Assert.Equal(failedEntry, Assert.Single(cache.Snapshot()).Value);
            Assert.True(before.ContainsKey("old")); // Previously published views stay immutable.
            cache.Pin([Track with { Id = "pin" }]); // A later persistence must retain the failed record.
        }
        finally
        {
            Directory.Delete(path);
            File.Move(backup, path);
        }

        using (var reopened = new SongCache(directory, "account"))
        {
            Assert.Equal(failedEntry, reopened.Snapshot()[Track.Id]);
            Assert.False(reopened.Snapshot().ContainsKey("old"));
            Assert.Equal(new byte[] { 4, 5, 6, 7 }, File.ReadAllBytes(reopened.Find(Track.Id)!));
        }
        Assert.NotNull(cache.Find(Track.Id)); // Best-effort recency persistence must retain retry tracking too.
        if (purge) cache.Purge(); // Account cleanup must also be able to retry the retained entry.
        else if (clear) cache.ClearAutomatic([]);
        else cache.Trim(0, []);
        Assert.False(File.Exists(path));
        Assert.False(cache.Snapshot().ContainsKey(Track.Id));
        if (!purge) Assert.True(Assert.Single(cache.Snapshot()).Value.Pinned);
        cache.Purge();
        Assert.Empty(cache.Snapshot());
        var account = Path.GetDirectoryName(path)!;
        Assert.Empty(Directory.GetFiles(account, "*.media"));
        Assert.Empty(Directory.GetFiles(account, "*.partial"));
        using var cleaned = new SongCache(directory, "account");
        Assert.Empty(cleaned.Snapshot());
    }

    private static async Task WhileStorageGateHeld(SongCache cache, Action action)
    {
        var gate = typeof(SongCache).GetField("gate", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!.GetValue(cache)!;
        using var held = new ManualResetEventSlim();
        using var release = new ManualResetEventSlim();
        var holder = Task.Factory.StartNew(() =>
        {
            lock (gate) { held.Set(); release.Wait(); }
        }, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
        Task? operation = null;
        try
        {
            Assert.True(held.Wait(TimeSpan.FromSeconds(10)));
            operation = Task.Factory.StartNew(action, CancellationToken.None, TaskCreationOptions.LongRunning, TaskScheduler.Default);
            await operation.WaitAsync(TimeSpan.FromSeconds(2));
        }
        finally
        {
            release.Set();
            await holder.WaitAsync(TimeSpan.FromSeconds(10));
            if (operation is not null) await operation.WaitAsync(TimeSpan.FromSeconds(10));
        }
    }

    public void Dispose() { if (Directory.Exists(directory)) Directory.Delete(directory, true); }
}
