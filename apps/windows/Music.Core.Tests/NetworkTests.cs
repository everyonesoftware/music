using System.Net;
using System.Net.Sockets;
using System.Text;
using Music.Core;
using Xunit;
using static Music.Core.Tests.ClientTests;

namespace Music.Core.Tests;

public sealed class NetworkTests
{
    private sealed class Loopback : IDisposable
    {
        private readonly TcpListener listener = new(IPAddress.Loopback, 0);
        public string Url { get; }
        public bool HasPending => listener.Pending();
        public Loopback()
        {
            listener.Start();
            Url = "http://127.0.0.1:" + ((IPEndPoint)listener.LocalEndpoint).Port + "/nav/";
        }
        public async Task<string> Respond(string status, string headers, string body)
        {
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            using var socket = await listener.AcceptTcpClientAsync(timeout.Token);
            await using var stream = socket.GetStream();
            using var reader = new StreamReader(stream, Encoding.ASCII, leaveOpen: true);
            var first = await reader.ReadLineAsync(timeout.Token);
            while (await reader.ReadLineAsync(timeout.Token) is { Length: > 0 }) { }
            var bytes = Encoding.UTF8.GetBytes(body);
            await stream.WriteAsync(Encoding.ASCII.GetBytes("HTTP/1.1 " + status + "\r\nConnection: close\r\nContent-Length: " + bytes.Length + "\r\n" + headers + "\r\n"), timeout.Token);
            await stream.WriteAsync(bytes, timeout.Token);
            return first!;
        }
        public void Dispose() => listener.Stop();
    }

    [Fact]
    public async Task Real_loopback_http_works_only_with_explicit_opt_in()
    {
        using var server = new Loopback();
        Assert.Throws<ArgumentException>(() => new NavidromeClient(Profile(server.Url), "secret"));
        var received = server.Respond("200 OK", "Content-Type: application/json\r\n", "{\"subsonic-response\":{\"status\":\"ok\"}}");
        using var client = new NavidromeClient(Profile(server.Url), "secret", allowLoopbackHttp: true);
        await client.PingAsync();
        Assert.StartsWith("GET /nav/rest/ping.view?", await received);
    }

    [Fact]
    public async Task Default_handler_never_follows_redirect_to_another_origin()
    {
        using var source = new Loopback();
        using var destination = new Loopback();
        var received = source.Respond("302 Found", "Location: " + destination.Url + "stolen\r\n", "");
        using var client = new NavidromeClient(Profile(source.Url), "secret", allowLoopbackHttp: true);
        var ex = await Assert.ThrowsAsync<NavidromeException>(() => client.PingAsync());
        Assert.Contains("redirected", ex.Message);
        await received;
        Assert.False(destination.HasPending);
    }

    private sealed class WaitingStream(TaskCompletionSource entered) : Stream
    {
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override async ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken cancellationToken = default)
        {
            entered.TrySetResult();
            await Task.Delay(Timeout.Infinite, cancellationToken);
            return 0;
        }
        public override int Read(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        public override void Flush() => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }

    [Fact]
    public async Task Caller_cancellation_remains_connected_while_reading_body_and_has_no_inner_uri()
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var handler = new Handler(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new WaitingStream(entered)) });
        using var http = new HttpClient(handler);
        using var client = new NavidromeClient(Profile(), "secret", http);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var pending = client.PingAsync(cts.Token);
        await entered.Task.WaitAsync(TimeSpan.FromSeconds(2));
        cts.Cancel();
        var ex = await Assert.ThrowsAnyAsync<OperationCanceledException>(() => pending);
        Assert.Null(ex.InnerException);
        Assert.DoesNotContain("https://", ex.ToString());
    }

    [Fact]
    public async Task Request_timeout_covers_body_reads_not_just_headers()
    {
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        using var handler = new Handler(_ => new HttpResponseMessage(HttpStatusCode.OK) { Content = new StreamContent(new WaitingStream(entered)) });
        using var http = new HttpClient(handler) { Timeout = TimeSpan.FromMilliseconds(100) };
        using var client = new NavidromeClient(Profile(), "secret", http);
        using var cts = new CancellationTokenSource();
        var pending = client.PingAsync(cts.Token);
        try
        {
            await entered.Task.WaitAsync(TimeSpan.FromSeconds(2));
            var ex = await Assert.ThrowsAsync<NavidromeException>(() => pending).WaitAsync(TimeSpan.FromSeconds(1));
            Assert.Null(ex.InnerException);
            Assert.DoesNotContain("https://", ex.ToString());
        }
        finally { cts.Cancel(); }
    }
}
