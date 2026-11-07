// Run one recipe as a worker: `vgi-recipes <recipe> [worker flags]`.
//
//   ATTACH 'aggregate' (TYPE vgi, LOCATION '/abs/.../vgi-recipes aggregate');

using Starter.Recipes;

var recipes = new Dictionary<string, Func<QueryFarm.Vgi.Worker>>
{
    ["transform_rows"] = TransformRows.Worker,
    ["aggregate"] = Aggregate.Worker,
    ["pushdown_sqlite"] = PushdownSqlite.Worker,
    ["cache_expensive_call"] = CacheExpensiveCall.Worker,
    ["catalog_tables"] = CatalogTables.Worker,
    ["copy_to_sink"] = CopyToSink.Worker,
};

if (args.Length < 1 || !recipes.TryGetValue(args[0], out var build))
{
    await Console.Error.WriteLineAsync($"usage: vgi-recipes <{string.Join('|', recipes.Keys)}> [worker flags]");
    return 2;
}

Environment.SetEnvironmentVariable("Logging__Console__LogToStandardErrorThreshold", "Trace");
await build().RunFromArgsAsync(args[1..]);
return 0;
