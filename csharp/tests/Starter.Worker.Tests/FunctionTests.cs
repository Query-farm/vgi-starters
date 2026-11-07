// Unit tests. The SQL-level tests live in test/sql and run through DuckDB.

using Apache.Arrow;
using QueryFarm.VgiRpc.Streaming;
using Xunit;

namespace Starter.Worker.Tests;

public class FunctionTests
{
    [Fact]
    public void GreetCasualAndFormal()
    {
        var names = new StringArray.Builder().Append("Ada").AppendNull().Append("Grace").Build();
        Assert.Equal(["Hi, Ada!", null, "Hi, Grace!"], Greet(names, "casual"));
        Assert.Equal(["Good day, Ada.", null, "Good day, Grace."], Greet(names, "formal"));
    }

    [Fact]
    public void GreetStylePublishesChoices()
    {
        var style = new GreetWithStyle().ArgumentsSchema.GetFieldByName("style");
        Assert.Equal(["casual", "formal"], System.Text.Json.JsonSerializer.Deserialize<string[]>(style.Metadata["vgi_choices"])!);
    }

    [Fact]
    public void SequenceDefaultStep()
    {
        var (n, squared) = Drain(new SequenceProducer(4, 1, new SequenceTable().OutputSchema));
        Assert.Equal([0L, 1, 2, 3], n);
        Assert.Equal([0L, 1, 4, 9], squared);
    }

    [Fact]
    public void SequenceNamedStep()
    {
        var (n, _) = Drain(new SequenceProducer(3, 5, new SequenceTable().OutputSchema));
        Assert.Equal([0L, 5, 10], n);
    }

    [Fact]
    public void SequenceStreamsInBoundedBatches()
    {
        var producer = new SequenceProducer(25_000, 1, new SequenceTable().OutputSchema);
        var sizes = new List<int>();
        while (Turn(producer) is { } batch)
        {
            sizes.Add(batch.Length);
        }

        Assert.Equal([10_000, 10_000, 5_000], sizes);
    }

    private static List<string?> Greet(StringArray names, string style)
    {
        var builder = new StringArray.Builder();
        Greeting.Build(names, style, builder);
        var result = builder.Build();
        return [.. Enumerable.Range(0, result.Length).Select(i => result.IsNull(i) ? null : result.GetString(i))];
    }

    // One producer turn: the emitted batch, or null once the producer finished.
    internal static RecordBatch? Turn(QueryFarm.Vgi.Table.ITableFunctionProducer producer, Schema? schema = null)
    {
        // Not disposed: disposing the collector disposes the batch it holds.
        var output = new OutputCollector(schema ?? new SequenceTable().OutputSchema);
        producer.Produce(output);
        return output.EmittedBatch ?? (output.Finished ? null : throw new InvalidOperationException("no batch and not finished"));
    }

    private static (List<long> N, List<long> Squared) Drain(SequenceProducer producer)
    {
        var (n, squared) = (new List<long>(), new List<long>());
        while (Turn(producer) is { } batch)
        {
            n.AddRange(((Int64Array)batch.Column(0)).Values.ToArray());
            squared.AddRange(((Int64Array)batch.Column(1)).Values.ToArray());
        }

        return (n, squared);
    }
}
