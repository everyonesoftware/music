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
}
