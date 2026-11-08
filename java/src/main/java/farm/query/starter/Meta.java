package farm.query.starter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.protocol.FunctionExample;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;

/**
 * Discovery metadata: the one file to edit when you rename or extend the worker.
 *
 * <p>DuckDB, coding agents and the {@code vgi-lint} metadata checker all read these tags. Keep
 * every object documented and {@code make lint-explain} stays at 100.
 *
 * <ul>
 *   <li>Catalog-level tags (title, license, author, links, long description) live in {@link
 *       #CATALOG_TAGS}.
 *   <li>Every function carries {@link #objectTags}: a title, a category from {@link
 *       #SCHEMA_CATEGORIES}, search keywords, and LLM- and human-facing docs.
 * </ul>
 */
public final class Meta {
  private Meta() {}

  /** The catalog name users ATTACH as. */
  public static final String CATALOG_NAME = "starter";

  /** Where the source lives; also the support links below. */
  public static final String SOURCE_URL = "https://github.com/Query-farm/vgi-starter-java";

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Categories group functions in docs and agent tools. Every object's {@code vgi.category} must
   * name one of these.
   */
  public static final List<Map<String, String>> SCHEMA_CATEGORIES =
      List.of(
          category("text", "Text", "Functions that build or transform strings."),
          category("generators", "Generators", "Table functions that generate rows."),
          category("api", "REST APIs", "Table functions that read from HTTP APIs."));

