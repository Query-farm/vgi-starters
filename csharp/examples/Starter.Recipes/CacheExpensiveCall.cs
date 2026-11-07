// Recipe: let DuckDB cache an expensive result.
//
// The worker *advertises* how long a result stays fresh. DuckDB caches it, and repeat
// queries inside the TTL never reach the worker. After the TTL, DuckDB revalidates
// with an ETag; if nothing changed the worker answers "not modified" with zero rows.
//
//   SELECT * FROM cache_expensive_call.main.exchange_rates();
//
// The cache directives are vgi.cache.* metadata on the first emitted batch; the
// client's ETag arrives as vgi.cache.if_none_match on the request.

using Apache.Arrow;
using Apache.Arrow.Types;
using QueryFarm.Vgi.Table;
using QueryFarm.VgiRpc.Streaming;

namespace Starter.Recipes;

/// <summary>Serves the <c>cache_expensive_call</c> catalog.</summary>
public static class CacheExpensiveCall
{
    /// <summary>Builds the recipe worker.</summary>
    /// <returns>The worker.</returns>
    public static QueryFarm.Vgi.Worker Worker() => new QueryFarm.Vgi.Worker()
        .CatalogName("cache_expensive_call")
        .DefaultSchema("main")
        .RegisterTable(new ExchangeRates());
}

/// <summary>Rates from a slow upstream, cacheable for five minutes.</summary>
public sealed class ExchangeRates : ITableFunction
{
    // In a real worker, derive this from the upstream: a Last-Modified header, a version
    // column, a content hash. It is the only thing revalidation compares.
    private const string DataVersion = "\"rates-v1\"";

    /// <inheritdoc />
    public string Name => "exchange_rates";

    /// <inheritdoc />
    public string Description => "Exchange rates from a slow upstream, cacheable for 5 minutes";

    /// <inheritdoc />
    public Schema ArgumentsSchema { get; } = new([], metadata: null);

    /// <inheritdoc />
    public Schema OutputSchema { get; } = new(
        [new Field("currency", StringType.Default, nullable: true), new Field("rate", DoubleType.Default, nullable: true)],
        metadata: null);

    /// <inheritdoc />
    public ITableFunctionProducer CreateProducer(TableInitParams initParams) => new Producer(initParams.OutputSchema);

    private sealed class Producer(Schema schema) : ITableFunctionProducer
    {
        public void Produce(OutputCollector output)
        {
            var currency = new StringArray.Builder();
            var rate = new DoubleArray.Builder();
            var metadata = new Dictionary<string, string>
            {
                ["vgi.cache.ttl"] = "300",
                ["vgi.cache.scope"] = "global",
                ["vgi.cache.etag"] = DataVersion,
                ["vgi.cache.revalidatable"] = "1",
            };
            if (output.InputMetadata?.GetValueOrDefault("vgi.cache.if_none_match") == DataVersion)
            {
                metadata["vgi.cache.not_modified"] = "1"; // the client's copy is current: send no rows
            }
            else
            {
                currency.Append("EUR").Append("GBP").Append("JPY");
                rate.Append(1.09).Append(1.27).Append(0.0067);
                metadata["vgi.cache.stale_while_revalidate"] = "60";
                metadata["vgi.cache.stale_if_error"] = "600";
            }

            var rows = currency.Length;
            output.Emit(new RecordBatch(schema, [currency.Build(), rate.Build()], rows), metadata);
            output.Finish();
        }
    }
}
