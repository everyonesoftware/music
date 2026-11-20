using Music.Core;
using Xunit;
using static Music.Core.Tests.ClientTests;

namespace Music.Core.Tests;

public sealed class RatingTests
{
    [Theory]
    [InlineData("s", 2, false)] [InlineData("other", 2, true)] [InlineData("s", 6, true)]
    public async Task Rating_is_returned_only_after_readback_and_matching_identity(string returnedId, int returnedRating, bool reject)
    {
        using var handler = new Handler(r => r.RequestUri!.AbsolutePath.EndsWith("setRating.view", StringComparison.Ordinal) ? Json() : Json(",\"song\":{\"id\":\"" + returnedId + "\",\"userRating\":" + returnedRating + "}"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        if (reject) await Assert.ThrowsAsync<NavidromeException>(() => client.RateSongAsync("s", 5));
        else Assert.Equal(2, await client.RateSongAsync("s", 5));
        Assert.Equal(new[] { "/sub/rest/setRating.view", "/sub/rest/getSong.view" }, handler.Requests.Select(x => x.AbsolutePath));
        Assert.Equal("5", Query(handler.Requests[0])["rating"]);
        Assert.All(handler.Requests, uri => Assert.Equal("s", Query(uri)["id"]));
        Assert.NotEqual(Query(handler.Requests[0])["s"], Query(handler.Requests[1])["s"]);
    }

    [Theory]
    [InlineData(-1)] [InlineData(6)]
    public async Task Invalid_rating_is_rejected_before_network(int rating)
    {
        using var handler = new Handler(_ => Json());
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        await Assert.ThrowsAsync<ArgumentOutOfRangeException>(() => client.RateSongAsync("s", rating));
        Assert.Empty(handler.Requests);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Failed_readback_after_acknowledgment_reconciles_before_the_next_edit_and_undo(bool reopen)
    {
        int actual = 2;
        bool failRead = false;
        bool failAfterFirstWrite = true;
        using var handler = new Handler(request =>
        {
            var uri = request.RequestUri!;
            if (uri.AbsolutePath.EndsWith("setRating.view", StringComparison.Ordinal))
            {
                actual = int.Parse(Query(uri)["rating"], System.Globalization.CultureInfo.InvariantCulture);
                failRead = failAfterFirstWrite;
                failAfterFirstWrite = false;
                return Json();
            }
            if (failRead) { failRead = false; return Json(",\"song\":{\"id\":\"wrong\"}"); }
            return Json($",\"song\":{{\"id\":\"s\",\"userRating\":{actual}}}");
        });

        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        var state = new SongRatingSession(client.RateSongAsync, client.GetSongRatingAsync);
        var song = new Song("s", "Track", "Artist", "Album", null, 60, 2);
        await Assert.ThrowsAsync<NavidromeException>(() => state.SetAsync(song, 5));
        Assert.Equal(5, actual);
        if (reopen) state = new SongRatingSession(client.RateSongAsync, client.GetSongRatingAsync);
        Assert.Equal(new SongRatingChange("s", 5, 2), await state.SetAsync(song, 2));
        Assert.Equal(new SongRatingChange("s", 2, 5), await state.UndoAsync("s"));
        Assert.Equal(5, actual);
    }
}
