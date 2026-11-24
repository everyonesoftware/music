using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class WindowsUpdateTests
{
    [Fact]
    public async Task Startup_reserves_the_shared_gate_before_locator_work_and_only_discovers_once()
    {
        var locating = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var releaseLocator = new ManualResetEventSlim();
        var backend = new Backend { BeforeLocate = () =>
        {
            locating.TrySetResult();
            Assert.True(releaseLocator.Wait(TimeSpan.FromSeconds(10)));
        } };
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        var startup = controller.CheckOnStartupAsync();
        try
        {
            await locating.Task.WaitAsync(TimeSpan.FromSeconds(10));
            Assert.Equal(WindowsUpdatePhase.Checking, controller.Status.Phase);
            await controller.CheckAsync();
            await controller.CheckOnStartupAsync();
            Assert.Equal(0, backend.Checks);
        }
        finally { releaseLocator.Set(); }
        await startup;
        await controller.CheckOnStartupAsync();
        Assert.Equal(WindowsUpdatePhase.Available, controller.Status.Phase);
        Assert.Equal(1, backend.Checks);
        Assert.Equal(0, backend.Downloads);
        Assert.Equal(0, backend.Applies);
    }

    [Fact]
    public async Task Failed_startup_discovery_can_be_retried_manually()
    {
        var backend = new Backend { CheckError = true };
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        await controller.CheckOnStartupAsync();
        Assert.Equal(WindowsUpdatePhase.Error, controller.Status.Phase);
        backend.CheckError = false;
        await controller.CheckOnStartupAsync();
        Assert.Equal(1, backend.Checks);
        await controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.Available, controller.Status.Phase);
        Assert.Equal(2, backend.Checks);
        Assert.Equal(0, backend.Downloads);
        Assert.Equal(0, backend.Applies);
    }

    [Fact]
    public async Task Check_publishes_notes_immediately_and_preserves_snapshot_through_install()
    {
        var changes = new WindowsChangelog(new string('a', 40), new string('b', 40), ["Faster browsing"], [], []);
        var backend = new Backend { Changelog = changes };
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        var seen = new List<WindowsUpdateStatus>();
        controller.Changed += seen.Add;
        await controller.CheckAsync();
        Assert.Same(changes, controller.Status.Changelog);
        await controller.DownloadAsync();
        Assert.All(seen.Where(s => s.Version is not null), s => Assert.Same(changes, s.Changelog));
        await controller.RestartAsync();
        Assert.Same(changes, controller.Status.Changelog);
        Assert.Equal(1, backend.Checks);
    }

    [Fact]
    public async Task Recheck_with_unavailable_notes_clears_old_snapshot_but_allows_download()
    {
        var backend = new Backend { Changelog = new(new string('a', 40), new string('b', 40), [], [], []) };
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        await controller.CheckAsync();
        Assert.NotNull(controller.Status.Changelog);
        backend.Changelog = null;
        await controller.CheckAsync();
        Assert.Null(controller.Status.Changelog);
        Assert.Equal(WindowsUpdatePhase.Available, controller.Status.Phase);
        await controller.DownloadAsync();
        Assert.Equal(WindowsUpdatePhase.Ready, controller.Status.Phase);
    }
    [Fact]
    public async Task Check_is_user_initiated_and_offers_download_without_applying()
    {
        var backend = new Backend();
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        Assert.Equal(0, backend.Checks);
        await controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.Available, controller.Status.Phase);
        Assert.Equal(new Version(0, 1, 20), controller.Status.Version);
        Assert.Equal(1, backend.Checks);
        Assert.Equal(0, backend.Downloads);
        Assert.Equal(0, backend.Applies);
    }

    [Fact]
    public async Task Download_reports_progress_then_waits_for_explicit_restart_after_saving()
    {
        var backend = new Backend();
        bool saved = false;
        using var controller = new WindowsUpdateController(backend, () => { saved = true; return Task.CompletedTask; });
        backend.BeforeApply = () => Assert.True(saved);
        var observed = new List<WindowsUpdateStatus>();
        controller.Changed += observed.Add;
        await controller.DownloadAsync();
        Assert.Equal(0, backend.Downloads);
        await controller.RestartAsync();
        Assert.Equal(0, backend.Applies);
        await controller.CheckAsync();
        await controller.DownloadAsync();
        Assert.Contains(observed, s => s.Phase == WindowsUpdatePhase.Downloading && s.Progress == 51);
        Assert.Equal(WindowsUpdatePhase.Ready, controller.Status.Phase);
        Assert.False(saved);
        Assert.Equal(0, backend.Applies);
        await controller.RestartAsync();
        Assert.True(saved);
        Assert.Equal(1, backend.Applies);
    }

    [Theory]
    [InlineData("0.1.10")]
    [InlineData("0.1.9")]
    [InlineData(null)]
    public async Task Equal_older_or_missing_release_never_offers_install(string? release)
    {
        var backend = new Backend { Offered = release is null ? null : Version.Parse(release) };
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        await controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.Current, controller.Status.Phase);
        Assert.Equal("No newer Windows version is available.", controller.Status.Message);
        await controller.DownloadAsync();
        await controller.RestartAsync();
        Assert.Equal(0, backend.Downloads);
        Assert.Equal(0, backend.Applies);
    }

    [Fact]
    public async Task Rechecking_a_current_result_does_not_present_checking_or_failure_as_up_to_date()
    {
        var backend = new Backend { Offered = null };
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        Assert.Equal(WindowsUpdatePhase.Idle, controller.Status.Phase);
        await controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.Current, controller.Status.Phase);
        var pending = new TaskCompletionSource<Version?>(TaskCreationOptions.RunContinuationsAsynchronously);
        backend.PendingCheck = pending.Task;
        var recheck = controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.Checking, controller.Status.Phase);
        Assert.DoesNotContain("No newer", controller.Status.Message);
        pending.SetException(new IOException("secret feed URL"));
        await recheck;
        Assert.Equal(WindowsUpdatePhase.Error, controller.Status.Phase);
        Assert.DoesNotContain("No newer", controller.Status.Message);
        Assert.DoesNotContain("secret", controller.Status.Message);
        Assert.Equal(0, backend.Downloads);
        Assert.Equal(0, backend.Applies);
        backend.PendingCheck = null;
        await controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.Current, controller.Status.Phase);
        Assert.Equal("No newer Windows version is available.", controller.Status.Message);
    }

    [Fact]
    public async Task Loose_build_does_not_contact_feed()
    {
        var backend = new Backend { Installed = false };
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        await controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.NotInstalled, controller.Status.Phase);
        Assert.Equal(0, backend.Checks);
    }

    [Fact]
    public async Task Failed_save_blocks_restart_and_can_be_retried_without_redownload()
    {
        var backend = new Backend();
        bool fail = true;
        using var controller = new WindowsUpdateController(backend, () => fail ? Task.FromException(new IOException("private session details")) : Task.CompletedTask);
        await controller.CheckAsync();
        await controller.DownloadAsync();
        await controller.RestartAsync();
        Assert.Equal(WindowsUpdatePhase.Ready, controller.Status.Phase);
        Assert.Equal(0, backend.Applies);
        Assert.DoesNotContain("private", controller.Status.Message);
        fail = false;
        await controller.RestartAsync();
        Assert.Equal(1, backend.Applies);
        Assert.Equal(1, backend.Downloads);
    }

    [Theory]
    [InlineData(true)]
    [InlineData(false)]
    public async Task Feed_and_download_errors_are_safe_and_retryable(bool checkFailure)
    {
        var backend = new Backend { CheckError = checkFailure, DownloadError = !checkFailure };
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        await controller.CheckAsync();
        if (!checkFailure) await controller.DownloadAsync();
        Assert.Equal(WindowsUpdatePhase.Error, controller.Status.Phase);
        Assert.DoesNotContain("secret", controller.Status.Message);
        Assert.Equal(0, backend.Applies);
        backend.CheckError = backend.DownloadError = false;
        await controller.CheckAsync();
        await controller.DownloadAsync();
        Assert.Equal(WindowsUpdatePhase.Ready, controller.Status.Phase);
    }

    [Fact]
    public async Task Duplicate_clicks_do_not_start_parallel_work_and_close_suppresses_completion()
    {
        var waiting = new TaskCompletionSource<Version?>(TaskCreationOptions.RunContinuationsAsynchronously);
        var backend = new Backend { PendingCheck = waiting.Task };
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        var check = controller.CheckAsync();
        await controller.CheckAsync();
        await controller.DownloadAsync();
        Assert.Equal(1, backend.Checks);
        Assert.Equal(0, backend.Downloads);
        controller.Dispose();
        waiting.SetResult(new Version(0, 1, 30));
        await check;
        Assert.Equal(WindowsUpdatePhase.Checking, controller.Status.Phase);
        await controller.RestartAsync();
        Assert.Equal(0, backend.Applies);
    }

    [Fact]
    public async Task Recheck_replaces_a_downloaded_offer_when_a_newer_version_appears()
    {
        var backend = new Backend();
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        await controller.CheckAsync();
        await controller.DownloadAsync();
        backend.Offered = new Version(0, 1, 30);
        await controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.Available, controller.Status.Phase);
        Assert.Equal(backend.Offered, controller.Status.Version);
        Assert.Equal(2, backend.Checks);
        await controller.RestartAsync();
        Assert.Equal(0, backend.Applies);
        Assert.Equal(1, backend.Downloads);
        await controller.DownloadAsync();
        Assert.Equal(WindowsUpdatePhase.Ready, controller.Status.Phase);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Unchanged_offer_or_failed_recheck_keeps_the_download_ready(bool fail)
    {
        var backend = new Backend();
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        await controller.CheckAsync();
        await controller.DownloadAsync();
        backend.CheckError = fail;
        await controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.Ready, controller.Status.Phase);
        Assert.Equal(2, backend.Checks);
        if (fail) Assert.Contains("Could not check", controller.Status.Message);
        await controller.RestartAsync();
        Assert.Equal(1, backend.Applies);
        Assert.Equal(1, backend.Downloads);
    }

    [Fact]
    public async Task Check_requested_during_download_runs_once_after_it_finishes()
    {
        var pending = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var backend = new Backend { PendingDownload = pending.Task };
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        await controller.CheckAsync();
        var download = controller.DownloadAsync();
        backend.Offered = new Version(0, 1, 30);
        var check = controller.CheckAsync();
        await controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.Downloading, controller.Status.Phase);
        Assert.Equal(1, backend.Checks);
        pending.SetResult();
        await Task.WhenAll(download, check);
        Assert.Equal(2, backend.Checks);
        Assert.Equal(WindowsUpdatePhase.Available, controller.Status.Phase);
        Assert.Equal(backend.Offered, controller.Status.Version);
    }

    private sealed class Backend : IWindowsUpdateBackend
    {
        public WindowsChangelog? Changelog { get; set; }
        public bool Installed = true;
        public Action? BeforeLocate;
        public bool IsInstalled { get { BeforeLocate?.Invoke(); return Installed; } }
        public Version CurrentVersion => new(0, 1, 10);
        public Version? Offered = new(0, 1, 20);
        public bool CheckError, DownloadError;
        public Task<Version?>? PendingCheck;
        public Task? PendingDownload;
        public int Checks, Downloads, Applies;
        public Task<Version?> CheckAsync() { Checks++; return CheckError ? Task.FromException<Version?>(new IOException("secret url")) : PendingCheck ?? Task.FromResult(Offered); }
        public Task DownloadAsync(Version version, Action<int> progress, CancellationToken token) { Downloads++; progress(51); return DownloadError ? Task.FromException(new IOException("secret path")) : PendingDownload ?? Task.CompletedTask; }
        public Action? BeforeApply;
        public void ApplyAndRestart(Version version) { BeforeApply?.Invoke(); Applies++; }
    }
}
