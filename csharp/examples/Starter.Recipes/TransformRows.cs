// Recipe: transform the rows of a query with a table in-out function.
//
// A table in-out function receives another query's rows, batch by batch, and emits
// rows back. Use it to clean, enrich, filter or reshape data in SQL:
//
//   SELECT * FROM transform_rows.main.positive_only((SELECT * FROM readings));
//
// Here: keep only rows whose `value` column is greater than zero. The output schema
// equals the input schema, so it works on any table that has a `value` column.

using Apache.Arrow;
using QueryFarm.Vgi.Table;
using QueryFarm.Vgi.TableInOut;
using QueryFarm.VgiRpc.Streaming;

namespace Starter.Recipes;

/// <summary>Serves the <c>transform_rows</c> catalog.</summary>
public static class TransformRows
{
    /// <summary>Builds the recipe worker.</summary>
    /// <returns>The worker.</returns>
    public static QueryFarm.Vgi.Worker Worker() => new QueryFarm.Vgi.Worker()
        .CatalogName("transform_rows")
        .DefaultSchema("main")
        .RegisterTableInOut(new PositiveOnly());
}

/// <summary>Emits the input rows whose <c>value</c> is greater than zero.</summary>
public sealed class PositiveOnly : ITableInOutFunction
{
    /// <inheritdoc />
    public string Name => "positive_only";

    /// <inheritdoc />
    public string Description => "Keep the rows of a query whose value column is positive";

    /// <inheritdoc />
    public Schema ArgumentsSchema { get; } = new([TableArgFields.Table("data")], metadata: null);

    /// <inheritdoc />
    public Schema OutputSchema { get; } = new([], metadata: null);

    /// <summary>Output columns are the input columns; fail early without a <c>value</c> column.</summary>
    /// <param name="bindParams">The bind call.</param>
    /// <returns>The input schema.</returns>
    public Schema ResolveOutputSchema(TableInOutBindParams bindParams) =>
        bindParams.InputSchema.GetFieldIndex("value") >= 0
            ? bindParams.InputSchema
            : throw new ArgumentException("positive_only needs an input column named 'value'");

    /// <inheritdoc />
    public ITableInOutProcessor CreateProcessor(TableInOutInitParams initParams) => new Processor();

    private sealed class Processor : ITableInOutProcessor
    {
        public void Process(RecordBatch input, OutputCollector output)
        {
            var value = input.Column(input.Schema.GetFieldIndex("value"));
            var keep = Enumerable.Range(0, input.Length).Where(row => IsPositive(value, row)).ToList();
            output.Emit(Take(input, keep));
        }
    }

    // NULL is not positive, so NULL rows are dropped.
    private static bool IsPositive(IArrowArray array, int row) => !array.IsNull(row) && array switch
    {
        Int8Array a => a.GetValue(row) > 0,
        Int16Array a => a.GetValue(row) > 0,
        Int32Array a => a.GetValue(row) > 0,
        Int64Array a => a.GetValue(row) > 0,
        FloatArray a => a.GetValue(row) > 0,
        DoubleArray a => a.GetValue(row) > 0,
        _ => throw new NotSupportedException($"positive_only: 'value' must be numeric, not {array.Data.DataType.Name}"),
    };

    // Arrow C# has no take kernel: concatenate one-row slices of the kept rows.
    private static RecordBatch Take(RecordBatch batch, IReadOnlyList<int> rows)
    {
        var columns = batch.Arrays.Select(column => rows.Count == 0
            ? ArrowArrayFactory.Slice(column, 0, 0)
            : ArrowArrayConcatenator.Concatenate(rows.Select(r => ArrowArrayFactory.Slice(column, r, 1)).ToList()));
        return new RecordBatch(batch.Schema, columns, rows.Count);
    }
}
