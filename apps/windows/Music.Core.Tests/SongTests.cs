using Music.Core;
using Xunit;
using static Music.Core.Tests.ClientTests;

namespace Music.Core.Tests;

public sealed class SongTests
{
    internal const string SongJson = "{\"id\":\"s&=雪\",\"title\":\"Song\",\"artist\":\"Artist\",\"album\":\"Album\",\"coverArt\":\"art\",\"duration\":123,\"userRating\":4}";
    internal static Song Expected => new("s&=雪", "Song", "Artist", "Album", "art", 123, 4);

    [Theory]
    [InlineData("a", false)] [InlineData("other", true)]
    public async Task Album_songs_inherit_metadata_and_verify_album_identity(string responseId, bool reject)
    {
        using var handler = new Handler(_ => Json(",\"album\":{\"id\":\"" + responseId + "\",\"name\":\"Album\",\"artist\":\"Artist\",\"coverArt\":\"art\",\"song\":[{\"id\":\"s\",\"title\":\"Title\",\"duration\":42}]}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        if (reject) await Assert.ThrowsAsync<NavidromeException>(() => client.GetAlbumSongsAsync("a"));
        else Assert.Equal(new Song("s", "Title", "Artist", "Album", "art", 42), Assert.Single(await client.GetAlbumSongsAsync("a")));
        Assert.Equal("/sub/rest/getAlbum.view", handler.Requests.Single().AbsolutePath);
        Assert.Equal("a", Query(handler.Requests.Single())["id"]);
    }

    [Fact]
    public async Task Search_sends_escaped_query_and_paging_without_artist_album_results()
    {
        using var handler = new Handler(_ => Json(",\"searchResult3\":{\"song\":[" + SongJson + "]}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        Assert.Equal(Expected, Assert.Single(await client.SearchSongsAsync("雪 & t=oops /?", 25, 50)));
        var uri = handler.Requests.Single();
        Assert.Equal("/sub/rest/search3.view", uri.AbsolutePath);
        var q = Query(uri);
        Assert.Equal("雪 & t=oops /?", q["query"]);
        Assert.Equal("25", q["songOffset"]);
        Assert.Equal("50", q["songCount"]);
        Assert.Equal("0", q["albumCount"]);
        Assert.Equal("0", q["artistCount"]);
    }

    [Theory]
    [InlineData(-1, 10)] [InlineData(0, 0)] [InlineData(0, 501)]
    public async Task Search_rejects_invalid_paging(int offset, int count)
    {
        using var handler = new Handler(_ => Json());
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => client.SearchSongsAsync("q", offset, count));
        Assert.Empty(handler.Requests);
    }

    [Fact]
    public async Task Playlist_songs_preserve_order_duplicates_and_escape_id()
    {
        using var handler = new Handler(_ => Json(",\"playlist\":{\"entry\":[" + SongJson + "," + SongJson + "]}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        Assert.Equal(new[] { Expected, Expected }, await client.GetPlaylistSongsAsync("p&x=雪"));
        var uri = handler.Requests.Single();
        Assert.Equal("/sub/rest/getPlaylist.view", uri.AbsolutePath);
        Assert.Equal("p&x=雪", Query(uri)["id"]);
    }
}
