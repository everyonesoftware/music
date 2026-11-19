namespace Music.Core;

public enum WindowsChangelogPhase { Idle, Loading, Loaded, Unavailable }

/// <summary>Independent from download/install: notes are on demand and owned by one exact offer.</summary>
public sealed class WindowsChangelogController : IDisposable
{
    private readonly Version installed;
    private readonly Func<Version, Version, CancellationToken, Task<WindowsChangelog>> load;
    private CancellationTokenSource? request;
    private Version? offered;
    private long generation;
    private bool disposed;
    public WindowsChangelogPhase Phase { get; private set; }
    public WindowsChangelog? Changes { get; private set; }
    public event Action? Changed;

    public WindowsChangelogController(Version installed, Func<Version, Version, CancellationToken, Task<WindowsChangelog>> load)
    {
        this.installed = installed;
        this.load = load;
    }

    public void SetOffer(Version? version)
    {
        if (disposed || Equals(offered, version)) return;
        generation++;
        request?.Cancel();
        offered = version;
        Changes = null;
        Phase = WindowsChangelogPhase.Idle;
        Changed?.Invoke();
    }

    public async Task LoadAsync()
    {
        if (disposed || offered is not { } version || Phase is WindowsChangelogPhase.Loading or WindowsChangelogPhase.Loaded) return;
        var revision = generation;
        using var pending = new CancellationTokenSource();
        request = pending;
        Phase = WindowsChangelogPhase.Loading;
        Changed?.Invoke();
        try
        {
            var result = await load(installed, version, pending.Token);
            if (disposed || revision != generation) return;
            Changes = result;
            Phase = WindowsChangelogPhase.Loaded;
        }
        catch
        {
            if (disposed || revision != generation) return;
            Changes = null;
            Phase = WindowsChangelogPhase.Unavailable;
        }
        finally
        {
            if (ReferenceEquals(request, pending)) request = null;
        }
        Changed?.Invoke();
    }

    public void Dispose()
    {
        if (disposed) return;
        disposed = true;
        generation++;
        request?.Cancel();
        Changes = null;
        Phase = WindowsChangelogPhase.Idle;
    }
}
