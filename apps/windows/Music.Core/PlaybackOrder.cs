namespace Music.Core;

/// <summary>Resolves randomness once: prefetch and the Next command consume the same plan.</summary>
public sealed class PlaybackOrder(int count, int current, bool shuffle, bool repeat, Random? random = null)
{
    private readonly Random random = random ?? Random.Shared;
    private readonly List<int> upcoming = [];
    private readonly List<int> history = [];
    private int replayCount;
    private const int HistoryLimit = 100;
    private void RememberCurrent()
    {
        if (history.Count == HistoryLimit) history.RemoveAt(0);
        history.Add(current);
    }
    public int MovePrevious()
    {
        int index = history.Count > 0 ? history[^1] : current > 0 ? current - 1 : repeat ? Math.Max(0, count - 1) : 0;
        if (index == current) return current;
        if (history.Count > 0) history.RemoveAt(history.Count - 1);
        upcoming.Insert(0, current);
        replayCount = Math.Min(HistoryLimit, replayCount + 1);
        if (upcoming.Count > HistoryLimit) upcoming.RemoveRange(HistoryLimit, upcoming.Count - HistoryLimit);
        current = index;
        return current;
    }
    private int current = current;
    private bool shuffle = shuffle, repeat = repeat;
    public void Configure(bool shuffled, bool repeated)
    {
        if (shuffle == shuffled && repeat == repeated) return;
        shuffle = shuffled; repeat = repeated;
        // Toggle changes invalidate guesses, not songs already traversed with Previous.
        upcoming.RemoveRange(replayCount, upcoming.Count - replayCount);
    }
    public void Select(int index)
    {
        if (index < 0 || index >= count) throw new ArgumentOutOfRangeException(nameof(index));
        if (index == current) return;
        RememberCurrent();
        if (upcoming.Count > 0 && upcoming[0] == index)
        {
            upcoming.RemoveAt(0);
            replayCount = Math.Max(0, replayCount - 1);
        }
        else { upcoming.Clear(); replayCount = 0; }
        current = index;
    }
    public IReadOnlyList<int> Upcoming(int requested)
    {
        if (requested < 0 || requested > 100) throw new ArgumentOutOfRangeException(nameof(requested));
        if (count == 0) return [];
        while (upcoming.Count < requested)
        {
            int last = upcoming.Count > 0 ? upcoming[^1] : current;
            int next;
            if (shuffle && count > 1) { next = random.Next(count - 1); if (next >= last) next++; }
            else { next = last + 1; if (next >= count) { if (!repeat) break; next = 0; } }
            upcoming.Add(next);
        }
        return upcoming.Take(requested).ToArray();
    }
    public int? MoveNext()
    {
        var next = Upcoming(1);
        if (next.Count == 0) return null;
        int index = next[0];
        // A one-item repeated queue still consumes a planned step.
        upcoming.RemoveAt(0);
        replayCount = Math.Max(0, replayCount - 1);
        if (index != current) RememberCurrent();
        current = index;
        return index;
    }
}
