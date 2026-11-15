using System.Net;
using Music.Core;
using Xunit;
using static Music.Core.Tests.ClientTests;

namespace Music.Core.Tests;

public sealed class ResponseTests
{
    [Theory]
    [InlineData(401)] [InlineData(403)] [InlineData(302)] [InlineData(500)]
    public async Task Http_errors_are_sanitized(int status)
    {
        using var handler = new Handler(_ => new HttpResponseMessage((HttpStatusCode)status) { ReasonPhrase = "private-secret", Content = new StringContent("private-secret") });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "private-secret", http);
        var ex = await Assert.ThrowsAsync<NavidromeException>(() => client.PingAsync());
        Assert.DoesNotContain("private-secret", ex.ToString());
        Assert.DoesNotContain("https://", ex.ToString());
        Assert.Null(ex.InnerException);
    }

    [Theory]
    [InlineData("not-json")]
    [InlineData("{}")]
    [InlineData("{\"subsonic-response\":{\"status\":\"failed\",\"error\":{\"code\":40,\"message\":\"private-secret\"}}}")]
    [InlineData("{\"subsonic-response\":{\"status\":\"ok\",\"error\":{\"message\":\"private-secret\"}}}")]
    public async Task Invalid_and_failed_envelopes_do_not_leak_server_details(string body)
    {
        using var handler = new Handler(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(body) });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "private-secret", http);
        var ex = await Assert.ThrowsAsync<NavidromeException>(() => client.PingAsync());
        Assert.DoesNotContain("private-secret", ex.ToString());
        Assert.Null(ex.InnerException);
    }

    [Fact]
    public async Task Transport_error_does_not_retain_exception_with_authenticated_uri()
    {
        using var handler = new Handler(request => throw new HttpRequestException(request.RequestUri + "private-secret"));
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "private-secret", http);
        var ex = await Assert.ThrowsAsync<NavidromeException>(() => client.PingAsync());
        Assert.DoesNotContain("private-secret", ex.ToString());
        Assert.DoesNotContain("https://", ex.ToString());
        Assert.Null(ex.InnerException);
    }

    [Theory]
    [InlineData(false)] [InlineData(true)]
    public async Task Json_size_limit_is_enforced_with_missing_or_lying_content_length(bool lieAboutLength)
    {
        using var handler = new Handler(_ =>
        {
            var content = new StreamContent(new NonSeekableStream(System.Text.Encoding.UTF8.GetBytes("{\"subsonic-response\":{\"status\":\"ok\",\"padding\":\"" + new string('x', 8 * 1024 * 1024) + "\"}}")));
            if (lieAboutLength) content.Headers.ContentLength = 1;
            else Assert.Null(content.Headers.ContentLength);
            return new HttpResponseMessage(HttpStatusCode.OK) { Content = content };
        });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        await Assert.ThrowsAsync<NavidromeException>(() => client.PingAsync());
    }
}
