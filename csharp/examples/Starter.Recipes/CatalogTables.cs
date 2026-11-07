// Recipe: expose a dataset as a named table, with a primary key.
//
// A catalog table is queried by name, with no parentheses, like any DuckDB table:
//
//   SELECT * FROM catalog_tables.main.countries WHERE continent = 'Europe';
//
// Use a table for a fixed or slowly changing dataset (reference data, a directory, a
// lookup list). Use a table function when the caller supplies arguments. Declaring
// NOT NULL and a primary key tells the optimizer, and agents, what identifies a row.
//
// Constraints are the worker's own attestation: declare them only if every row the
// table can return satisfies them.

using Apache.Arrow;
using Apache.Arrow.Types;
using QueryFarm.Vgi.Catalog;
using QueryFarm.Vgi.Table;
using QueryFarm.VgiRpc.Streaming;

namespace Starter.Recipes;

/// <summary>Serves the <c>catalog_tables</c> catalog.</summary>
public static class CatalogTables
{
    /// <summary>Builds the recipe worker.</summary>
    /// <returns>The worker.</returns>
    public static QueryFarm.Vgi.Worker Worker() => new QueryFarm.Vgi.Worker()
        .CatalogName("catalog_tables")
        .DefaultSchema("main")
        .RegisterCatalogTable(new CatalogTable
        {
            Name = "countries",
            Comment = "A small reference list of countries.",
            ScanFunction = new CountriesSource(),
            Constraints = ConstraintRecord.For(["main"], "countries").NotNull("code").PrimaryKey("code").Build(),
            ColumnComments = new Dictionary<string, string>
            {
                ["code"] = "ISO 3166-1 alpha-2 country code.",
                ["name"] = "English short name.",
                ["continent"] = "Continent the country is in.",
            },
        });
}

/// <summary>Rows for the <c>countries</c> table.</summary>
public sealed class CountriesSource : ITableFunction
{
    private static readonly string[][] Rows =
    [
        ["DE", "Germany", "Europe"],
        ["FR", "France", "Europe"],
        ["JP", "Japan", "Asia"],
        ["KE", "Kenya", "Africa"],
        ["US", "United States", "North America"],
    ];

    /// <inheritdoc />
    public string Name => "countries_source";

    /// <inheritdoc />
    public string Description => "Backing rows for the countries table";

    /// <inheritdoc />
    public Schema ArgumentsSchema { get; } = new([], metadata: null);

    /// <inheritdoc />
    public Schema OutputSchema { get; } = new(
        [
            new Field("code", StringType.Default, nullable: false),
            new Field("name", StringType.Default, nullable: true),
            new Field("continent", StringType.Default, nullable: true),
        ],
        metadata: null);

    /// <inheritdoc />
    public ITableFunctionProducer CreateProducer(TableInitParams initParams) => new Producer(initParams.OutputSchema);

    private sealed class Producer(Schema schema) : ITableFunctionProducer
    {
        public void Produce(OutputCollector output)
        {
            var columns = Enumerable.Range(0, 3)
                .Select(c => (IArrowArray)new StringArray.Builder().AppendRange(Rows.Select(r => r[c])).Build());
            output.Emit(new RecordBatch(schema, columns, Rows.Length));
            output.Finish();
        }
    }
}
