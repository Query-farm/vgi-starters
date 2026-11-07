package farm.query.starter;

import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.protocol.FunctionExample;
import farm.query.vgi.scalar.ScalarFn;
import farm.query.vgi.scalar.Vector;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.arrow.vector.VarCharVector;

/**
 * Scalar function example: {@code greet(name)}.
 *
 * <p>A scalar maps each input row to one output row. VGI hands {@code compute} a whole Arrow column
 * at a time (not one value), so loop over the vector, not over SQL calls.
 *
 * <p>SQL has no named arguments for scalar functions, so an optional argument is an
 * <em>overload</em>: two classes that share the SQL name {@code greet} and differ in arity. DuckDB
 * picks one by the number of arguments. See {@link GreetWithStyle} for the two-argument form.
 */
public final class Greet extends ScalarFn {

  static final String NAME_DOC = "Person or thing to greet; NULL in, NULL out.";

  private static final List<FunctionExample> EXAMPLES =
      List.of(
          Meta.example("SELECT starter.main.greet('Ada') AS greeting", "Greet one name casually"));

  @Override
  public String name() {
    return "greet";
  }

  @Override
  public String description() {
    return "Greet a name casually: greet('Ada') returns 'Hi, Ada!'";
  }

  @Override
  public FunctionMetadata metadata() {
    return FunctionMetadata.describe(description())
        .withExamples(EXAMPLES)
        .withTags(
            Meta.objectTags(
                "Greet (casual)",
                "text",
                "greet, greeting, hello, salutation, welcome message",
                "Return a **casual greeting** for each input name: greet('Ada') returns 'Hi, Ada!'."
                    + " NULL names produce NULL. This is the one-argument overload; call"
                    + " greet(name, style) for the formal tone. It is a vectorized scalar, so it"
                    + " works over a whole column of names in one call.",
                "## greet(name)\n\n"
                    + "A casual greeting for each row: `'Ada'` becomes `'Hi, Ada!'`.\n\n"
                    + "NULL in, NULL out. See `greet(name, style)` for the formal tone.\n\n"
                    + "```sql\nSELECT starter.main.greet(name) FROM people;\n```"))
        .withTag("vgi.example_queries", Meta.exampleQueries(GreetWithStyle.ALL_EXAMPLES));
  }

  /**
   * Return {@code Hi, <name>!} for every row.
   *
   * @param name the names to greet
   * @param result output vector, allocated by the framework
   */
  public void compute(@Vector(doc = NAME_DOC) VarCharVector name, VarCharVector result) {
    greet(name, "casual", result);
  }

  /**
   * Build greetings for a whole column; NULL names stay NULL.
   *
   * @param names input names
   * @param style {@code casual} or {@code formal}
   * @param result output vector
   */
  static void greet(VarCharVector names, String style, VarCharVector result) {
    int rows = names.getValueCount();
    result.allocateNew(rows);
    for (int i = 0; i < rows; i++) {
      if (names.isNull(i)) {
        result.setNull(i);
        continue;
      }
      byte[] out =
          greeting(new String(names.get(i), StandardCharsets.UTF_8), style)
              .getBytes(StandardCharsets.UTF_8);
      result.setSafe(i, out, 0, out.length);
    }
    result.setValueCount(rows);
  }

  /**
   * One greeting.
   *
   * @param name the name
   * @param style {@code casual} or {@code formal}
   * @return the greeting text
   */
  static String greeting(String name, String style) {
    return "formal".equals(style) ? "Good day, " + name + "." : "Hi, " + name + "!";
  }
}
