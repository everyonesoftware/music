namespace Music.Core;

/// <summary>Ordered, short storage operations; callers never execute filesystem work inline.</summary>
public sealed class BackgroundStorage
{
    private readonly object gate = new();
    private Task tail = Task.CompletedTask;

    public Task RunAsync(Action action) => RunAsync(() => { action(); return true; });

    public Task<T> RunAsync<T>(Func<T> action)
    {
        lock (gate)
        {
            var previous = tail;
            var task = Task.Run(async () =>
            {
                // Report failures to their own caller without preventing later cleanup or reads.
                try { await previous.ConfigureAwait(false); } catch { }
                return action();
            });
            tail = task;
            return task;
        }
    }
}
