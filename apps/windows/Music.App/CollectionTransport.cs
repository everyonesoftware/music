namespace Music.App;

// Future-start preference only; never owns or changes the active listening session.
public sealed class CollectionTransport
{
    private readonly HashSet<(string Account, string Kind, string Id)> shuffled = new();
    public bool IsShuffled(string account, string kind, string id) => shuffled.Contains((account, kind, id));
    public bool CanStart(bool loading, bool error, bool offline, bool current, int count) =>
        !loading && !error && !offline && current && count > 0;

    public T[]? Prepare<T>(IReadOnlyList<T> tracks, bool available, bool shuffle, Random? random = null)
    {
        if (!available || tracks.Count == 0) return null;
        var result = tracks.ToArray();
        if (shuffle)
        {
            random ??= Random.Shared;
            for (int i = result.Length - 1; i > 0; i--)
            {
                int j = random.Next(i + 1);
                (result[i], result[j]) = (result[j], result[i]);
            }
        }
        return result;
    }
    public void SetShuffled(string account, string kind, string id, bool value)
    {
        if (value) shuffled.Add((account, kind, id));
        else shuffled.Remove((account, kind, id));
    }
}
