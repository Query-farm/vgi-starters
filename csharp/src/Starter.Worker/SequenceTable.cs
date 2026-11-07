// Table function example: sequence_table.
//
// A table function returns a set of rows. This one is a deterministic generator,
// the simplest shape to learn from:
//
// * `count` is positional and required; `step` is *named* with a default, so
//   callers write sequence_table(5) or sequence_table(5, step := 2). (Named
//   arguments work on table functions, not on scalars.)
// * The ge/le constraints are declared in the argument metadata. The SDK enforces
//   them before the function runs, and agents read them to learn the valid range.
// * Output streams in bounded batches. SequenceProducer remembers where the last
//   batch stopped. The SDK keeps one producer per call on the worker side (over
//   HTTP too), so the cursor never travels to DuckDB.

using Apache.Arrow;
using Apache.Arrow.Types;
using QueryFarm.Vgi.Protocol;
using QueryFarm.Vgi.Table;
using QueryFarm.VgiRpc.Streaming;

namespace Starter.Worker;

/// <summary>Generates <c>count</c> rows of <c>(n, squared)</c> with <c>n = 0, step, 2*step, ...</c>.</summary>
public sealed class SequenceTable : ITableFunction
{
    /// <summary>Most rows emitted per batch.</summary>
    public const int BatchRows = 10_000;

    /// <summary>Largest allowed <c>count</c>.</summary>
    public const long MaxCount = 1_000_000;

    /// <inheritdoc />
    public string Name => "sequence_table";

    /// <inheritdoc />
    public string Description => "Generate count rows of (n, squared), stepping n by step";

    /// <inheritdoc />
    public Schema ArgumentsSchema { get; } = new(
        [
            Meta.Argument("count", Int64Type.Default, "How many rows to generate, from 0 to 1,000,000.", ge: 0, le: MaxCount),
            Meta.Argument("step", Int64Type.Default, "Gap between consecutive n values; at least 1.",
                named: true, defaultJson: "1", ge: 1),
        ],
        metadata: null);

    /// <inheritdoc />
    public RecordBatch? ParameterDefaultValues { get; } = new(
        new Schema([new Field("step", Int64Type.Default, nullable: true)], metadata: null),
        [new Int64Array.Builder().Append(1).Build()],
        1);

    /// <inheritdoc />
    public Schema OutputSchema { get; } = new(
        [
            Meta.Column("n", Int64Type.Default, "The generated value: row index times step.", nullable: false),
            Meta.Column("squared", Int64Type.Default, "n multiplied by itself.", nullable: false),
        ],
        metadata: null);

    /// <inheritdoc />
    public IReadOnlyList<FunctionExample> Examples { get; } =
    [
        Meta.Example("SELECT n, squared FROM starter.main.sequence_table(5) ORDER BY n", "The first five values and their squares"),
        Meta.Example("SELECT n FROM starter.main.sequence_table(4, step := 10) ORDER BY n", "Four values stepping by ten"),
    ];

    /// <inheritdoc />
    public IReadOnlyDictionary<string, string> Tags => new Dictionary<string, string>(Meta.ObjectTags(
        title: "Sequence Table",
        category: "generators",
        keywords: "sequence, series, range, generate rows, numbers, squares",
        docLlm: "Generate a deterministic table of `count` rows with columns `n` and `squared`, where `n` " +
                "runs 0, step, 2*step, and so on. `count` is positional (0 to 1,000,000); `step` is a named " +
                "argument that defaults to 1. Rows stream back in batches, so large counts do not buffer " +
                "in memory. A call with a step looks like this:\n\n" +
                "```sql\nsequence_table(5, step := 2)\n```",
        docMd: "## sequence_table(count, step := 1)\n\n" +
               "`count` rows of `(n, squared)` with n = 0, step, 2*step, ...\n\n" +
               "Deterministic, so it is handy for tests and demos."))
    {
        ["vgi.result_columns_schema"] = Meta.ResultColumnsSchema(OutputSchema),
        ["vgi.executable_examples"] = """
            [
              {"description": "Three rows stepping by two",
               "sql": "SELECT n, squared FROM starter.main.sequence_table(3, step := 2) ORDER BY n",
               "expected_result": [[0, 0], [2, 4], [4, 16]]},
              {"description": "Count the rows generated",
               "sql": "SELECT count(*) AS rows FROM starter.main.sequence_table(1000)",
               "expected_result": [[1000]]}
            ]
            """,
        ["vgi.example_queries"] = Meta.ExampleQueries(Examples),
    };

    /// <summary>Tells the planner exactly how many rows to expect.</summary>
    /// <param name="bindParams">The bind call.</param>
    /// <returns>The row count.</returns>
    public long? Cardinality(TableBindParams bindParams) => bindParams.Arguments.Int64(0);

    /// <inheritdoc />
    public ITableFunctionProducer CreateProducer(TableInitParams initParams)
    {
        // The SDK has already enforced the ge/le ranges declared in ArgumentsSchema.
        var count = initParams.Arguments.Int64(0);
        var step = initParams.Arguments.Int64Named("step", 1);
        return new SequenceProducer(count, step, initParams.OutputSchema);
    }

}

/// <summary>The per-call cursor: emits one bounded batch per call until <c>count</c> rows are out.</summary>
/// <param name="count">Rows to generate.</param>
/// <param name="step">Gap between consecutive values.</param>
/// <param name="schema">The bound output schema.</param>
public sealed class SequenceProducer(long count, long step, Schema schema) : ITableFunctionProducer
{
    private long _emitted;

    /// <inheritdoc />
    public void Produce(OutputCollector output)
    {
        if (_emitted >= count)
        {
            output.Finish();
            return;
        }

        var rows = (int)Math.Min(SequenceTable.BatchRows, count - _emitted);
        var n = new Int64Array.Builder().Reserve(rows);
        var squared = new Int64Array.Builder().Reserve(rows);
        for (var i = 0; i < rows; i++)
        {
            var value = (_emitted + i) * step;
            n.Append(value);
            squared.Append(value * value);
        }

        output.Emit(new RecordBatch(schema, [n.Build(), squared.Build()], rows));
        _emitted += rows;
    }
}
