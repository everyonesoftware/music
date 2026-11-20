using System.Net;
using Music.Core;
using Xunit;
using static Music.Core.Tests.ClientTests;

namespace Music.Core.Tests;

public sealed class ArtistTests
{
    internal static HttpResponseMessage Json(string body) => new(HttpStatusCode.OK)
    { Content = new StringContent("{\"subsonic-response\":{\"status\":\"ok\"," + body + "}}") };

    [Fact]
    public async Task Detail_collects_every_album_in_response_order_and_preserves_song_occurrences()
    {
        var requests = new List<string>();
        using var handler = new Handler(request =>
        {
            var uri = request.RequestUri!; requests.Add(uri.AbsolutePath);
            if (uri.AbsolutePath.EndsWith("getArtist.view"))
                return Json("\"artist\":{\"id\":\"a\",\"name\":\"Same\",\"albumCount\":2,\"album\":[{\"id\":\"x\",\"name\":\"First\",\"songCount\":2},{\"id\":\"y\",\"name\":\"Second\",\"songCount\":1}]}");
            bool first = uri.Query.Contains("id=x");
            return Json(first
                ? "\"album\":{\"id\":\"x\",\"name\":\"First\",\"song\":[{\"id\":\"s\",\"title\":\"One\"},{\"id\":\"t\",\"title\":\"Two\"}]}"
                : "\"album\":{\"id\":\"y\",\"name\":\"Second\",\"song\":[{\"id\":\"s\",\"title\":\"One again\"}]}");
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        var detail = await client.GetArtistAsync("a");
        Assert.Equal(new[] { "x", "y" }, detail.Albums.Select(a => a.Id));
        Assert.Equal(new[] { "s", "t", "s" }, detail.Songs.Select(s => s.Id));
        Assert.True(detail.IsComplete);
        Assert.Equal("Second", detail.Songs[2].Album);
        Assert.Equal(3, requests.Count);
    }

    [Theory]
    [InlineData("missing")]
    [InlineData("failure")]
    [InlineData("truncated")]
    [InlineData("detail-count")]
    [InlineData("invalid-title")]
    [InlineData("wrong-id")]
    public async Task Failed_or_incomplete_album_preserves_healthy_albums_and_songs(string fault)
    {
        using var handler = new Handler(request =>
        {
            if (request.RequestUri!.AbsolutePath.EndsWith("getArtist.view"))
                return Json("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"albumCount\":3,\"album\":[{\"id\":\"x\",\"name\":\"First\",\"songCount\":1},{\"id\":\"y\",\"name\":\"Second\"" +
                    (fault is "detail-count" or "invalid-title" ? "" : ",\"songCount\":2") + "},{\"id\":\"z\",\"name\":\"Third\",\"songCount\":1}]}");
            if (request.RequestUri.Query.Contains("id=x")) return Json("\"album\":{\"id\":\"x\",\"name\":\"First\",\"song\":[{\"id\":\"s\",\"title\":\"One\"}]}");
            if (request.RequestUri.Query.Contains("id=z")) return Json("\"album\":{\"id\":\"z\",\"name\":\"Third\",\"song\":[{\"id\":\"s\",\"title\":\"One again\"}]}");
            if (fault == "failure") return new HttpResponseMessage(HttpStatusCode.ServiceUnavailable);
            if (fault == "missing") return new HttpResponseMessage(HttpStatusCode.OK)
            { Content = new StringContent("""{"subsonic-response":{"status":"failed","error":{"code":70}}}""") };
            if (fault == "invalid-title") return Json("\"album\":" + """{"id":"y","name":"Second","song":[{"id":"t","title":"\uD800"}]}""");
            return Json("\"album\":{\"id\":\"" + (fault == "wrong-id" ? "other" : "y") + "\",\"name\":\"Second\"" +
                (fault == "detail-count" ? ",\"songCount\":2" : "") + ",\"song\":[{\"id\":\"t\",\"title\":\"Two\"}]}");
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        var detail = await client.GetArtistAsync("a");
        Assert.Equal(new[] { "x", "y", "z" }, detail.Albums.Select(a => a.Id));
        Assert.Equal(new[] { "s", "s" }, detail.Songs.Select(s => s.Id));
        Assert.Equal(new[] { "First", "Third" }, detail.Songs.Select(s => s.Album));
        Assert.False(detail.IsComplete);
        if (fault == "detail-count")
        {
            await Assert.ThrowsAsync<NavidromeException>(() => client.GetAlbumSongsAsync("y", validateCount: true));
            Assert.Single(await client.GetAlbumSongsAsync("y"));
        }
    }

    [Fact]
    public async Task Cancellation_between_albums_never_publishes_partial_result()
    {
        using var cancellation = new CancellationTokenSource();
        int albums = 0;
        using var handler = new Handler(request =>
        {
            if (request.RequestUri!.AbsolutePath.EndsWith("getArtist.view"))
                return Json("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"album\":[{\"id\":\"x\",\"name\":\"First\"},{\"id\":\"y\",\"name\":\"Second\"}]}");
            albums++; cancellation.Cancel();
            return Json("\"album\":{\"id\":\"x\",\"name\":\"First\",\"song\":[{\"id\":\"s\",\"title\":\"One\"}]}");
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => client.GetArtistAsync("a", cancellation.Token));
        Assert.Equal(1, albums);
    }

    [Fact]
    public async Task Wrong_artist_identity_still_fails_closed()
    {
        using var handler = new Handler(_ => Json("\"artist\":{\"id\":\"wrong\",\"name\":\"Same\"}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        await Assert.ThrowsAsync<NavidromeException>(() => client.GetArtistAsync("a"));
    }

    [Theory]
    [InlineData("2", "")]
    [InlineData("0", "")]
    [InlineData("null", "")]
    [InlineData("\"invalid\"", "")]
    [InlineData("2", "null,")]
    [InlineData("2", "{\"id\":\"bad\",\"name\":\"\"},")]
    public async Task Missing_or_malformed_album_summary_does_not_hide_healthy_album(string declaredCount, string malformed)
    {
        using var handler = new Handler(request => request.RequestUri!.AbsolutePath.EndsWith("getArtist.view")
            ? Json("\"artist\":" + $$"""{"id":"a","name":"Artist","albumCount":{{declaredCount}},"album":[{{malformed}}{"id":"x","name":"First"}]}""")
            : Json("\"album\":{\"id\":\"x\",\"name\":\"First\",\"song\":[{\"id\":\"s\",\"title\":\"One\"}]}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        var detail = await client.GetArtistAsync("a");
        Assert.Equal("x", Assert.Single(detail.Albums).Id);
        Assert.Equal("s", Assert.Single(detail.Songs).Id);
        Assert.False(detail.IsComplete);
    }

    [Fact]
    public async Task Malformed_optional_summary_metadata_does_not_hide_album_or_invalidate_tracks()
    {
        using var handler = new Handler(request => request.RequestUri!.AbsolutePath.EndsWith("getArtist.view")
            ? Json("\"artist\":" + """{"id":"a","name":"Artist","coverArt":{},"albumCount":1,"album":[{"id":"x","name":"First","artist":7,"coverArt":"\uD800","year":"invalid"}]}""")
            : Json("\"album\":" + """{"id":"x","name":"First","song":[{"id":"s","title":"One"}]}"""));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        var detail = await client.GetArtistAsync("a");
        var album = Assert.Single(detail.Albums);
        Assert.Equal("Artist", album.Artist);
        Assert.Null(album.CoverArtId);
        Assert.Equal(0, album.Year);
        Assert.Equal("s", Assert.Single(detail.Songs).Id);
        Assert.True(detail.IsComplete);
    }

    [Fact]
    public async Task Duplicate_album_id_does_not_duplicate_songs_or_certify_completeness()
    {
        using var handler = new Handler(request => request.RequestUri!.AbsolutePath.EndsWith("getArtist.view")
            ? Json("\"artist\":{\"id\":\"a\",\"name\":\"Artist\",\"albumCount\":2,\"album\":[{\"id\":\"x\",\"name\":\"First\"},{\"id\":\"x\",\"name\":\"Duplicate\"}]}")
            : Json("\"album\":{\"id\":\"x\",\"name\":\"First\",\"song\":[{\"id\":\"s\",\"title\":\"One\"}]}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        var detail = await client.GetArtistAsync("a");
        Assert.Equal("First", Assert.Single(detail.Albums).Name);
        Assert.Equal("s", Assert.Single(detail.Songs).Id);
        Assert.False(detail.IsComplete);
    }

    [Fact]
    public async Task Empty_artist_library_is_empty_without_song_search()
    {
        using var handler = new Handler(request =>
        {
            Assert.EndsWith("/getArtist.view", request.RequestUri!.AbsolutePath);
            return Json("\"artist\":{\"id\":\"a\",\"name\":\"Empty\",\"albumCount\":0}");
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        var library = await client.GetArtistAsync("a");
        Assert.True(library.IsComplete);
        Assert.Empty(library.Albums); Assert.Empty(library.Songs);
        client.Offline = true;
        await Assert.ThrowsAsync<NavidromeException>(() => client.GetArtistsAsync());
        await Assert.ThrowsAsync<NavidromeException>(() => client.GetArtistAsync("a"));
    }

    [Fact]
    public async Task Full_ID3_index_preserves_distinct_ids_with_identical_names()
    {
        using var handler = new Handler(request =>
        {
            Assert.EndsWith("/getArtists.view", request.RequestUri!.AbsolutePath);
            return Json("\"artists\":{\"index\":[{\"name\":\"A\",\"artist\":[{\"id\":\"a\",\"name\":\"Same\",\"albumCount\":2,\"coverArt\":\"art\"}]},{\"name\":\"B\",\"artist\":[{\"id\":\"b\",\"name\":\"Same\",\"albumCount\":1}]}]}");
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        var artists = await client.GetArtistsAsync();
        Assert.Equal(new[] { "a", "b" }, artists.Select(a => a.Id));
        Assert.All(artists, a => Assert.Equal("Same", a.Name));
        Assert.Equal("art", artists[0].CoverArtId);
        Assert.Equal(2, artists[0].AlbumCount);
    }
}
