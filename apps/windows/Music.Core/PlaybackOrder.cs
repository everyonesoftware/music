namespace Music.Core;

public sealed record PlaybackVisit(long Id, int Index);

/// <summary>One resolved Next/prefetch plan with bounded, evidence-based listening history.
/// Selection is intent; only MarkStarted (native Playing evidence) makes a visit eligible.
/// This queue-scoped history is deliberately not persisted: restored sources start paused.
/// </summary>
public sealed class PlaybackOrder(int count, int current, bool shuffle, bool repeat, Random? random = null)
{
    private readonly Random random = random ?? Random.Shared;
    private readonly List<int> upcoming = InitialOrder(count, current, shuffle, random ?? Random.Shared);
    private readonly List<int> history = [];
    private readonly List<PlaybackVisit> played = [];
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
    private bool repeat = repeat;
    private static List<int> InitialOrder(int count, int current, bool shuffle, Random random)
    {
        var order = (shuffle ? Enumerable.Range(0, count).Where(i => i != current) : Enumerable.Range(current + 1, Math.Max(0, count - current - 1))).ToList();
        if (shuffle) random.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(order));
        return order;
    }
    public int Current => current;
    public static PlaybackOrder Restore(PlaybackSnapshot snapshot)
    {
        // Legacy Shuffle is deliberately ignored: reopening never re-enables a random mode.
        var order = new PlaybackOrder(snapshot.Queue.Count, snapshot.Index, false, snapshot.Repeat);
        if (snapshot.RemainingOrder is not null)
        {
            order.upcoming.Clear();
            order.upcoming.AddRange(snapshot.RemainingOrder);
        }
        return order;
    }
    /// <summary>The finite current cycle, by occurrence index (not song ID).</summary>
    public IReadOnlyList<int> RemainingOrder => upcoming.ToArray();
    public bool CanShufflePending => !Ended && upcoming.Count >= 2;
    public bool ShufflePending()
    {
        if (!CanShufflePending) return false;
        var before = upcoming.ToArray();
        random.Shuffle(System.Runtime.InteropServices.CollectionsMarshal.AsSpan(upcoming));
        // A click always visibly reorders distinct occurrences; it is never unshuffle.
        if (before.SequenceEqual(upcoming)) { int first = upcoming[0]; upcoming.RemoveAt(0); upcoming.Add(first); }
        return true;
    }
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
        upcoming.Clear();
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
        // Bound repeat-cycle replay without truncating the materialized unheard tail.
        if (repeat && upcoming.Count >= count + HistoryLimit) return current;
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
        if (!repeat) upcoming.RemoveAll(i => i == index || i == current);
        upcoming.Insert(0, current);
        current = index; Ended = false;
        return current;
    }
    public void Configure(bool shuffled, bool repeated)
    {
        // A materialized order is never a persistent shuffle mode.
        if (repeat && !repeated)
        {
            var seen = new HashSet<int> { current };
            upcoming.RemoveAll(i => !seen.Add(i));
        }
        repeat = repeated;
    }
    /// <summary>Select the exact row in the materialized pending plan, including
    /// repeated occurrences of the current index. Unlike source handoff, this is a new visit.</summary>
    public bool SelectPending(int offset)
    {
        if (Ended || offset < 0 || offset >= upcoming.Count) return false;
        int index = upcoming[offset];
        Depart(true);
        upcoming.RemoveRange(0, offset + 1);
        current = index; Ended = false;
        return true;
    }
    public void Select(int index)
    {
        if (index < 0 || index >= count) throw new ArgumentOutOfRangeException(nameof(index));
        if (index == current) { Ended = false; return; }
        Depart(true);
        if (upcoming.Count > 0 && upcoming[0] == index)
        {
            upcoming.RemoveAt(0);
        }
        else
        {
            int selected = upcoming.IndexOf(index);
            if (selected >= 0) upcoming.RemoveRange(0, selected + 1);
        }
        current = index; Ended = false;
    }
    public IReadOnlyList<int> Upcoming(int requested)
    {
        if (requested < 0 || requested > Math.Max(100, count)) throw new ArgumentOutOfRangeException(nameof(requested));
        if (count == 0 || Ended) return [];
        var result = upcoming.Take(requested).ToList();
        if (repeat)
            while (result.Count < requested) result.Add((result.Count - upcoming.Count) % count);
        return result;
    }
    public int? MoveNext()
    {
        var next = Upcoming(1);
        if (next.Count == 0) return null;
        int index = next[0];
        if (upcoming.Count == 0) upcoming.AddRange(Enumerable.Range(0, count));
        upcoming.RemoveAt(0);
        // Repeating a singleton is a new visit, unlike same-source handoff.
        Depart(true);
        current = index; Ended = false;
        return index;
    }
}
