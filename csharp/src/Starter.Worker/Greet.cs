// Scalar function example: greet.
//
// A scalar maps each input row to one output row. VGI hands Compute a whole
// Arrow column at a time (not one value), and Compute appends one result per row
// to the output builder.
//
// SQL has no named arguments for scalar functions, so an optional argument is an
// *overload*: two classes that share the SQL name `greet` and differ in arity.
// DuckDB picks one by the number of arguments.
//
// `style` is a [ConstParam]. It must be a constant in the query (not a column),
// and the SDK checks its choices while the query is planned, so greet('Ada', 'loud') fails with a
// helpful error before any row runs.

using Apache.Arrow;
using QueryFarm.Vgi.Attributes;
using QueryFarm.Vgi.Protocol;
using QueryFarm.Vgi.Scalar;

namespace Starter.Worker;

/// <summary>Shared greeting logic and metadata for both <c>greet</c> overloads.</summary>
public static class Greeting
{
    /// <summary>The allowed values of <c>style</c>.</summary>
    public static readonly IReadOnlyList<string> Styles = ["casual", "formal"];

    internal const string NameDoc = "Person or thing to greet; NULL in, NULL out.";
    internal const string StyleDoc = "Greeting tone: 'casual' gives 'Hi, <name>!', 'formal' gives 'Good day, <name>.'";

    /// <summary>Both overloads' examples; overloads share one SQL name, so they publish one list.</summary>
    internal static readonly IReadOnlyList<FunctionExample> AllExamples =
    [
        Meta.Example("SELECT starter.main.greet('Ada') AS greeting", "Greet one name casually"),
        Meta.Example("SELECT starter.main.greet('Ada', 'formal') AS greeting", "Greet one name formally"),
    ];

    /// <summary>Appends a greeting in <paramref name="style"/> for every row; NULL names stay NULL.</summary>
    /// <param name="names">Input column.</param>
    /// <param name="style">'casual' or 'formal'.</param>
    /// <param name="result">Output builder.</param>
    public static void Build(StringArray names, string style, StringArray.Builder result)
    {
        var (prefix, suffix) = style == "formal" ? ("Good day, ", ".") : ("Hi, ", "!");
        result.Reserve(names.Length);
        for (var row = 0; row < names.Length; row++)
        {
            if (names.IsNull(row))
            {
                result.AppendNull();
            }
            else
            {
                result.Append(prefix + names.GetString(row) + suffix);
            }
        }
    }
}

/// <summary><c>greet(name)</c>: a casual greeting for every row.</summary>
public sealed class Greet : ScalarFn
{
    /// <inheritdoc />
    public override string Name => "greet";

    /// <inheritdoc />
    public override string Description => "Greet a name casually: greet('Ada') returns 'Hi, Ada!'";

    /// <inheritdoc />
    public override IReadOnlyList<FunctionExample> Examples => Greeting.AllExamples.Take(1).ToList();

    /// <inheritdoc />
    public override IReadOnlyDictionary<string, string> Tags => DiscoveryTags;

    private static readonly IReadOnlyDictionary<string, string> DiscoveryTags = new Dictionary<string, string>(Meta.ObjectTags(
        title: "Greet (casual)",
        category: "text",
        keywords: "greet, greeting, hello, salutation, welcome message",
        docLlm: "Return a **casual greeting** for each input name: the greeting for 'Ada' is 'Hi, Ada!'. " +
                "NULL names produce NULL. This is the one-argument overload; call greet with a second " +
                "argument for the formal tone. It is a vectorized scalar, so it works over a whole column:\n\n" +
                "```sql\nSELECT starter.main.greet(name) FROM people;\n```",
        docMd: "## greet(name)\n\n" +
               "A casual greeting for each row: 'Ada' becomes 'Hi, Ada!'.\n\n" +
               "NULL in, NULL out. See `greet(name, style)` for the formal tone."))
    {
        ["vgi.example_queries"] = Meta.ExampleQueries(Greeting.AllExamples),
    };

    private static void Compute([Param(Doc = Greeting.NameDoc)] StringArray name, StringArray.Builder result) =>
        Greeting.Build(name, "casual", result);
}

/// <summary><c>greet(name, style)</c>: a greeting in the chosen tone.</summary>
public sealed class GreetWithStyle : ScalarFn
{
    /// <inheritdoc />
    public override string Name => "greet";

    /// <inheritdoc />
    public override string Description => "Greet a name in a chosen tone: greet('Ada', 'formal') returns 'Good day, Ada.'";

    /// <inheritdoc />
    public override IReadOnlyList<FunctionExample> Examples => Greeting.AllExamples.Skip(1).ToList();

    /// <inheritdoc />
    public override IReadOnlyDictionary<string, string> Tags => DiscoveryTags;

    private static readonly IReadOnlyDictionary<string, string> DiscoveryTags = new Dictionary<string, string>(Meta.ObjectTags(
        title: "Greet (with tone)",
        category: "text",
        keywords: "greet, greeting, formal greeting, casual greeting, salutation, tone",
        docLlm: "Return a greeting for each input name in the requested tone. The style argument must be a " +
                "constant, either 'casual' (giving 'Hi, Ada!') or 'formal' (giving 'Good day, Ada.'); " +
                "any other value is rejected when the query is planned. NULL names produce NULL.",
        docMd: "## greet(name, style)\n\n" +
               "A greeting in the chosen tone: 'casual' or 'formal'.\n\n" +
               "```sql\nSELECT starter.main.greet('Ada', 'formal');  -- Good day, Ada.\n```"))
    {
        ["vgi.example_queries"] = Meta.ExampleQueries(Greeting.AllExamples),
    };

    // Choices are declared on the [ConstParam]: the SDK publishes them as vgi_choices
    // and rejects any other value when the query is planned.
    private static void Compute(
        [Param(Doc = Greeting.NameDoc)] StringArray name,
        [ConstParam(Doc = Greeting.StyleDoc, Choices = ["casual", "formal"])] string style,
        StringArray.Builder result) => Greeting.Build(name, style, result);

}
