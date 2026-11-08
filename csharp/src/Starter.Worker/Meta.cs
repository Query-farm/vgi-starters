// Discovery metadata: the one file to edit when you rename or extend the worker.
//
// DuckDB, coding agents and the vgi-lint metadata checker all read these tags.
// Keep every object documented and `make lint-explain` stays at 100.
//
// * Catalog-level tags (title, license, author, links, long description) live in
//   CatalogTags below.
// * Every function carries ObjectTags(...): a title, a category from
//   SchemaCategories, search keywords, and LLM- and human-facing docs.

using System.Globalization;
using System.Text.Encodings.Web;
using System.Text.Json;
using Apache.Arrow;
using Apache.Arrow.Types;
using QueryFarm.Vgi.Protocol;

namespace Starter.Worker;

/// <summary>Catalog/schema metadata and the helpers every function uses for its own.</summary>
public static class Meta
{
    // Keep quotes and non-ASCII readable in the published JSON tags.
    private static readonly JsonSerializerOptions Json = new() { Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    /// <summary>Serializes a tag value as JSON.</summary>
    /// <typeparam name="T">Value type.</typeparam>
    /// <param name="value">The value.</param>
    /// <returns>JSON text.</returns>
    public static string ToJson<T>(T value) => JsonSerializer.Serialize(value, Json);

    /// <summary>One example query, published in the function's catalog entry and in
    /// <c>vgi.example_queries</c>.</summary>
    /// <param name="sql">The query.</param>
    /// <param name="description">What it shows, in one line.</param>
    /// <returns>The example.</returns>
    public static FunctionExample Example(string sql, string description) => new() { Sql = sql, Description = description };

    /// <summary>The catalog name DuckDB sees by default.</summary>
    public const string CatalogName = "starter";

    /// <summary>Where this worker's code lives.</summary>
    public const string SourceUrl = "https://github.com/Query-farm/vgi-starter-csharp";

    /// <summary>Categories group functions in docs and agent tools. Every object's
    /// <c>vgi.category</c> must name one of these.</summary>
    public static readonly IReadOnlyList<(string Name, string Title, string Description)> SchemaCategories =
    [
        ("text", "Text", "Functions that build or transform strings."),
        ("generators", "Generators", "Table functions that generate rows."),
        ("api", "REST APIs", "Table functions that read from HTTP APIs."),
    ];

    /// <summary>Turns <c>"a, b, c"</c> into the JSON array string <c>vgi.keywords</c> requires.</summary>
    /// <param name="keywords">Comma-separated keywords.</param>
    /// <returns>A JSON array of trimmed keywords.</returns>
    public static string KeywordsArray(string keywords) => ToJson(
        keywords.Split(',', StringSplitOptions.TrimEntries | StringSplitOptions.RemoveEmptyEntries));

    /// <summary>Builds the standard per-object discovery tags.</summary>
    /// <param name="title">Human title.</param>
    /// <param name="category">One of <see cref="SchemaCategories"/>.</param>
    /// <param name="keywords">Comma-separated search keywords.</param>
    /// <param name="docLlm">Detailed description for agents.</param>
    /// <param name="docMd">Markdown description for people.</param>
    /// <returns>The tag dictionary.</returns>
    public static Dictionary<string, string> ObjectTags(
        string title, string category, string keywords, string docLlm, string docMd)
    {
        if (!SchemaCategories.Any(c => c.Name == category))
        {
            throw new ArgumentException($"unknown category '{category}'; add it to SchemaCategories", nameof(category));
        }

        return new Dictionary<string, string>
        {
            ["vgi.title"] = title,
            ["vgi.category"] = category,
            ["vgi.keywords"] = KeywordsArray(keywords),
            ["vgi.doc_llm"] = docLlm,
            ["vgi.doc_md"] = docMd,
        };
    }

    /// <summary>An Arrow field whose <c>comment</c> shows up in DuckDB's DESCRIBE and agent tools.</summary>
    /// <param name="name">Column name.</param>
    /// <param name="type">Arrow type.</param>
    /// <param name="comment">Column description.</param>
    /// <param name="nullable">Whether the column can be NULL.</param>
    /// <returns>The field.</returns>
    public static Field Column(string name, IArrowType type, string comment, bool nullable = true) =>
        new(name, type, nullable, new Dictionary<string, string> { ["comment"] = comment });

    /// <summary>The JSON <c>vgi.result_columns_schema</c> for a table function's output.</summary>
    /// <param name="schema">Output schema built with <see cref="Column"/>.</param>
    /// <returns>A JSON array of <c>{name, type, description}</c>.</returns>
    public static string ResultColumnsSchema(Schema schema) => ToJson(
        schema.FieldsList.Select(f => new Dictionary<string, string>
        {
            ["name"] = f.Name,
            ["type"] = f.DataType switch
            {
                Int64Type => "BIGINT",
                DoubleType => "DOUBLE",
                StringType => "VARCHAR",
                _ => f.DataType.Name.ToUpperInvariant(),
            },
            ["description"] = f.Metadata?.GetValueOrDefault("comment") ?? "",
        }));

    /// <summary>A table-function argument with its documentation and constraints, in the field
    /// metadata DuckDB publishes through <c>vgi_function_arguments()</c>.</summary>
    /// <param name="name">Argument name.</param>
    /// <param name="type">Arrow type.</param>
    /// <param name="doc">What the argument means.</param>
    /// <param name="named">Named (<c>name := value</c>) rather than positional.</param>
    /// <param name="defaultJson">JSON rendering of the default, for named arguments.</param>
    /// <param name="ge">Inclusive lower bound, if any.</param>
    /// <param name="le">Inclusive upper bound, if any.</param>
    /// <param name="pattern">Regex the value must match, if any.</param>
    /// <returns>The argument field.</returns>
    public static Field Argument(
        string name, IArrowType type, string doc, bool named = false, string? defaultJson = null,
        long? ge = null, long? le = null, string? pattern = null)
    {
        // The keys are the VGI wire protocol's (vgi_arg, vgi_doc, vgi_range, ...). The SDK's
        // TableArgFields helpers cover one key at a time, so they are spelled out here.
        var metadata = new Dictionary<string, string> { ["vgi_doc"] = doc };
        if (named)
        {
            metadata["vgi_arg"] = "named";
        }

        if (defaultJson is not null)
        {
            metadata["vgi_default"] = defaultJson;
        }

        if (ge is not null || le is not null)
        {
            var lower = ge is { } g ? "[" + g.ToString(CultureInfo.InvariantCulture) : "(-inf";
            var upper = le is { } l ? l.ToString(CultureInfo.InvariantCulture) + "]" : "+inf)";
            metadata["vgi_range"] = $"{lower}, {upper}";
        }

        if (pattern is not null)
        {
            metadata["vgi_pattern"] = pattern;
        }

        return new Field(name, type, nullable: true, metadata);
    }

    /// <summary>Catalog description for agents.</summary>
    public const string CatalogDocLlm =
        "An example VGI worker generated from the Query.Farm starter template. It shows the three " +
        "shapes most workers need: a vectorized scalar function (`greet`, with a one- and two-argument " +
        "overload), a deterministic table function with a named default argument (`sequence_table`), " +
        "and a table function that pages through a REST API with retries and an optional bearer token " +
        "taken from a DuckDB secret or an environment variable (`fetch_items`). Replace these with " +
        "your own functions; keep the metadata complete so agents can discover and call them.";

    /// <summary>Catalog description for people.</summary>
    public const string CatalogDocMd =
        "# VGI starter worker\n\n" +
        "**An example [VGI](https://query.farm/vgi) worker that brings custom functions into DuckDB " +
        "SQL.** It was generated from the Query.Farm starter template and is meant to be renamed and " +
        "rewritten: every function here demonstrates one pattern you will reuse.\n\n" +
        "- `greet(name)` and `greet(name, style)`: a vectorized scalar with an overload and a " +
        "constrained constant argument.\n" +
        "- `sequence_table(count, step := 1)`: a deterministic table function that streams its " +
        "output in batches.\n" +
        "- `fetch_items(base_url, page_size := 100)`: wraps a paginated REST API, retrying politely " +
        "and reading a bearer token from a DuckDB secret or the environment.\n\n" +
        "The worker speaks Apache Arrow to DuckDB over stdin/stdout, a Unix socket, or HTTP, so the " +
        "same code runs as a local subprocess or a hosted service. See the " +
        "[VGI documentation](https://query.farm/vgi/docs/) to learn more.";

    /// <summary>Schema description for agents.</summary>
    public const string SchemaDocLlm =
        "The `main` schema holds the starter template's example functions, one per common worker " +
        "pattern. `greet(name)` and `greet(name, style)` are vectorized scalars: use them inside a " +
        "projection or predicate, one result per row; `style` is a constant that must be 'casual' or " +
        "'formal'. `sequence_table(count, step := 1)` is a deterministic table function that returns " +
        "`count` rows of `(n, squared)`; use it for demos, tests, and as a template for generators. " +
        "`fetch_items(base_url, page_size := 100)` reads every item from a paginated JSON REST API as " +
        "`(id, name, category, price)` rows, streaming one page per batch, retrying rate-limited " +
        "requests, and authenticating with a `starter_api` DuckDB secret or the `STARTER_API_TOKEN` " +
        "environment variable. Scalars cannot take named arguments; table functions can.";

    /// <summary>Schema description for people.</summary>
    public const string SchemaDocMd =
        "## main\n\n" +
        "The starter template's example functions, one per common worker pattern.\n\n" +
        "- **Scalars** answer one question per row and slot into a projection or predicate.\n" +
        "- **Table functions** return sets of rows, streamed to DuckDB in Arrow batches.\n" +
        "- **REST readers** page through an HTTP API and authenticate with a DuckDB secret.";

    /// <summary>Public analyst tasks for <c>vgi-lint simulate</c>; private graders live in
    /// vgi-agent-tests.yaml.</summary>
    public static readonly IReadOnlyList<Dictionary<string, string>> AgentTestTasks =
    [
        new() { ["name"] = "formal_greeting", ["prompt"] = "Using the starter worker, greet the name 'Grace' formally." },
        new()
        {
            ["name"] = "sum_of_squares",
            ["prompt"] = "Using the starter worker, what is the sum of the squared column for the first 10 rows " +
                         "of the sequence with the default step?",
        },
        new() { ["name"] = "stepped_sequence", ["prompt"] = "List the n values of a 4-row sequence that steps by 5, in ascending order." },
        new()
        {
            ["name"] = "items_per_category",
            ["prompt"] = "The items API is running at https://vgi-starter-api.query-farm.services/v1. How many items does it have in each category?",
        },
    ];

    /// <summary>Catalog-level tags.</summary>
    public static Dictionary<string, string> CatalogTags() => new()
    {
        ["vgi.title"] = "VGI Starter Worker",
        ["vgi.subtitle"] = "Example DuckDB functions to rename and replace: a scalar, a table function and a REST API reader.",
        ["vgi.doc_llm"] = CatalogDocLlm,
        ["vgi.doc_md"] = CatalogDocMd,
        ["vgi.source_url"] = SourceUrl,
        ["vgi.icon_url"] = "https://query.farm/media-kit/products/vgi/vgi-logo.png",
        ["vgi.license"] = "0BSD",
        ["vgi.author"] = "Query.Farm",
        ["vgi.copyright"] = "Copyright 2026 Query.Farm LLC",
        ["vgi.keywords"] = KeywordsArray("vgi, duckdb, starter template, example worker, rest api, table function"),
        ["vgi.support_contact"] = $"{SourceUrl}/issues",
        ["vgi.support_policy_url"] = $"{SourceUrl}#support",
        ["vgi.agent_test_tasks"] = ToJson(AgentTestTasks),
    };

    /// <summary>Tags for the <c>main</c> schema.</summary>
    /// <param name="allExamples">Every function's examples, for <c>vgi.example_queries</c>.</param>
    /// <returns>The tag dictionary.</returns>
    public static Dictionary<string, string> SchemaTags(IEnumerable<FunctionExample> allExamples) => new()
    {
        ["vgi.title"] = "Starter Functions",
        ["domain"] = "examples",
        ["vgi.keywords"] = KeywordsArray("greeting, sequence, generator, rest api, pagination"),
        ["vgi.doc_llm"] = SchemaDocLlm,
        ["vgi.doc_md"] = SchemaDocMd,
        ["vgi.categories"] = ToJson(SchemaCategories.Select(c => new Dictionary<string, string>
        {
            ["name"] = c.Name,
            ["title"] = c.Title,
            ["description"] = c.Description,
        })),
        ["vgi.example_queries"] = ExampleQueries(allExamples),
    };

    /// <summary>Renders examples as the <c>vgi.example_queries</c> JSON array.</summary>
    /// <param name="examples">The examples.</param>
    /// <returns>JSON text.</returns>
    public static string ExampleQueries(IEnumerable<FunctionExample> examples) => ToJson(
        examples.Select(e => new Dictionary<string, string> { ["description"] = e.Description, ["sql"] = e.Sql }));
}