  private static Map<String, String> category(String name, String title, String description) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("title", title);
    m.put("description", description);
    return m;
  }

  /**
   * Serialize a value to JSON (tags are strings; structured tags hold JSON).
   *
   * @param value lists, maps, strings and numbers
   * @return the JSON text
   */
  public static String json(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Turn {@code "a, b, c"} into the JSON array string {@code vgi.keywords} requires.
   *
   * @param keywords comma-separated keywords
   * @return a JSON array of the trimmed keywords
   */
  public static String keywordsArray(String keywords) {
    return json(
        Arrays.stream(keywords.split(",")).map(String::strip).filter(k -> !k.isEmpty()).toList());
  }

  /**
   * Build the standard per-object discovery tags.
   *
   * @param title short human title
   * @param category one of {@link #SCHEMA_CATEGORIES}
   * @param keywords comma-separated search keywords
   * @param docLlm description written for agents
   * @param docMd Markdown description written for people
   * @return the tag map
   */
  public static Map<String, String> objectTags(
      String title, String category, String keywords, String docLlm, String docMd) {
    Set<String> known =
        SCHEMA_CATEGORIES.stream().map(c -> c.get("name")).collect(Collectors.toSet());
    if (!known.contains(category)) {
      throw new IllegalArgumentException(
          "unknown category '" + category + "'; add it to SCHEMA_CATEGORIES");
    }
    Map<String, String> tags = new LinkedHashMap<>();
    tags.put("vgi.title", title);
    tags.put("vgi.category", category);
    tags.put("vgi.keywords", keywordsArray(keywords));
    tags.put("vgi.doc_llm", docLlm);
    tags.put("vgi.doc_md", docMd);
    return tags;
  }

  /**
   * Publish examples as the {@code vgi.example_queries} tag (with descriptions).
   *
   * @param examples the function's examples
   * @return JSON array of {@code {description, sql}}
   */
  public static String exampleQueries(List<FunctionExample> examples) {
    return json(
        examples.stream()
            .map(e -> {
              Map<String, String> m = new LinkedHashMap<>();
              m.put("description", e.description());
              m.put("sql", e.sql());
              return m;
            })
            .toList());
  }

  /**
   * An example query with a description.
   *
   * @param sql fully qualified, analytical SQL
   * @param description what it shows
   * @return the example
   */
  public static FunctionExample example(String sql, String description) {
    return new FunctionExample(sql, description, null);
  }

  /**
   * A positional, constant argument with a doc string and constraints.
   *
   * @param name argument name
   * @param position zero-based position
   * @param type Arrow type
   * @param doc what the argument means (published to agents)
   * @param constraints ranges, choices or a pattern; {@code null} for none
   * @return the argument spec
   */
  public static ArgSpec positional(
      String name, int position, ArrowType type, String doc, ArgSpec.Constraints constraints) {
    return new ArgSpec(
        name,
        position,
        type,
        doc,
        true,
        false,
        "",
        List.of(),
        false,
        false,
        false,
        List.of(),
        constraints);
  }

  /**
   * A named argument ({@code name := value}) with a default, doc string and constraints.
   *
   * @param name argument name
   * @param type Arrow type
   * @param defaultValue default, as SQL literal text
   * @param doc what the argument means (published to agents)
   * @param constraints ranges, choices or a pattern; {@code null} for none
   * @return the argument spec
   */
  public static ArgSpec named(
      String name,
      ArrowType type,
      String defaultValue,
      String doc,
      ArgSpec.Constraints constraints) {
    return new ArgSpec(
        name,
        -1,
        type,
        doc,
        true,
        true,
        defaultValue,
        List.of(),
        false,
        false,
        false,
        List.of(),
        constraints);
  }

  /**
   * An Arrow field whose comment shows up in DuckDB's {@code DESCRIBE} and agent tools.
   *
   * @param name column name
   * @param type Arrow type
   * @param comment column description
   * @param nullable whether NULLs may appear
   * @return the field
   */
  public static Field column(String name, ArrowType type, String comment, boolean nullable) {
    return new Field(name, new FieldType(nullable, type, null, Map.of("comment", comment)), null);
  }

  static final String CATALOG_DOC_LLM =
      "An example VGI worker generated from the Query.Farm starter template. It shows the three"
          + " shapes most workers need: a vectorized scalar function (`greet`, with a one- and"
          + " two-argument overload), a deterministic table function with a named default argument"
          + " (`sequence_table`), and a table function that pages through a REST API with retries"
          + " and an optional bearer token taken from a DuckDB secret or an environment variable"
          + " (`fetch_items`). Replace these with your own functions; keep the metadata complete so"
          + " agents can discover and call them.";

  static final String CATALOG_DOC_MD =
      "# VGI starter worker\n\n"
          + "**An example [VGI](https://query.farm/vgi) worker that brings custom functions into"
          + " DuckDB SQL.** It was generated from the Query.Farm starter template and is meant to be"
          + " renamed and rewritten: every function here demonstrates one pattern you will reuse.\n\n"
          + "- `greet(name)` and `greet(name, style)`: a vectorized scalar with an overload and a"
          + " constrained constant argument.\n"
          + "- `sequence_table(count, step := 1)`: a deterministic table function that streams its"
          + " output in batches.\n"
          + "- `fetch_items(base_url, page_size := 100)`: wraps a paginated REST API, retrying"
          + " politely and reading a bearer token from a DuckDB secret or the environment.\n\n"
          + "The worker speaks Apache Arrow to DuckDB over stdin/stdout, a Unix socket, or HTTP, so"
          + " the same code runs as a local subprocess or a hosted service. See the"
          + " [VGI documentation](https://query.farm/vgi/docs/) to learn more.";

  static final String SCHEMA_DOC_LLM =
      "The `main` schema holds the starter template's example functions, one per common worker"
          + " pattern. `greet(name)` and `greet(name, style)` are vectorized scalars: use them inside"
          + " a projection or predicate, one result per row; `style` is a constant that must be"
          + " 'casual' or 'formal'. `sequence_table(count, step := 1)` is a deterministic table"
          + " function that returns `count` rows of `(n, squared)`; use it for demos, tests, and as"
          + " a template for generators. `fetch_items(base_url, page_size := 100)` reads every item"
          + " from a paginated JSON REST API as `(id, name, category, price)` rows, streaming one"
          + " page per batch, retrying rate-limited requests, and authenticating with a"
          + " `starter_api` DuckDB secret or the `STARTER_API_TOKEN` environment variable. Scalars"
          + " cannot take named arguments; table functions can.";

  static final String SCHEMA_DOC_MD =
      "## main\n\n"
          + "The starter template's example functions, one per common worker pattern.\n\n"
          + "- **Scalars** answer one question per row and slot into a projection or predicate.\n"
          + "- **Table functions** return sets of rows, streamed to DuckDB in Arrow batches.\n"
          + "- **REST readers** page through an HTTP API and authenticate with a DuckDB secret.";

  /** Public analyst tasks for {@code vgi-lint simulate}; graders live in vgi-agent-tests.yaml. */
  static final List<Map<String, String>> AGENT_TEST_TASKS =
      List.of(
          task("formal_greeting", "Using the starter worker, greet the name 'Grace' formally."),
          task(
              "sum_of_squares",
              "Using the starter worker, what is the sum of the squared column for the first 10"
                  + " rows of the sequence with the default step?"),
          task(
              "stepped_sequence",
              "List the n values of a 4-row sequence that steps by 5, in ascending order."),
          task(
              "items_per_category",
              "The items API is running at https://vgi-starter-api.query-farm.services/v1. How many items does it have in"
                  + " each category?"));

  private static Map<String, String> task(String name, String prompt) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("prompt", prompt);
    return m;
  }

  /** Catalog-level tags. */
  static final Map<String, String> CATALOG_TAGS = catalogTags();

  private static Map<String, String> catalogTags() {
    Map<String, String> t = new LinkedHashMap<>();
    t.put("vgi.title", "VGI Starter Worker");
    t.put(
        "vgi.subtitle",
        "Example DuckDB functions to rename and replace: a scalar, a table function and a REST API reader.");
    t.put("vgi.doc_llm", CATALOG_DOC_LLM);
    t.put("vgi.doc_md", CATALOG_DOC_MD);
    t.put("vgi.source_url", SOURCE_URL);
    t.put("vgi.icon_url", "https://query.farm/media-kit/products/vgi/vgi-logo.png");
    t.put("vgi.license", "0BSD");
    t.put("vgi.author", "Query.Farm");
    t.put("vgi.copyright", "Copyright 2026 Query.Farm LLC");
    t.put(
        "vgi.keywords",
        keywordsArray(
            "vgi, duckdb, starter template, example worker, rest api, table function, java"));
    t.put("vgi.support_contact", SOURCE_URL + "/issues");
    t.put("vgi.support_policy_url", SOURCE_URL + "#support");
    t.put("vgi.agent_test_tasks", json(AGENT_TEST_TASKS));
    return t;
  }
}
