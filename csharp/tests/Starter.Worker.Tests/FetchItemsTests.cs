// fetch_items against a tiny in-process mock of the items API.

using System.Net;
using System.Net.Sockets;
using System.Text;
using Apache.Arrow;
using Xunit;

namespace Starter.Worker.Tests;

public sealed class FetchItemsTests : IDisposable
{
    private readonly HttpListener _listener = new();
    private readonly string _baseUrl;
    private readonly Dictionary<long, bool> _rateLimited = [];

    public FetchItemsTests()
    {
        var port = FreePort();
        _baseUrl = $"http://127.0.0.1:{port}";
        _listener.Prefixes.Add($"{_baseUrl}/");
        _listener.Start();
        _ = Task.Run(Serve);
    }

    public void Dispose() => _listener.Close();

    [Fact]
    public void PagesThroughTheApiAndRetries429()
    {
        var producer = new FetchProducer(new ItemsClient(_baseUrl + "/", token: null), 4, new FetchItems().OutputSchema);
        var ids = new List<long>();
        while (FunctionTests.Turn(producer, new FetchItems().OutputSchema) is { } batch)
        {
            ids.AddRange(((Int64Array)batch.Column(0)).Values.ToArray());
        }

        Assert.Equal(Enumerable.Range(1, 10).Select(i => (long)i), ids);
        Assert.All(_rateLimited.Values, Assert.True); // every page was rate-limited once, then retried
    }

    [Fact]
    public void ErrorsIncludeTheHttpStatus()
    {
        var client = new ItemsClient(_baseUrl + "/secure", token: null);
        var error = Assert.Throws<ApiException>(() => client.GetPage(1, 10));
        Assert.Contains("HTTP 401", error.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void SendsTheBearerToken()
    {
        var (items, _) = new ItemsClient(_baseUrl + "/secure", token: "test-token").GetPage(1, 10);
        Assert.Equal(10, items.Count);
    }

    private static int FreePort()
    {
        using var socket = new TcpListener(IPAddress.Loopback, 0);
        socket.Start();
        return ((IPEndPoint)socket.LocalEndpoint).Port;
    }

    // 10 items; /items rate-limits each page once; /secure/items needs "Bearer test-token".
    private async Task Serve()
    {
        while (_listener.IsListening)
        {
            HttpListenerContext ctx;
            try
            {
                ctx = await _listener.GetContextAsync();
            }
            catch (Exception e) when (e is HttpListenerException or ObjectDisposedException)
            {
                return;
            }

            var page = long.Parse(ctx.Request.QueryString["page"] ?? "1", System.Globalization.CultureInfo.InvariantCulture);
            var size = long.Parse(ctx.Request.QueryString["page_size"] ?? "4", System.Globalization.CultureInfo.InvariantCulture);
            string body;
            if (ctx.Request.Url!.AbsolutePath.StartsWith("/secure", StringComparison.Ordinal)
                && ctx.Request.Headers["Authorization"] != "Bearer test-token")
            {
                ctx.Response.StatusCode = 401;
                body = """{"error": "missing or invalid bearer token"}""";
            }
            else if (!ctx.Request.Url.AbsolutePath.StartsWith("/secure", StringComparison.Ordinal) && _rateLimited.TryAdd(page, true))
            {
                ctx.Response.StatusCode = 429;
                ctx.Response.Headers["Retry-After"] = "0";
                body = "{}";
            }
            else
            {
                var first = ((page - 1) * size) + 1;
                var ids = Enumerable.Range((int)first, (int)Math.Max(0, Math.Min(size, 10 - first + 1)));
                var items = string.Join(",", ids.Select(i => $$"""{"id": {{i}}, "name": "item-{{i}}", "category": "hardware", "price": 1.5}"""));
                var next = first + size <= 10 ? (page + 1).ToString(System.Globalization.CultureInfo.InvariantCulture) : "null";
                body = $$"""{"items": [{{items}}], "next_page": {{next}}}""";
            }

            var bytes = Encoding.UTF8.GetBytes(body);
            ctx.Response.ContentType = "application/json";
            await ctx.Response.OutputStream.WriteAsync(bytes);
            ctx.Response.Close();
        }
    }
}
