package farm.query.starter.recipes;

import farm.query.vgi.Worker;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Run one recipe as a worker: {@code bin/recipes <recipe> [worker flags]}.
 *
 * <pre>
 *   ATTACH 'aggregate' (TYPE vgi, LOCATION '/abs/path/bin/recipes aggregate');
 * </pre>
 */
public final class Recipes {
  private Recipes() {}

  static final Map<String, Supplier<Worker>> RECIPES = new LinkedHashMap<>();

  static {
    RECIPES.put("transform_rows", TransformRows::worker);
    RECIPES.put("aggregate", Aggregate::worker);
    RECIPES.put("pushdown_sqlite", PushdownSqlite::worker);
    RECIPES.put("cache_expensive_call", CacheExpensiveCall::worker);
    RECIPES.put("catalog_tables", CatalogTables::worker);
    RECIPES.put("copy_to_sink", CopyToSink::worker);
  }

  /**
   * Serve the recipe named by the first argument; the rest are worker flags.
   *
   * @param args {@code <recipe> [--http ...|--unix PATH]}
   */
  public static void main(String[] args) {
    if (args.length < 1 || !RECIPES.containsKey(args[0])) {
      System.err.println("usage: recipes <" + String.join("|", RECIPES.keySet()) + "> [flags]");
      System.exit(2);
    }
    System.setProperty("io.netty.noUnsafe", "false");
    RECIPES.get(args[0]).get().runFromArgs(Arrays.copyOfRange(args, 1, args.length));
  }
}
