using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class BackgroundStorageTests
{
    [Fact]
    public async Task Blocked_disk_work_yields_to_caller_and_preserves_operation_order()
    {
        var work = new BackgroundStorage();
        using var release = new ManualResetEventSlim();
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var order = new List<int>();
        var first = work.RunAsync(() =>
        {
            started.SetResult();
            if (!release.Wait(TimeSpan.FromSeconds(10))) throw new TimeoutException();
            order.Add(1);
        });
        try
        {
            await started.Task.WaitAsync(TimeSpan.FromSeconds(5));
            var second = work.RunAsync(() => { order.Add(2); return 42; });
            Assert.False(first.IsCompleted);
            Assert.False(second.IsCompleted);
            // The caller can process another UI event while storage is blocked.
            await Task.Yield();
            release.Set();
            Assert.Equal(42, await second.WaitAsync(TimeSpan.FromSeconds(5)));
            await first;
            Assert.Equal(new[] { 1, 2 }, order);
        }
        finally { release.Set(); await first; }
    }

    [Fact]
    public async Task Storage_failure_does_not_poison_later_operations()
    {
        var work = new BackgroundStorage();
        await Assert.ThrowsAsync<IOException>(() => work.RunAsync(() => throw new IOException()));
        Assert.Equal(7, await work.RunAsync(() => 7));
    }

    [Fact]
    public async Task Explicit_account_purge_after_pending_initialization_removes_pins_without_touching_other_accounts()
    {
        string root = Path.Combine(Path.GetTempPath(), "music-opening-" + Guid.NewGuid().ToString("N"));
        using var release = new ManualResetEventSlim();
        var started = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var song = new Song("saved", "Title", "Artist", "Album", null, 90);
        var work = new BackgroundStorage();
        try
        {
            foreach (string account in new[] { "removed", "retained" })
            {
                using var seed = new SongCache(root, account);
                await seed.SaveAsync(song, true, (s, t) => s.WriteAsync(new byte[] { 1, 2, 3 }, t).AsTask());
            }
            var opening = work.RunAsync(() =>
            {
                started.SetResult();
                if (!release.Wait(TimeSpan.FromSeconds(10))) throw new TimeoutException();
                // A cancelled UI lease disposes this instance without attaching it.
                using var stale = new SongCache(root, "removed");
            });
            await started.Task.WaitAsync(TimeSpan.FromSeconds(5));
            var purge = work.RunAsync(() =>
            {
                using var storage = new SongCache(root, "removed");
                storage.Purge();
            });
            Assert.False(purge.IsCompleted);
            release.Set();
            await opening.WaitAsync(TimeSpan.FromSeconds(5));
            await purge.WaitAsync(TimeSpan.FromSeconds(5));
            using var removed = new SongCache(root, "removed");
            using var retained = new SongCache(root, "retained");
            Assert.Empty(removed.Snapshot());
            Assert.Null(removed.Find(song.Id));
            Assert.NotNull(retained.Find(song.Id));
            Assert.Single(Directory.GetFiles(root, "*.media", SearchOption.AllDirectories));
        }
        finally { release.Set(); if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
}
