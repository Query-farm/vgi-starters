package farm.query.starter;

import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.protocol.FunctionExample;
import farm.query.vgi.scalar.Const;
import farm.query.vgi.scalar.ScalarFn;
import farm.query.vgi.scalar.Vector;
import java.util.List;
import java.util.stream.Stream;
import org.apache.arrow.vector.VarCharVector;

/**
 * Scalar overload: {@code greet(name, style)}.
 *
 * <p>{@code style} is a {@link Const} parameter. It must be a constant in the query (not a column),
 * and its {@code choices} are enforced when the query is bound, so {@code greet('Ada', 'loud')}
 * fails with a helpful error before any row runs.
 */
public final class GreetWithStyle extends ScalarFn {

  static final String STYLE_DOC =
      "Greeting tone: 'casual' gives 'Hi, <name>!', 'formal' gives 'Good day, <name>.'";

  private static final List<FunctionExample> EXAMPLES =
      List.of(
          Meta.example(
              "SELECT starter.main.greet('Ada', 'formal') AS greeting", "Greet one name formally"));

  /** Both overloads' examples: overloads share one SQL name, so they share one list. */
  static final List<FunctionExample> ALL_EXAMPLES =
      Stream.concat(
              Stream.of(
                  Meta.example(
                      "SELECT starter.main.greet('Ada') AS greeting", "Greet one name casually")),
              EXAMPLES.stream())
          .toList();

  @Override
  public String name() {
    return "greet";
  }

  @Override
  public String description() {
    return "Greet a name in a chosen tone: greet('Ada', 'formal') returns 'Good day, Ada.'";
  }

  @Override
  public FunctionMetadata metadata() {
    return FunctionMetadata.describe(description())
        .withExamples(EXAMPLES)
        .withTags(
            Meta.objectTags(
                "Greet (with tone)",
                "text",
                "greet, greeting, formal greeting, casual greeting, salutation, tone",
                "Return a greeting for each input name in the requested tone. style must be a"
                    + " constant, either 'casual' ('Hi, Ada!') or 'formal' ('Good day, Ada.'); any"
                    + " other value is rejected when the query is planned. NULL names produce"
                    + " NULL.",
                "## greet(name, style)\n\n"
                    + "A greeting in the chosen tone: `'casual'` or `'formal'`.\n\n"
                    + "```sql\nSELECT starter.main.greet('Ada', 'formal');  -- Good day, Ada.\n```"))
        .withTag("vgi.example_queries", Meta.exampleQueries(ALL_EXAMPLES));
  }

  /**
   * Return a greeting in {@code style} for every row.
   *
   * @param name the names to greet
   * @param style {@code casual} or {@code formal}, checked at bind time
   * @param result output vector, allocated by the framework
   */
  public void compute(
      @Vector(doc = Greet.NAME_DOC) VarCharVector name,
      @Const(
              doc = STYLE_DOC,
              choices = {"casual", "formal"})
          String style,
      VarCharVector result) {
    Greet.greet(name, style, result);
  }
}
