using Music.Core;
using Xunit;
using static Music.Core.Tests.SongTests;

namespace Music.Core.Tests;

public sealed class CollectionTests
{
    [Fact]
    public void Album_filter_matches_artist_name_and_year_without_reordering()
    {
        var album = new Album("a", "雪 EP", "Artist", null, 2, 2026);
        var albums = new[] { album, album with { Id = "b", Artist = "Other", Year = 2000 } };
        Assert.Equal(new[] { album }, CollectionSearch.FilterAlbums(albums, "artist 2026 雪"));
        Assert.Equal(albums, CollectionSearch.FilterAlbums(albums, ""));
    }

    [Fact]
    public void Playlist_filter_matches_name_tokens_in_source_order()
    {
        var mix = new Playlist("p", "雪 Evening MIX", null, 4);
        var lists = new[] { mix, mix with { Id = "q", Name = "Morning" }, mix };
        Assert.Equal(new[] { mix, mix }, CollectionSearch.FilterPlaylists(lists, "mix 雪"));
        Assert.Empty(CollectionSearch.FilterPlaylists(lists, "unknown"));
    }

    [Theory]
    [InlineData(" artist song ", 2)] [InlineData("ALBUM", 2)] [InlineData("missing", 0)] [InlineData("", 3)] [InlineData(null, 3)]
    public void Song_filter_matches_all_terms_across_metadata_preserving_order_and_occurrences(string? query, int count)
    {
        var songs = new[] { Expected, Expected with { Id = "other", Title = "雪", Artist = "Else", Album = "Else" }, Expected };
        var filtered = CollectionSearch.FilterSongs(songs, query);
        Assert.Equal(count, filtered.Count);
        if (count == 2) Assert.Equal(new[] { Expected, Expected }, filtered);
        Assert.Equal(3, songs.Length);
    }
}
