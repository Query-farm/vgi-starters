// Recipe: write query results out with COPY ... TO and a custom format.
//
//   ATTACH 'copy_to_sink' (TYPE vgi, LOCATION '.../vgi-recipes copy_to_sink');
//   COPY (SELECT * FROM t) TO 'out.jsonl' (FORMAT 'copy_to_sink.jsonl_out');
//
// The format name is qualified by the attach alias. Write runs once per batch
// (possibly in parallel, possibly in different processes), so it stashes rows in
// the function's storage; Close runs once and writes the file. Swap the file write
// for an upload to object storage, a warehouse load, or an API call.

using System.Text;
using System.Text.Json;
using Apache.Arrow;
using Apache.Arrow.Types;
using QueryFarm.Vgi.Buffering;
using QueryFarm.Vgi.Table;

namespace Starter.Recipes;

/// <summary>Serves the <c>copy_to_sink</c> catalog.</summary>
public static class CopyToSink
{
    /// <summary>Builds the recipe worker.</summary>
    /// <returns>The worker.</returns>
    public static QueryFarm.Vgi.Worker Worker() => new QueryFarm.Vgi.Worker()
        .CatalogName("copy_to_sink")
        .DefaultSchema("main")
        .RegisterCopyToFormat(new WriteJsonLines(), "jsonl_out", "Newline-delimited JSON, one object per row");
}

/// <summary>Writes each row as one JSON object per line.</summary>
public sealed class WriteJsonLines : CopyToFunction
{
    private const string Namespace = "jsonl_out";
    private const string Key = "shards";

    /// <inheritdoc />
    public override string Name => "write_jsonl";

    /// <inheritdoc />
    public override string Description => "Write rows as newline-delimited JSON";

    /// <inheritdoc />
    public override Schema ArgumentsSchema { get; } = new(
        [TableArgFields.NamedWithDoc("sort_keys", BooleanType.Default, "Sort the keys of each JSON object")],
        metadata: null);

    /// <summary>Serializes this batch and stashes it (storage is scoped to this COPY).</summary>
    /// <param name="batch">Rows to write.</param>
    /// <param name="processParams">The call.</param>
    /// <param name="filePath">Destination file.</param>
    protected override void Write(RecordBatch batch, TableBufferingProcessParams processParams, string filePath)
    {
        if (batch.Length == 0)
        {
            return;
        }

        var sortKeys = processParams.Arguments.BoolNamed("sort_keys", false);
        var order = Enumerable.Range(0, batch.ColumnCount).ToList();
        if (sortKeys)
        {
            order.Sort((a, b) => string.CompareOrdinal(batch.Schema.GetFieldByIndex(a).Name, batch.Schema.GetFieldByIndex(b).Name));
        }

        var text = new StringBuilder();
        for (var row = 0; row < batch.Length; row++)
        {
            using var buffer = new MemoryStream();
            using (var json = new Utf8JsonWriter(buffer))
            {
                json.WriteStartObject();
                foreach (var c in order)
                {
                    json.WritePropertyName(batch.Schema.GetFieldByIndex(c).Name);
                    WriteValue(json, batch.Column(c), row);
                }

                json.WriteEndObject();
            }

            text.Append(Encoding.UTF8.GetString(buffer.ToArray())).Append('\n');
        }

        processParams.Storage.Append(Namespace, Key, Encoding.UTF8.GetBytes(text.ToString()));
    }

    /// <summary>Writes every stashed batch to the file once.</summary>
    /// <param name="combineParams">The call.</param>
    /// <param name="filePath">Destination file.</param>
    protected override void Close(TableBufferingCombineParams combineParams, string filePath)
    {
        using var file = File.Create(filePath);
        foreach (var chunk in combineParams.Storage.ScanLog(Namespace, Key))
        {
            file.Write(chunk);
        }
    }

    private static void WriteValue(Utf8JsonWriter json, IArrowArray array, int row)
    {
        if (array.IsNull(row))
        {
            json.WriteNullValue();
            return;
        }

        switch (array)
        {
            case Int32Array a: json.WriteNumberValue(a.GetValue(row)!.Value); break;
            case Int64Array a: json.WriteNumberValue(a.GetValue(row)!.Value); break;
            case DoubleArray a: json.WriteNumberValue(a.GetValue(row)!.Value); break;
            case BooleanArray a: json.WriteBooleanValue(a.GetValue(row)!.Value); break;
            case StringArray a: json.WriteStringValue(a.GetString(row)); break;
            default: throw new NotSupportedException($"jsonl_out: unsupported column type {array.Data.DataType.Name}");
        }
    }
}
