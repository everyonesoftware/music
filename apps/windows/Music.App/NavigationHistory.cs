namespace Music.App;

// In-memory only: collection metadata belongs to the current connection, never a persisted queue.
internal sealed record NavigationView(string Page, string Query, string Mode, object? Collection = null);

internal sealed class NavigationHistory
{
    private readonly List<NavigationView> entries = [];

    public bool Record(NavigationView current, NavigationView destination)
    {
        if (current.Page == destination.Page && Equals(current.Collection, destination.Collection)) return false;
        entries.Add(current);
        if (entries.Count > 64) entries.RemoveAt(0);
        return true;
    }

    public void Clear() => entries.Clear();

    public void RemoveCollection(Predicate<object> matches) =>
        entries.RemoveAll(view => view.Collection is { } collection && matches(collection));

    public bool TryBack(out NavigationView? previous)
    {
        previous = null;
        if (entries.Count == 0) return false;
        previous = entries[^1];
        entries.RemoveAt(entries.Count - 1);
        return true;
    }
}
