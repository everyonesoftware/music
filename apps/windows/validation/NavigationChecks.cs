using Music.App;

static class NavigationChecks
{
    public static void Run(Action<bool, string> check)
    {
        var history = new NavigationHistory();
        var library = new NavigationView("library", "", "grid");
        var playlists = new NavigationView("playlists", "road", "list");
        var playlist = new NavigationView("detail", "road", "list", "playlist-one");
        check(history.Record(library, playlists), "Library -> Playlists records origin");
        check(history.Record(playlists, playlist), "Playlists -> playlist records filtered list origin");
        check(history.TryBack(out var previous) && previous == playlists, "playlist Back restores Playlists query and layout");
        check(history.TryBack(out previous) && previous == library, "second Back restores Library without re-push");
        check(!history.TryBack(out _), "empty history Back is a no-op");
        check(!history.Record(playlists, playlists with { Query = "", Mode = "grid" }), "repeated sidebar destination does not add or reset a view");
        check(!history.TryBack(out _), "repeated sidebar does not manufacture a Back entry");
        var albums = new NavigationView("albums", "jazz", "list");
        var album = new NavigationView("detail", "jazz", "list", "album-one");
        check(history.Record(library, albums) && history.Record(albums, album), "album origin recorded");
        check(history.Record(album, new NavigationView("settings", "", "grid")), "settings records detail origin");
        check(history.TryBack(out previous) && previous == album, "settings Back restores previous album detail identity");
        check(history.TryBack(out previous) && previous == albums, "album Back restores albums query/layout");
        history.Clear();
        history.Record(library, playlist);
        history.Record(playlist, album);
        history.Record(album, playlists);
        history.RemoveCollection(collection => Equals(collection, "playlist-one"));
        check(history.TryBack(out previous) && previous == album, "deletion preserves Back to sibling album");
        check(history.TryBack(out previous) && previous == library, "deletion skips only the retired playlist route");
        history.Clear();
        check(!history.TryBack(out _), "account replacement/offline reset removes scoped history");
        history.Record(albums, new NavigationView("settings", "", "grid"));
        check(history.TryBack(out previous) && previous == albums, "settings Back restores filtered collection view");
        history.Record(library, playlist);
        history.Clear();
        check(!history.TryBack(out _), "reset abandons old collection identity");
        history.Record(library, albums);
        check(history.TryBack(out previous) && previous == library && !history.TryBack(out _), "new scope starts a fresh independent history");
        using var requests = new RequestGate();
        history.Record(library, playlist);
        var loading = requests.Begin();
        check(history.TryBack(out previous) && previous == library, "loading detail can be abandoned before completion");
        requests.Cancel();
        check(!requests.IsCurrent(loading) && loading.Token.IsCancellationRequested, "abandoned detail completion is fenced");
        check(!history.TryBack(out _), "Back never re-pushes abandoned detail");

        for (int i = 0; i < 100; i++) history.Record(library with { Query = i.ToString() }, albums);
        int count = 0;
        while (history.TryBack(out _)) count++;
        check(count == 64, "in-memory history is bounded");
    }
}
