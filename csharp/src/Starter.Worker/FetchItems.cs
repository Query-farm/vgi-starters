// Table function example: fetch_items, wrapping a paginated REST API.
//
// This is the pattern most workers start from: page through an HTTP API and
// stream each page to DuckDB as one Arrow batch.
//
// * Streaming. Produce fetches one page per call and keeps the next page number
//   on the producer, so a 10,000-page API never sits in memory.
// * Politeness. Requests time out, and 429/5xx responses are retried with
//   backoff that honours Retry-After.
// * Credentials. A bearer token comes from a DuckDB secret if one exists
//   (CREATE SECRET (TYPE starter_api, TOKEN '...')), otherwise from the
//   STARTER_API_TOKEN environment variable. The token lives only on the producer,
//   which stays in the worker process; it is never sent back to DuckDB.
//
// The tests run against test/mock_api/server.py; point base_url at your real API
// once you adapt the parsing in ParsePage.

using System.Globalization;
using System.Net;
using System.Text.Json;
using Apache.Arrow;
using Apache.Arrow.Types;
using QueryFarm.Vgi.Protocol;
using QueryFarm.Vgi.Table;
using QueryFarm.VgiRpc.Streaming;

namespace Starter.Worker;

/// <summary>The API returned an error that will not be retried.</summary>
/// <param name="message">What failed, including the HTTP status.</param>
public sealed class ApiException(string message) : Exception(message);

/// <summary>One item from the API.</summary>
/// <param name="Id">Identifier.</param>
/// <param name="Name">Display name.</param>
/// <param name="Category">Category.</param>
/// <param name="Price">Price in US dollars.</param>
public sealed record Item(long Id, string? Name, string? Category, double? Price);

/// <summary>Pages through <c>&lt;base_url&gt;/items</c> and streams every item as a row.</summary>
public sealed class FetchItems : ITableFunction
{
    /// <summary>DuckDB secret type holding the bearer token.</summary>
    public const string SecretType = "starter_api";

    /// <summary>Environment variable used when no secret exists.</summary>
    public const string TokenEnvVar = "STARTER_API_TOKEN";

    /// <summary>Fields of the <c>starter_api</c> secret type, registered with the worker.</summary>
    public static readonly Schema SecretSchema = new(
        [new Field("token", StringType.Default, nullable: true, new Dictionary<string, string> { ["redact"] = "true" })],
        metadata: null);

    /// <inheritdoc />
    public string Name => "fetch_items";

    /// <inheritdoc />
    public string Description => "Read every item from a paginated REST API, one page per batch";

    /// <inheritdoc />
    public Schema ArgumentsSchema { get; } = new(
        [
            Meta.Argument("base_url", StringType.Default, "Root URL of the API, e.g. 'https://vgi-starter-api.query-farm.services/v1'.",
                pattern: UrlPattern),
            Meta.Argument("page_size", Int64Type.Default, "Items requested per page; 1 to 1000.",
                named: true, defaultJson: "100", ge: 1, le: 1000),
        ],
        metadata: null);

    /// <inheritdoc />
    public RecordBatch? ParameterDefaultValues { get; } = new(
        new Schema([new Field("page_size", Int64Type.Default, nullable: true)], metadata: null),
        [new Int64Array.Builder().Append(100).Build()],
        1);

    /// <summary>Asks DuckDB to send a <c>starter_api</c> secret with every call, if one exists.</summary>
    public IReadOnlyList<RequiredSecret> RequiredSecrets { get; } = [new RequiredSecret { SecretType = SecretType }];

    /// <inheritdoc />
    public Schema OutputSchema { get; } = new(
        [
            Meta.Column("id", Int64Type.Default, "Item identifier assigned by the API.", nullable: false),
            Meta.Column("name", StringType.Default, "Item display name."),
            Meta.Column("category", StringType.Default, "Item category, e.g. 'hardware'."),
            Meta.Column("price", DoubleType.Default, "Item price in US dollars."),
        ],
        metadata: null);

