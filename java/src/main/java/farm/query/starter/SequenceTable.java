package farm.query.starter;

import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.function.FunctionSpec;
import farm.query.vgi.function.ParameterExtractor;
import farm.query.vgi.protocol.FunctionExample;
import farm.query.vgi.table.SimpleTableFunction;
import farm.query.vgi.table.TableBindParams;
import farm.query.vgi.table.TableInitParams;
import farm.query.vgi.table.TableProducerState;
import farm.query.vgi.types.Schemas;
import farm.query.vgirpc.CallContext;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Table function example: {@code sequence_table(count, step := 1)}.
 *
 * <p>A table function returns a set of rows. This one is a deterministic generator, the simplest
 * shape to learn from:
 *
 * <ul>
 *   <li>{@code count} is positional and required; {@code step} is <em>named</em> with a default, so
 *       callers write {@code sequence_table(5)} or {@code sequence_table(5, step := 2)}. (Named
 *       arguments work on table functions, not on scalars.)
 *   <li>{@code ge}/{@code le} constraints are published so agents know the valid range, and checked
 *       in {@link #onBind} before any row is produced.
 *   <li>Output streams in bounded batches. {@link State} remembers where the last batch stopped.
 *       Its public fields are the whole cursor, so the same code works over HTTP, where the state
 *       travels between requests in a signed token.
 * </ul>
 */
public final class SequenceTable extends SimpleTableFunction {

  static final int BATCH_ROWS = 10_000;
  static final long MAX_COUNT = 1_000_000L;

  static final Schema OUTPUT_SCHEMA =
      new Schema(
          List.of(
              Meta.column("n", Schemas.INT64, "The generated value: row index times step.", false),
              Meta.column("squared", Schemas.INT64, "n multiplied by itself.", false)));

  private static final List<ArgSpec> ARGS =
      List.of(
          Meta.positional(
              "count",
              0,
              Schemas.INT64,
              "How many rows to generate, from 0 to 1,000,000.",
              ArgSpec.Constraints.range(0, MAX_COUNT, null, null)),
          Meta.named(
              "step",
              Schemas.INT64,
              "1",
              "Gap between consecutive n values; at least 1.",
              ArgSpec.Constraints.range(1, null, null, null)));

  static final List<FunctionExample> EXAMPLES =
      List.of(
          Meta.example(
              "SELECT n, squared FROM starter.main.sequence_table(5) ORDER BY n",
              "The first five values and their squares"),
          Meta.example(
              "SELECT n FROM starter.main.sequence_table(4, step := 10) ORDER BY n",
              "Four values stepping by ten"));

  private static final FunctionSpec SPEC =
      new FunctionSpec("sequence_table", metadata(EXAMPLES), ARGS);

  private static FunctionMetadata metadata(List<FunctionExample> examples) {
    Map<String, Object> threeRows = new LinkedHashMap<>();
    threeRows.put("description", "Three rows stepping by two");
    threeRows.put(
        "sql", "SELECT n, squared FROM starter.main.sequence_table(3, step := 2) ORDER BY n");
    threeRows.put("expected_result", List.of(List.of(0, 0), List.of(2, 4), List.of(4, 16)));
    Map<String, Object> countRows = new LinkedHashMap<>();
    countRows.put("description", "Count the rows generated");
    countRows.put("sql", "SELECT count(*) AS rows FROM starter.main.sequence_table(1000)");
    countRows.put("expected_result", List.of(List.of(1000)));

    return FunctionMetadata.describe("Generate count rows of (n, squared), stepping n by step")
        .withExamples(examples)
        .withTags(
            Meta.objectTags(
                "Sequence Table",
                "generators",
                "sequence, series, range, generate rows, numbers, squares",
                "Generate a deterministic table of `count` rows with columns `n` and `squared`,"
                    + " where `n` runs 0, step, 2*step, and so on. `count` is positional (0 to"
                    + " 1,000,000); `step` is a named argument that defaults to 1. Rows stream back"
                    + " in batches, so large counts do not buffer in memory.",
                "## sequence_table(count, step := 1)\n\n"
                    + "`count` rows of `(n, squared)` with `n = 0, step, 2*step, ...`.\n\n"
                    + "Deterministic, so it is handy for tests and demos."))
        .withTag(
            "vgi.result_columns_schema",
            Meta.json(
                List.of(
                    column("n", "BIGINT", "The generated value: row index times step."),
                    column("squared", "BIGINT", "n multiplied by itself."))))
        .withTag("vgi.executable_examples", Meta.json(List.of(threeRows, countRows)))
        .withTag("vgi.example_queries", Meta.exampleQueries(examples));
  }

  static Map<String, String> column(String name, String type, String description) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("name", name);
    m.put("type", type);
    m.put("description", description);
    return m;
  }

  @Override
  public FunctionSpec spec() {
    return SPEC;
  }

  @Override
  protected Schema outputSchema() {
    return OUTPUT_SCHEMA;
  }

  /** The validated arguments. */
  record Args(long count, long step) {}

  /**
   * Read and validate the arguments. The SDK enforces positional constraints itself; named ones are
   * checked here too, so both give a clear error at bind time.
   *
   * @param p the call's arguments
   * @return validated arguments
   */
  static Args args(ParameterExtractor p) {
    long count = p.positional(0, "count").asLong().between(0, MAX_COUNT).required();
    long step = p.named("step").asLong().ge(1).orElse(1L);
    return new Args(count, step);
  }

  @Override
  public long cardinality(TableBindParams params) {
    return params.arguments().positional().isEmpty()
        ? -1L
        : args(ParameterExtractor.of(params.arguments())).count();
  }

  @Override
  public TableProducerState createProducer(TableInitParams params) {
    Args a = args(ParameterExtractor.of(params.arguments()));
    return new State(a.count(), a.step(), params.outputSchema());
  }

  /** Cursor: how many rows have been emitted so far. Public fields = the serialized state. */
  public static final class State extends TableProducerState {
    public long count;
    public long step;
    public long emitted;

    /** Projected output schema (DuckDB may ask for only some columns). */
    public Schema schema;

    /** Required no-arg constructor: the HTTP transport rebuilds state from its fields. */
    public State() {}

    State(long count, long step, Schema schema) {
      this.count = count;
      this.step = step;
      this.schema = schema;
    }

    @Override
    public void produceTick(OutputCollector out, CallContext ctx) {
      if (emitted >= count) {
        out.finish();
        return;
      }
      int rows = (int) Math.min(BATCH_ROWS, count - emitted);
      VectorSchemaRoot root = VectorSchemaRoot.create(schema, Allocators.root());
      root.allocateNew();
      for (Field f : schema.getFields()) {
        BigIntVector v = (BigIntVector) root.getVector(f.getName());
        boolean squared = f.getName().equals("squared");
        for (int i = 0; i < rows; i++) {
          long n = (emitted + i) * step;
          v.setSafe(i, squared ? n * n : n);
        }
      }
      root.setRowCount(rows);
      emitted += rows;
      out.emit(root); // emit() takes ownership of the root
    }
  }
}
