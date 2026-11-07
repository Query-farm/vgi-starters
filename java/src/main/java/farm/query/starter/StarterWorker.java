package farm.query.starter;

import farm.query.vgi.Worker;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Assemble the functions into a catalog and run the worker.
 *
 * <p>To add a function: write it (see {@link Greet} / {@link SequenceTable} / {@link FetchItems}),
 * then register it in {@link #build()}.
 *
 * <pre>
 *   bin/vgi-starter-worker                      # stdio: what DuckDB launches by default
 *   bin/vgi-starter-worker --http --port 8000   # HTTP: for Docker / hosted deploys
 *   bin/vgi-starter-worker --unix /tmp/w.sock   # AF_UNIX: what LOCATION 'launch:...' uses
 * </pre>
 *
 * <p>Attach it from DuckDB:
 *
 * <pre>
 *   ATTACH 'starter' (TYPE vgi, LOCATION 'launch:/abs/path/to/bin/vgi-starter-worker');
 * </pre>
 */
public final class StarterWorker {
  private StarterWorker() {}

  /**
   * Build the worker: one catalog, one {@code main} schema.
   *
   * @return the configured worker, ready to run
   */
  public static Worker build() {
    Map<String, String> schemaTags = new LinkedHashMap<>();
    schemaTags.put("vgi.title", "Starter Functions");
    schemaTags.put("domain", "examples");
    schemaTags.put(
        "vgi.keywords", Meta.keywordsArray("greeting, sequence, generator, rest api, pagination"));
    schemaTags.put("vgi.doc_llm", Meta.SCHEMA_DOC_LLM);
    schemaTags.put("vgi.doc_md", Meta.SCHEMA_DOC_MD);
    schemaTags.put("vgi.categories", Meta.json(Meta.SCHEMA_CATEGORIES));
    schemaTags.put(
        "vgi.example_queries",
        Meta.exampleQueries(
            Stream.of(GreetWithStyle.ALL_EXAMPLES, SequenceTable.EXAMPLES, FetchItems.EXAMPLES)
                .flatMap(List::stream)
                .toList()));

    return Worker.builder()
        .catalogName(Meta.CATALOG_NAME)
        .catalogComment(Meta.CATALOG_DOC_MD)
        .catalogTags(Meta.CATALOG_TAGS)
        .sourceUrl(Meta.SOURCE_URL)
        .schemaComment("main", Meta.SCHEMA_DOC_MD)
        .schemaTags("main", schemaTags)
        .secretTypes(FetchItems.API_SECRET_TYPE)
        .registerScalar(new Greet())
        .registerScalar(new GreetWithStyle())
        .registerTable(new SequenceTable())
        .registerTable(new FetchItems());
  }

  /**
   * Entry point: {@code --http [--host H] [--port P]}, {@code --unix PATH}, or stdio (no args).
   *
   * @param args command-line flags; {@code --debug} turns on debug logging (stderr)
   */
  public static void main(String[] args) {
    configureRuntime(args);
    build().runFromArgs(args);
  }

  /**
   * Settings that must be in place before Arrow or SLF4J initialize.
   *
   * @param args command-line flags
   */
  static void configureRuntime(String[] args) {
    // Arrow allocates through Netty, which turns off sun.misc.Unsafe on Java 25+ unless told
    // otherwise (apache/arrow-java#728). bin/vgi-starter-worker passes it too.
    System.setProperty("io.netty.noUnsafe", "false");
    // slf4j-simple writes to stderr; stdout is the protocol channel.
    System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
    if (Arrays.asList(args).contains("--debug")) {
      System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug");
    }
  }
}
