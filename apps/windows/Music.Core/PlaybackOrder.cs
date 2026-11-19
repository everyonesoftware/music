namespace Music.Core;

public sealed record PlaybackVisit(long Id, int Index);

/// <summary>One resolved Next/prefetch plan with bounded, evidence-based listening history.
/// Selection is intent; only MarkStarted (native Playing evidence) makes a visit eligible.
/// This queue-scoped history is deliberately not persisted: restored sources start paused.
/// </summary>
public sealed class PlaybackOrder(int count, int current, bool shuffle, bool repeat, Random? random = null)
{
    private readonly Random random = random ?? Random.Shared;
    private readonly List<int> upcoming = [];
    private readonly List<int> history = [];
    private readonly List<PlaybackVisit> played = [];
    private int replayCount;
    private const int HistoryLimit = 100;
    // Only this small evidence cell is shared with native callbacks. Queue/history
    // remain dispatcher-owned; closing a visit linearizes evidence with departure.
    private sealed class StartEvidence
    {
        public bool Started, Closed;
        public long Source;
    }
    private StartEvidence evidence = new();
    private long visitId;
    private int current = current;
    private bool shuffle = shuffle, repeat = repeat;
    public int Current => current;
    public IReadOnlyList<PlaybackVisit> Played => played.AsReadOnly();
    public bool Ended { get; private set; }
    public void MarkStarted() { if (!Ended && count > 0) { lock (evidence) evidence.Started = true; } }
    /// <summary>Bind on the dispatcher; invoke on the native Playing callback.
    /// A replacement source invalidates older callbacks without clearing this visit's evidence.</summary>
    public Action CaptureStartEvidence()
    {
        var visit = evidence;
        long source;
        lock (visit) source = ++visit.Source;
        bool eligible = !Ended && count > 0;
        return () =>
        {
            lock (visit)
                if (eligible && !visit.Closed && source == visit.Source) visit.Started = true;
        };
    }
    private void Depart(bool remember)
    {
        bool started;
        var visit = evidence;
        lock (visit) { visit.Closed = true; started = visit.Started; }
        evidence = new();
        if (!started) return;
        if (remember)
        {
            if (history.Count == HistoryLimit) history.RemoveAt(0);
            history.Add(current);
        }
        if (played.Count == HistoryLimit) played.RemoveAt(0);
        played.Add(new(++visitId, current));
    }
    public void Finish()
    {
        if (Ended) return;
        Depart(false);
        Ended = true;
        upcoming.Clear(); replayCount = 0;
    }
    public bool SelectPlayed(long id)
    {
        int entry = played.FindIndex(x => x.Id == id);
        if (entry < 0) return false;
        int index = played[entry].Index;
        played.RemoveAt(entry);
        if (index == current) Depart(true);
        Select(index);
        return true;
    }
    public int MovePrevious()
    {
        if (Ended && played.Count > 0)
        {
            SelectPlayed(played[^1].Id);
            return current;
        }
        int index = history.Count > 0 ? history[^1] : current > 0 ? current - 1 : repeat ? Math.Max(0, count - 1) : 0;
        if (index == current && history.Count == 0) return current;
        if (history.Count > 0)
        {
            history.RemoveAt(history.Count - 1);
            int entry = played.FindLastIndex(x => x.Index == index);
            if (entry >= 0) played.RemoveAt(entry);
        }
        Depart(false);
        upcoming.Insert(0, current);
        replayCount = Math.Min(HistoryLimit, replayCount + 1);
        if (upcoming.Count > HistoryLimit) upcoming.RemoveRange(HistoryLimit, upcoming.Count - HistoryLimit);
        current = index; Ended = false;
        return current;
    }
    public void Configure(bool shuffled, bool repeated)
    {
        if (shuffle == shuffled && repeat == repeated) return;
        shuffle = shuffled; repeat = repeated;
        // Toggle changes invalidate guesses, not songs traversed with Previous.
        upcoming.RemoveRange(replayCount, upcoming.Count - replayCount);
    }
    public void Select(int index)
    {
        if (index < 0 || index >= count) throw new ArgumentOutOfRangeException(nameof(index));
        if (index == current) { Ended = false; return; }
        Depart(true);
        if (upcoming.Count > 0 && upcoming[0] == index)
        {
            upcoming.RemoveAt(0);
            replayCount = Math.Max(0, replayCount - 1);
        }
        else { upcoming.Clear(); replayCount = 0; }
        current = index; Ended = false;
    }
    public IReadOnlyList<int> Upcoming(int requested)
    {
        if (requested < 0 || requested > Math.Max(100, count)) throw new ArgumentOutOfRangeException(nameof(requested));
        if (count == 0 || Ended) return [];
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
        upcoming.RemoveAt(0);
        replayCount = Math.Max(0, replayCount - 1);
        // Repeating a singleton is a new visit, unlike same-source handoff.
        Depart(true);
        current = index; Ended = false;
        return index;
    }
}