    /// <inheritdoc />
    public IReadOnlyList<FunctionExample> Examples { get; } =
    [
        Meta.Example("SELECT id, name, price FROM starter.main.fetch_items('https://vgi-starter-api.query-farm.services/v1') " +
            "WHERE category = 'hardware' ORDER BY price DESC LIMIT 5",
            "The five priciest hardware items from the public demo API"),
        Meta.Example("SELECT category, count(*) AS items, avg(price) AS avg_price " +
            "FROM starter.main.fetch_items('https://vgi-starter-api.query-farm.services/v1', page_size := 10) " +
            "GROUP BY category ORDER BY category",
            "Summarize the API's items by category, ten per request"),
    ];

    /// <inheritdoc />
    public IReadOnlyDictionary<string, string> Tags => new Dictionary<string, string>(Meta.ObjectTags(
        title: "Fetch Items From API",
        category: "api",
        keywords: "rest api, http, pagination, fetch, items, json api, bearer token",
        docLlm: "Read all items from a paginated JSON REST API at `base_url` and return them as rows " +
                "`(id, name, category, price)`. Pages are requested `page_size` at a time (named argument, " +
                "default 100) and streamed to DuckDB as they arrive. If a DuckDB secret of type `starter_api` " +
                "exists its `token` is sent as a bearer token; otherwise the `STARTER_API_TOKEN` environment " +
                "variable is used. Rate-limit (429) and server errors are retried with backoff.",
        docMd: "## fetch_items(base_url, page_size := 100)\n\n" +
               "Every item from a paginated REST API, streamed one page per batch.\n\n" +
               "Authenticate with a `starter_api` secret or the `STARTER_API_TOKEN` environment variable:\n\n" +
               "```sql\nCREATE SECRET (TYPE starter_api, TOKEN 'your-token');\n```"))
    {
        ["vgi.result_columns_schema"] = Meta.ResultColumnsSchema(OutputSchema),
        ["vgi.example_queries"] = Meta.ExampleQueries(Examples),
    };

    private const string UrlPattern = "^https?://";

    /// <inheritdoc />
    public ITableFunctionProducer CreateProducer(TableInitParams initParams)
    {
        // The SDK has already enforced base_url's pattern and page_size's range.
        var baseUrl = initParams.Arguments.StringPositional(0);
        var pageSize = initParams.Arguments.Int64Named("page_size", 100);
        var token = initParams.SecretValues.FindByType(SecretType)?.GetString("token");
        if (string.IsNullOrEmpty(token))
        {
            token = Environment.GetEnvironmentVariable(TokenEnvVar);
        }

        return new FetchProducer(new ItemsClient(baseUrl, string.IsNullOrEmpty(token) ? null : token), pageSize, initParams.OutputSchema);
    }

}

/// <summary>The per-call cursor: one API page per <see cref="Produce"/> call.</summary>
/// <param name="client">API client.</param>
/// <param name="pageSize">Items per page.</param>
/// <param name="schema">The bound output schema.</param>
public sealed class FetchProducer(ItemsClient client, long pageSize, Schema schema) : ITableFunctionProducer
{
    private long _nextPage = 1; // 0 once the API says there are no more pages

    /// <inheritdoc />
    public void Produce(OutputCollector output)
    {
        if (_nextPage == 0)
        {
            output.Finish();
            return;
        }

        var (items, next) = client.GetPage(_nextPage, pageSize);
        var id = new Int64Array.Builder();
        var name = new StringArray.Builder();
        var category = new StringArray.Builder();
        var price = new DoubleArray.Builder();
        foreach (var item in items)
        {
            id.Append(item.Id);
            name.Append(item.Name);
            category.Append(item.Category);
            if (item.Price is { } p)
            {
                price.Append(p);
            }
            else
            {
                price.AppendNull();
            }
        }

        output.Emit(new RecordBatch(schema, [id.Build(), name.Build(), category.Build(), price.Build()], items.Count));
        _nextPage = next ?? 0;
    }
}

