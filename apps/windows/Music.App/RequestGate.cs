namespace Music.App;

// Every asynchronous UI operation must hold a current lease before publishing.
public sealed class RequestGate : IDisposable
{
    private CancellationTokenSource? source;
    private long version;
    public readonly record struct Lease(long Version, CancellationToken Token);
    public Lease Begin()
    {
        Cancel();
        source = new CancellationTokenSource();
        return new Lease(version, source.Token);
    }
    public bool IsCurrent(Lease lease) => lease.Version == version && !lease.Token.IsCancellationRequested;
    public void Cancel() { version++; source?.Cancel(); source?.Dispose(); source = null; }
    public void Dispose() => Cancel();
}
