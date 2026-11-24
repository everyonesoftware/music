namespace Music.Core;

/// <summary>The OS/package boundary. Implementations must only offer this application's newer Windows packages.</summary>
public interface IWindowsUpdateBackend
{
    bool IsInstalled { get; }
    bool IsStartupCheckDeferred => false;
    Version CurrentVersion { get; }
    WindowsChangelog? Changelog => null;
    Task<Version?> CheckAsync();
    Task DownloadAsync(Version version, Action<int> progress, CancellationToken token);
    void ApplyAndRestart(Version version);
}

public enum WindowsUpdatePhase { Idle, Checking, Current, Available, Downloading, Ready, Restarting, Error, NotInstalled, StartupDeferred }
public sealed record WindowsUpdateStatus(WindowsUpdatePhase Phase, string Message, Version? Version = null, int Progress = 0, WindowsChangelog? Changelog = null);

/// <summary>Startup only checks for an offer; download and apply require explicit actions.</summary>
public sealed class WindowsUpdateController : IDisposable
{
    private readonly IWindowsUpdateBackend backend;
    private readonly Func<Task> preserveSession;
    private readonly CancellationTokenSource lifetime = new();
    private readonly SemaphoreSlim operation = new(1, 1);
    private int checkRequested;
    private int startupRequested;
    private bool disposed;
    public WindowsUpdateStatus Status { get; private set; } = new(WindowsUpdatePhase.Idle, "");
    public event Action<WindowsUpdateStatus>? Changed;

    public WindowsUpdateController(IWindowsUpdateBackend backend, Func<Task> preserveSession)
    {
        this.backend = backend;
        this.preserveSession = preserveSession;
    }

    private void Set(WindowsUpdateStatus status)
    {
        if (disposed) return;
        Status = status;
        Changed?.Invoke(status);
    }

    public Task CheckOnStartupAsync() => Interlocked.Exchange(ref startupRequested, 1) == 0
        ? CheckAsync(background: true) : Task.CompletedTask;

    public Task CheckAsync() => CheckAsync(background: false);

    private async Task CheckAsync(bool background)
    {
        if (disposed || Interlocked.CompareExchange(ref checkRequested, 1, 0) != 0) return;
        // A manual check clicked during download waits rather than interrupting package verification.
        await operation.WaitAsync();
        var previous = Status;
        try
        {
            if (disposed || previous.Phase == WindowsUpdatePhase.Restarting) return;
            Set(new(WindowsUpdatePhase.Checking, "Looking for a newer Windows version…"));
            // Reserve the shared gate before dispatch, including lazy package locator initialization.
            var result = await (background ? Task.Run(() => DiscoverAsync(startup: true)) : DiscoverAsync(startup: false));
            if (previous.Phase == WindowsUpdatePhase.Ready && result.Phase == WindowsUpdatePhase.Available && result.Version == previous.Version)
                result = result with { Phase = WindowsUpdatePhase.Ready, Message = $"Music {result.Version} is downloaded.", Progress = 100 };
            Set(result);
        }
        catch
        {
            const string error = "Could not check for updates. Check your internet connection or try again later (GitHub may be rate-limiting requests).";
            Set(previous.Phase == WindowsUpdatePhase.Ready
                ? previous with { Message = error + " The downloaded update is still ready to install." }
                : new(WindowsUpdatePhase.Error, error));
        }
        finally
        {
            operation.Release();
            Interlocked.Exchange(ref checkRequested, 0);
        }
    }

    private async Task<WindowsUpdateStatus> DiscoverAsync(bool startup)
    {
        if (!backend.IsInstalled)
            return new(WindowsUpdatePhase.NotInstalled, "Install Music using the Windows installer to enable in-app updates. Loose development builds cannot update.");
        if (startup && backend.IsStartupCheckDeferred)
            return new(WindowsUpdatePhase.StartupDeferred, "GitHub recently limited requests. You can still check for updates manually.");
        var version = await backend.CheckAsync();
        return version is not null && version > backend.CurrentVersion
            ? new(WindowsUpdatePhase.Available, $"Music {version}", version, Changelog: backend.Changelog)
            : new(WindowsUpdatePhase.Current, "No newer Windows version is available.");
    }

    public async Task DownloadAsync()
    {
        if (disposed || !operation.Wait(0)) return;
        try
        {
            if (Status.Phase != WindowsUpdatePhase.Available || Status.Version is not { } version) return;
            var changelog = Status.Changelog;
            Set(new(WindowsUpdatePhase.Downloading, $"Music {version}…", version, Changelog: changelog));
            await backend.DownloadAsync(version, value =>
            {
                if (Status.Phase == WindowsUpdatePhase.Downloading)
                    Set(new(WindowsUpdatePhase.Downloading, $"Music {version}…", version, Math.Clamp(value, 0, 100), changelog));
            }, lifetime.Token);
            Set(new(WindowsUpdatePhase.Ready, $"Music {version} is downloaded.", version, 100, changelog));
        }
        catch { Set(new(WindowsUpdatePhase.Error, "Download failed or was cancelled. Nothing was installed. Check for updates to try again.")); }
        finally { operation.Release(); }
    }

    public async Task RestartAsync()
    {
        if (disposed || !operation.Wait(0)) return;
        var version = Status.Version;
        var changelog = Status.Changelog;
        try
        {
            if (Status.Phase != WindowsUpdatePhase.Ready || version is null) return;
            Set(new(WindowsUpdatePhase.Restarting, "Saving your session before restarting…", version, 100, changelog));
            await preserveSession();
            if (!disposed) backend.ApplyAndRestart(version);
        }
        catch { Set(new(WindowsUpdatePhase.Ready, "Could not save your session or start the installer. Music has not requested another restart. Try Restart and install again.", version, 100, changelog)); }
        finally { operation.Release(); }
    }

    public void Dispose()
    {
        disposed = true;
        lifetime.Cancel();
        // Keep the CTS alive while a download may still be observing its token.
    }
}