/// <summary>A small, polite client for the items API.</summary>
/// <param name="baseUrl">Root URL of the API.</param>
/// <param name="token">Bearer token, or null.</param>
public sealed class ItemsClient(string baseUrl, string? token)
{
    /// <summary>Attempts per request, including the first.</summary>
    public const int MaxAttempts = 4;

    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(30) };

    /// <summary>Fetches one page.</summary>
    /// <param name="page">1-based page number.</param>
    /// <param name="pageSize">Items per page.</param>
    /// <returns>The page's items and the next page number, or null after the last page.</returns>
    public (IReadOnlyList<Item> Items, long? NextPage) GetPage(long page, long pageSize)
    {
        var url = $"{baseUrl.TrimEnd('/')}/items?page={page}&page_size={pageSize}";
        using var body = GetJson(url);
        return ParsePage(body.RootElement);
    }

    /// <summary>Splits one API response into rows and the next page number. Adapt this to your API.</summary>
    /// <param name="body">The decoded JSON body.</param>
    /// <returns>Items and the next page number.</returns>
    public static (IReadOnlyList<Item> Items, long? NextPage) ParsePage(JsonElement body)
    {
        static string? Text(JsonElement e, string name) =>
            e.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

        var items = body.GetProperty("items").EnumerateArray().Select(e => new Item(
            e.GetProperty("id").GetInt64(),
            Text(e, "name"),
            Text(e, "category"),
            e.TryGetProperty("price", out var p) && p.ValueKind == JsonValueKind.Number ? p.GetDouble() : null)).ToList();
        long? next = body.TryGetProperty("next_page", out var n) && n.ValueKind == JsonValueKind.Number ? n.GetInt64() : null;
        return (items, next);
    }

    /// <summary>GETs <paramref name="url"/> and decodes JSON, retrying 429 and 5xx with backoff.</summary>
    private JsonDocument GetJson(string url)
    {
        for (var attempt = 1; ; attempt++)
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, url);
            request.Headers.Accept.ParseAdd("application/json");
            request.Headers.UserAgent.ParseAdd("vgi-starter-worker/0.1");
            if (token is not null)
            {
                request.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token);
            }

            HttpResponseMessage response;
            try
            {
                response = Http.Send(request);
            }
            catch (Exception err) when (err is HttpRequestException or TaskCanceledException)
            {
                if (attempt == MaxAttempts)
                {
                    throw new ApiException($"GET {url} failed: {err.Message}");
                }

                Thread.Sleep(Backoff(attempt));
                continue;
            }

            using (response)
            {
                if (response.IsSuccessStatusCode)
                {
                    return JsonDocument.Parse(response.Content.ReadAsStream());
                }

                var code = (int)response.StatusCode;
                var retryable = response.StatusCode == HttpStatusCode.TooManyRequests || code >= 500;
                if (!retryable || attempt == MaxAttempts)
                {
                    using var reader = new StreamReader(response.Content.ReadAsStream());
                    var text = reader.ReadToEnd();
                    throw new ApiException($"GET {url} failed with HTTP {code} {response.ReasonPhrase}: {text[..Math.Min(500, text.Length)]}");
                }

                var retryAfter = response.Headers.TryGetValues("Retry-After", out var values) ? values.FirstOrDefault() : null;
                var delay = int.TryParse(retryAfter, NumberStyles.None, CultureInfo.InvariantCulture, out var seconds)
                    ? TimeSpan.FromSeconds(seconds)
                    : Backoff(attempt);
                Thread.Sleep(delay < TimeSpan.FromSeconds(30) ? delay : TimeSpan.FromSeconds(30));
            }
        }
    }

    private static TimeSpan Backoff(int attempt) => TimeSpan.FromSeconds(0.5 * Math.Pow(2, attempt));
}
