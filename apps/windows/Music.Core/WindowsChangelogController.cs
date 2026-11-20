namespace Music.Core;

public enum WindowsChangelogPhase { Idle, Loaded, Unavailable }

/// <summary>Presentation snapshot only; disclosure never starts network work.</summary>
public sealed class WindowsChangelogController : IDisposable
{
    private Version? offered;
    private bool disposed;
    public WindowsChangelogPhase Phase { get; private set; }
    public WindowsChangelog? Changes { get; private set; }
    public event Action? Changed;

    public void SetOffer(Version? version, WindowsChangelog? changes)
    {
        if (disposed) return;
        if (version is null) changes = null;
        if (Equals(offered, version) && ReferenceEquals(Changes, changes)) return;
        offered = version;
        Changes = changes;
        Phase = version is null ? WindowsChangelogPhase.Idle : changes is null ? WindowsChangelogPhase.Unavailable : WindowsChangelogPhase.Loaded;
        Changed?.Invoke();
    }

    public void Dispose()
    {
        disposed = true;
        offered = null;
        Changes = null;
        Phase = WindowsChangelogPhase.Idle;
    }
}
