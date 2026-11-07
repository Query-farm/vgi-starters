// Recipe: an aggregate function, driven by GROUP BY.
//
//   SELECT category, aggregate.main.int_sum(amount) FROM sales GROUP BY category;
//
// Aggregates keep per-group state across three phases:
//
// - Update    fold a batch of values into each group's state
// - Combine   merge partial states for the same group (parallel execution)
// - Finalize  turn each group's state into one output value
//
// State is opaque bytes, because partial states can be combined in a different
// process.

using Apache.Arrow;
using Apache.Arrow.Types;
using QueryFarm.Vgi.Aggregate;

namespace Starter.Recipes;

/// <summary>Serves the <c>aggregate</c> catalog.</summary>
public static class Aggregate
{
    /// <summary>Builds the recipe worker.</summary>
    /// <returns>The worker.</returns>
    public static QueryFarm.Vgi.Worker Worker() => new QueryFarm.Vgi.Worker()
        .CatalogName("aggregate")
        .DefaultSchema("main")
        .RegisterAggregate(new IntSum());
}

/// <summary>Sums a BIGINT column per group.</summary>
public sealed class IntSum : IAggregateFunction
{
    /// <inheritdoc />
    public string Name => "int_sum";

    /// <inheritdoc />
    public string Description => "Sum integer values per group";

    /// <inheritdoc />
    public Schema ArgumentsSchema { get; } = new(
        [new Field("value", Int64Type.Default, nullable: true, new Dictionary<string, string> { ["vgi_doc"] = "Values to add up" })],
        metadata: null);

    /// <inheritdoc />
    public Schema OutputSchema { get; } = new([new Field("result", Int64Type.Default, nullable: true)], metadata: null);

    /// <inheritdoc />
    public void Update(RecordBatch inputColumns, long[] groupIds, Dictionary<long, byte[]> states, AggregateCallParams callParams)
    {
        var values = (Int64Array)inputColumns.Column(0);
        for (var row = 0; row < groupIds.Length; row++)
        {
            if (values.GetValue(row) is not { } value)
            {
                continue;
            }

            var total = states.TryGetValue(groupIds[row], out var state) ? BitConverter.ToInt64(state) : 0;
            states[groupIds[row]] = BitConverter.GetBytes(total + value);
        }
    }

    /// <inheritdoc />
    public byte[] Combine(byte[] source, byte[]? target, AggregateCallParams callParams) =>
        BitConverter.GetBytes(BitConverter.ToInt64(source) + (target is null ? 0 : BitConverter.ToInt64(target)));

    /// <inheritdoc />
    public IArrowArray Finalize(long[] groupIds, byte[]?[] states, Schema outputSchema, AggregateCallParams callParams)
    {
        var result = new Int64Array.Builder();
        foreach (var state in states)
        {
            if (state is null)
            {
                result.AppendNull();
            }
            else
            {
                result.Append(BitConverter.ToInt64(state));
            }
        }

        return result.Build();
    }
}
