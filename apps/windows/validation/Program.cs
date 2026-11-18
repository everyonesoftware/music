using Music.App;
int assertions = 0;
void Check(bool value, string message) { assertions++; if (!value) throw new Exception(message); }
using var gate = new RequestGate();
var first = gate.Begin();
var second = gate.Begin();
Check(first.Token.IsCancellationRequested, "superseded work must cancel");
Check(!gate.IsCurrent(first), "stale request cannot publish");
Check(gate.IsCurrent(second), "latest request may publish");
gate.Cancel();
Check(!gate.IsCurrent(second) && second.Token.IsCancellationRequested, "navigation invalidates active work");
Check(BrowsePolicy.DefaultMode("songs") == "list", "songs start in list mode");
Check(BrowsePolicy.DefaultMode("albums") == "grid", "albums start in grid mode");
Check(BrowsePolicy.DefaultMode("playlists") == "grid", "playlists start in grid mode");
Check(BrowsePolicy.ShowSection("library", "albums", 2), "library includes albums");
Check(!BrowsePolicy.ShowSection("library", "songs", 2), "library landing page hides songs");
Check(!BrowsePolicy.ShowSection("library", "songs", 2, "   "), "blank library search hides songs");
Check(BrowsePolicy.ShowSection("library", "songs", 2, "track"), "library search still shows matching songs");
Check(!BrowsePolicy.ShowSection("library", "songs", 0, "track"), "empty song search results stay hidden");
Check(BrowsePolicy.ShowSection("songs", "songs", 2), "dedicated songs page remains available");
Check(BrowsePolicy.ShowSection("library", "playlists", 2), "library includes playlists");
Check(!BrowsePolicy.ShowSection("library", "albums", 0), "zero match sections hidden");
Check(!BrowsePolicy.ShowSection("songs", "albums", 2), "dedicated page isolates collection");
Check(BrowsePolicy.ShowViewAll("library") && !BrowsePolicy.ShowViewAll("albums"), "view all links only in library");
Check(ConnectionIdentity.SameAccount("https://EXAMPLE.test/music", "dan", "https://example.test:443/music/", "dan"), "same normalized account keeps its session");
Check(!ConnectionIdentity.SameAccount("https://example.test/music", "dan", "https://other.test/music/", "dan"), "different host never receives another server's queue");
Check(!ConnectionIdentity.SameAccount("https://example.test/music", "dan", "https://example.test/other/", "dan"), "server path isolates accounts");
Check(!ConnectionIdentity.SameAccount("https://example.test/music", "dan", "https://example.test/music/", "Dan"), "username comparison preserves server-defined case");
Check(!ConnectionIdentity.SameAccount("https://example.test/Music", "dan", "https://example.test/music/", "dan"), "server path case is significant");
Check(!ConnectionIdentity.SameAccount("not a URL", "dan", "https://example.test/music/", "dan"), "invalid stored URL cannot inherit a session");
var transport = new CollectionTransport();
Check(!transport.IsShuffled("account", "playlist", "one"), "new collection defaults to ordered");
transport.SetShuffled("account", "playlist", "one", true);
Check(transport.IsShuffled("account", "playlist", "one"), "mode remembered for collection");
Check(!transport.IsShuffled("other", "playlist", "one"), "mode isolated by account");
Check(!transport.IsShuffled("account", "album", "one"), "album and playlist identities isolated");
Check(!transport.IsShuffled("account", "playlist", "two"), "mode isolated by collection");
transport.SetShuffled("account", "playlist", "one", false);
Check(!transport.IsShuffled("account", "playlist", "one"), "toggle can restore ordered mode");
var occurrences = new[] { "first", "duplicate", "last", "duplicate" };
string[]? ordered = transport.Prepare(occurrences, true, false, new FirstSlotRandom());
Check(ordered!.SequenceEqual(occurrences) && !ReferenceEquals(ordered, occurrences), "ordered Play copies all occurrences");
string[]? shuffled = transport.Prepare(occurrences, true, true, new FirstSlotRandom());
Check(shuffled!.Order().SequenceEqual(occurrences.Order()), "shuffle preserves every duplicate occurrence");
Check(!shuffled.SequenceEqual(occurrences), "shuffle permutes occurrence positions");
Check(occurrences[0] == "first" && occurrences[2] == "last", "preparation never mutates source order");
Check(!ReferenceEquals(ordered, transport.Prepare(occurrences, true, false)), "repeat Play creates a fresh restart queue");
Check(transport.Prepare(occurrences, false, true) is null, "unavailable detail cannot replace playback");
Check(transport.Prepare(Array.Empty<string>(), true, true) is null, "empty detail cannot replace playback");
for (int flags = 0; flags < 16; flags++)
{
    bool loading = (flags & 1) != 0, error = (flags & 2) != 0, offline = (flags & 4) != 0, current = (flags & 8) != 0;
    Check(transport.CanStart(loading, error, offline, current, 4) == (!loading && !error && !offline && current), "loading/error/offline/stale lease blocks transport");
}
Check(!transport.CanStart(false, false, false, true, 0), "empty detail disabled");
NavigationChecks.Run(Check);
Console.WriteLine($"PASS: cancellation/version isolation, browsing policy, connection identity, collection transport and navigation ({assertions} assertions)");
sealed class FirstSlotRandom : Random { public override int Next(int maxValue) => 0; }
