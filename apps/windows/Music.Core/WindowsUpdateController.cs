namespace Music.Core;

/// <summary>The OS/package boundary. Implementations must only offer this application's newer Windows packages.</summary>
public interface IWindowsUpdateBackend
{
    bool IsInstalled { get; }
    Version CurrentVersion { get; }
    WindowsChangelog? Changelog => null;
    Task<Version?> CheckAsync();
    Task DownloadAsync(Version version, Action<int> progress, CancellationToken token);
    void ApplyAndRestart(Version version);
}

public enum WindowsUpdatePhase { Idle, Checking, Current, Available, Downloading, Ready, Restarting, Error, NotInstalled }
public sealed record WindowsUpdateStatus(WindowsUpdatePhase Phase, string Message, Version? Version = null, int Progress = 0, WindowsChangelog? Changelog = null);

/// <summary>No work is performed until an explicit user action. Exceptions never enter UI status text.</summary>
public sealed class WindowsUpdateController : IDisposable
{
    private readonly IWindowsUpdateBackend backend;
    private readonly Func<Task> preserveSession;
    private readonly CancellationTokenSource lifetime = new();
    private int busy;
    private bool disposed;
    public WindowsUpdateStatus Status { get; private set; } = new(WindowsUpdatePhase.Idle, "Check for updates when you choose.");
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

    public async Task CheckAsync()
    {
        if (disposed || Interlocked.CompareExchange(ref busy, 1, 0) != 0) return;
        try
        {
            if (Status.Phase is WindowsUpdatePhase.Ready or WindowsUpdatePhase.Restarting) return;
            if (!backend.IsInstalled)
            {
                Set(new(WindowsUpdatePhase.NotInstalled, "Install Music using the Windows installer to enable in-app updates. Loose development builds cannot update."));
                return;
            }
            Set(new(WindowsUpdatePhase.Checking, "Looking for a newer Windows version…"));
            var version = await backend.CheckAsync();
            Set(version is not null && version > backend.CurrentVersion
                ? new(WindowsUpdatePhase.Available, $"Music {version}", version, Changelog: backend.Changelog)
                : new(WindowsUpdatePhase.Current, "No newer Windows version is available."));
        }
        catch { Set(new(WindowsUpdatePhase.Error, "Could not check for updates. Check your internet connection or try again later (GitHub may be rate-limiting requests).")); }
        finally { Interlocked.Exchange(ref busy, 0); }
    }

    public async Task DownloadAsync()
    {
        if (disposed || Interlocked.CompareExchange(ref busy, 1, 0) != 0) return;
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
        finally { Interlocked.Exchange(ref busy, 0); }
    }

    public async Task RestartAsync()
    {
        if (disposed || Interlocked.CompareExchange(ref busy, 1, 0) != 0) return;
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
        finally { Interlocked.Exchange(ref busy, 0); }
    }

    public void Dispose()
    {
        disposed = true;
        lifetime.Cancel();
        // Keep the CTS alive while a download may still be observing its token.
    }
}
