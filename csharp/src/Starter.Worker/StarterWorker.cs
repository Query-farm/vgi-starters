// Assembles the functions into a catalog.
//
// To add a function: write it (see Greet.cs / SequenceTable.cs / FetchItems.cs),
// then register it in Build() below.

using QueryFarm.Vgi.Protocol;

namespace Starter.Worker;

/// <summary>The worker: one catalog, one <c>main</c> schema.</summary>
public static class StarterWorker
{
    /// <summary>Builds the configured worker.</summary>
    /// <returns>A worker ready for <c>RunFromArgsAsync</c>.</returns>
    public static QueryFarm.Vgi.Worker Build()
    {
        var greet = new Greet();
        var greetWithStyle = new GreetWithStyle();
        var sequence = new SequenceTable();
        var fetch = new FetchItems();
        FunctionExample[] examples = [.. greet.Examples, .. greetWithStyle.Examples, .. sequence.Examples, .. fetch.Examples];

        return new QueryFarm.Vgi.Worker()
            .CatalogName(Meta.CatalogName)
            .DefaultSchema("main")
            .RegisterCatalog(new CatalogInfo { Name = Meta.CatalogName, SourceUrl = Meta.SourceUrl })
            .DatabaseComment(Meta.CatalogDocMd)
            .DatabaseTags(Meta.CatalogTags())
            .RegisterSchema("main", Meta.SchemaDocMd, Meta.SchemaTags(examples))
            .RegisterSecretType(FetchItems.SecretType, "Bearer token for the API that fetch_items reads.", FetchItems.SecretSchema)
            .RegisterScalar(greet)
            .RegisterScalar(greetWithStyle)
            .RegisterTable(sequence)
            .RegisterTable(fetch);
    }
}
