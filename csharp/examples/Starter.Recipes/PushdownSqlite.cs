// Recipe: push WHERE filters and column selection down into a database.
//
// DuckDB tells a table function which columns the query needs (projection pushdown)
// and which predicates it can apply at the source (filter pushdown). Turning those
// into the source's own query means less data crosses the wire.
//
//   SELECT id, amount FROM pushdown_sqlite.main.orders() WHERE region = 'west' AND amount > 900;
//
// Only simple comparisons, IN lists and IS [NOT] NULL on a column are translated;
// anything else is left for DuckDB, which re-checks every row it receives, so
// partial pushdown is safe. The demo database is created on first use in the
// system temp directory.

using System.Text.Json;
using Apache.Arrow;
using Apache.Arrow.Types;
using Microsoft.Data.Sqlite;
using QueryFarm.Vgi.Arguments;
using QueryFarm.Vgi.Table;
using QueryFarm.VgiRpc.Streaming;

namespace Starter.Recipes;

/// <summary>Serves the <c>pushdown_sqlite</c> catalog.</summary>
public static class PushdownSqlite
{
    /// <summary>Builds the recipe worker.</summary>
    /// <returns>The worker.</returns>
    public static QueryFarm.Vgi.Worker Worker() => new QueryFarm.Vgi.Worker()
        .CatalogName("pushdown_sqlite")
        .DefaultSchema("main")
        .RegisterTable(new Orders());
}

/// <summary>Scans the SQLite <c>orders</c> table, pushing filters and projections into SQL.</summary>
public sealed class Orders : ITableFunction
{
    internal const int Page = 500;

    internal static readonly string DbPath = Path.Combine(Path.GetTempPath(), "vgi_recipe_orders_csharp.sqlite");

    private static readonly Dictionary<string, string> SqlOp = new()
    {
        ["eq"] = "=",
        ["ne"] = "<>",
        ["gt"] = ">",
        ["ge"] = ">=",
        ["lt"] = "<",
        ["le"] = "<=",
    };

    private static readonly Dictionary<string, string> Flipped = new()
    {
        ["eq"] = "eq",
        ["ne"] = "ne",
        ["gt"] = "lt",
        ["ge"] = "le",
        ["lt"] = "gt",
        ["le"] = "ge",
    };

    /// <inheritdoc />
    public string Name => "orders";

    /// <inheritdoc />
    public string Description => "Orders from a SQLite database, with filter and projection pushdown";

    /// <inheritdoc />
    public Schema ArgumentsSchema { get; } = new([], metadata: null);

    /// <inheritdoc />
    public Schema OutputSchema { get; } = new(
        [
            new Field("id", Int64Type.Default, nullable: false),
            new Field("region", StringType.Default, nullable: true),
            new Field("amount", Int64Type.Default, nullable: true),
        ],
        metadata: null);

    /// <summary>Opt in to projection pushdown.</summary>
    public bool? ProjectionPushdown => true;

    /// <summary>Opt in to filter pushdown.</summary>
    public bool? FilterPushdown => true;

    /// <inheritdoc />
    public ITableFunctionProducer CreateProducer(TableInitParams initParams)
    {
        EnsureDb();
        var filters = initParams.Filters;
        var clauses = new List<string>();
        var values = new List<object?>();
        foreach (var predicate in filters?.Predicates ?? [])
        {
            Translate(predicate.Expression, filters!, clauses, values);
        }

        return new Producer(initParams.ProjectedSchema, clauses, values);
    }

    /// <summary>Creates a deterministic 2,000-row orders table if it does not exist.</summary>
    internal static void EnsureDb()
    {
        if (File.Exists(DbPath))
        {
            return;
        }

        var tmp = $"{DbPath}.{Environment.ProcessId}.tmp";
        using (var con = new SqliteConnection($"Data Source={tmp};Pooling=False"))
        {
            con.Open();
            using var tx = con.BeginTransaction();
            using var create = con.CreateCommand();
            create.CommandText = "CREATE TABLE orders (id INTEGER PRIMARY KEY, region TEXT NOT NULL, amount INTEGER NOT NULL)";
            create.ExecuteNonQuery();
            string[] regions = ["north", "south", "east", "west"];
            using var insert = con.CreateCommand();
            insert.CommandText = "INSERT INTO orders VALUES ($id, $region, $amount)";
            var id = insert.Parameters.Add("$id", SqliteType.Integer);
            var region = insert.Parameters.Add("$region", SqliteType.Text);
            var amount = insert.Parameters.Add("$amount", SqliteType.Integer);
            for (var i = 1; i <= 2000; i++)
            {
                id.Value = i;
                region.Value = regions[i % 4];
                amount.Value = i * 37 % 1000;
                insert.ExecuteNonQuery();
            }

            tx.Commit();
        }

        File.Move(tmp, DbPath, overwrite: true);
    }

