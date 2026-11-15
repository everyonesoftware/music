namespace Music.Core;

/// <summary>Local filtering of loaded collections; preserves source ordering and duplicate occurrences.</summary>
public static class CollectionSearch
{
    public static IReadOnlyList<Song> FilterSongs(IEnumerable<Song> songs, string? query) =>
        Filter(songs, query, song => song.Title + " " + song.Artist + " " + song.Album);

    public static IReadOnlyList<Album> FilterAlbums(IEnumerable<Album> albums, string? query) =>
        Filter(albums, query, album => album.Name + " " + album.Artist + " " + album.Year.ToString(System.Globalization.CultureInfo.InvariantCulture));

    public static IReadOnlyList<Playlist> FilterPlaylists(IEnumerable<Playlist> playlists, string? query) =>
        Filter(playlists, query, playlist => playlist.Name);

    private static IReadOnlyList<T> Filter<T>(IEnumerable<T> source, string? query, Func<T, string> text)
    {
        var terms = (query ?? "").Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        return source.Where(item => terms.All(term => text(item).Contains(term, StringComparison.OrdinalIgnoreCase))).ToArray();
    }
}
