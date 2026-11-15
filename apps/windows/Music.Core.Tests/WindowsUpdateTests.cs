using Music.Core;
using Xunit;

namespace Music.Core.Tests;

public sealed class WindowsUpdateTests
{
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
        await controller.DownloadAsync();
        await controller.RestartAsync();
        Assert.Equal(0, backend.Downloads);
        Assert.Equal(0, backend.Applies);
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
    public async Task A_new_check_cannot_discard_an_already_downloaded_update()
    {
        var backend = new Backend();
        using var controller = new WindowsUpdateController(backend, () => Task.CompletedTask);
        await controller.CheckAsync();
        await controller.DownloadAsync();
        await controller.CheckAsync();
        Assert.Equal(WindowsUpdatePhase.Ready, controller.Status.Phase);
        Assert.Equal(1, backend.Checks);
    }

    private sealed class Backend : IWindowsUpdateBackend
    {
        public bool Installed = true;
        public bool IsInstalled => Installed;
        public Version CurrentVersion => new(0, 1, 10);
        public Version? Offered = new(0, 1, 20);
        public bool CheckError, DownloadError;
        public Task<Version?>? PendingCheck;
        public int Checks, Downloads, Applies;
        public Task<Version?> CheckAsync() { Checks++; return CheckError ? Task.FromException<Version?>(new IOException("secret url")) : PendingCheck ?? Task.FromResult(Offered); }
        public Task DownloadAsync(Version version, Action<int> progress, CancellationToken token) { Downloads++; progress(51); return DownloadError ? Task.FromException(new IOException("secret path")) : Task.CompletedTask; }
        public Action? BeforeApply;
        public void ApplyAndRestart(Version version) { BeforeApply?.Invoke(); Applies++; }
    }
}