    // Translates the filters SQLite can evaluate into parameterized SQL; skips the rest.
    private static void Translate(JsonElement e, FilterSet filters, List<string> clauses, List<object?> values)
    {
        string? Column(JsonElement node) =>
            node.GetProperty("node").GetString() == "column_ref" ? node.GetProperty("column_name").GetString() : null;
        bool IsLiteral(JsonElement node) => node.GetProperty("node").GetString() == "literal";

        switch (e.GetProperty("node").GetString())
        {
            case "and":
                foreach (var child in e.GetProperty("children").EnumerateArray())
                {
                    Translate(child, filters, clauses, values);
                }

                break;
            case "comparison":
                var op = e.GetProperty("op").GetString()!;
                var (left, right) = (e.GetProperty("left"), e.GetProperty("right"));
                if (Column(left) is { } col && IsLiteral(right) && SqlOp.TryGetValue(op, out var sql))
                {
                    clauses.Add($"\"{col}\" {sql} $p{values.Count}");
                    values.Add(filters.ValueRef(right.GetProperty("value_ref").GetInt32()));
                }
                else if (Column(right) is { } col2 && IsLiteral(left) && Flipped.TryGetValue(op, out var flipped))
                {
                    clauses.Add($"\"{col2}\" {SqlOp[flipped]} $p{values.Count}");
                    values.Add(filters.ValueRef(left.GetProperty("value_ref").GetInt32()));
                }

                break;
            case "in":
                var set = e.GetProperty("set");
                if (Column(e.GetProperty("expression")) is { } inCol && set.GetProperty("kind").GetString() == "literal")
                {
                    var items = filters.LiteralSet(set.GetProperty("value_ref").GetInt32());
                    if (items.Count > 0)
                    {
                        var not = e.GetProperty("negated").GetBoolean() ? "NOT " : "";
                        clauses.Add($"\"{inCol}\" {not}IN ({string.Join(", ", items.Select((_, i) => $"$p{values.Count + i}"))})");
                        values.AddRange(items);
                    }
                }

                break;
            case "is_null":
                if (Column(e.GetProperty("expression")) is { } nullCol)
                {
                    clauses.Add($"\"{nullCol}\" IS {(e.GetProperty("negated").GetBoolean() ? "NOT " : "")}NULL");
                }

                break;
            default:
                break; // OR, NOT, functions, casts: DuckDB applies them.
        }
    }

    /// <summary>Keyset pagination: one page of matching rows per call, only the projected columns.</summary>
    private sealed class Producer(Schema schema, List<string> clauses, List<object?> values) : ITableFunctionProducer
    {
        private long _lastId;

        public void Produce(OutputCollector output)
        {
            var wanted = schema.FieldsList.Select(f => $"\"{f.Name}\"").ToList();
            var where = string.Join(" AND ", clauses.Prepend("id > $last"));
            using var con = new SqliteConnection($"Data Source={DbPath};Mode=ReadOnly");
            con.Open();
            using var cmd = con.CreateCommand();
            cmd.CommandText = $"SELECT {string.Join(", ", wanted.Prepend("id"))} FROM orders WHERE {where} ORDER BY id LIMIT {Page}";
            cmd.Parameters.AddWithValue("$last", _lastId);
            for (var i = 0; i < values.Count; i++)
            {
                cmd.Parameters.AddWithValue($"$p{i}", values[i] ?? DBNull.Value);
            }

            var builders = schema.FieldsList.Select(f => f.DataType is StringType ? (object)new StringArray.Builder() : new Int64Array.Builder()).ToList();
            var rows = 0;
            using (var reader = cmd.ExecuteReader())
            {
                while (reader.Read())
                {
                    _lastId = reader.GetInt64(0);
                    for (var c = 0; c < builders.Count; c++)
                    {
                        if (builders[c] is StringArray.Builder s)
                        {
                            s.Append(reader.IsDBNull(c + 1) ? null : reader.GetString(c + 1));
                        }
                        else if (reader.IsDBNull(c + 1))
                        {
                            ((Int64Array.Builder)builders[c]).AppendNull();
                        }
                        else
                        {
                            ((Int64Array.Builder)builders[c]).Append(reader.GetInt64(c + 1));
                        }
                    }

                    rows++;
                }
            }

            if (rows == 0)
            {
                output.Finish();
                return;
            }

            var arrays = builders.Select(b => b is StringArray.Builder s ? (IArrowArray)s.Build() : ((Int64Array.Builder)b).Build());
            output.Emit(new RecordBatch(schema, arrays, rows));
        }
    }
}
