package farm.query.starter.recipes;

import farm.query.vgi.Worker;
import farm.query.vgi.cache.CacheControl;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.function.FunctionSpec;
import farm.query.vgi.table.SimpleTableFunction;
import farm.query.vgi.table.TableInitParams;
import farm.query.vgi.table.TableProducerState;
import farm.query.vgi.types.Schemas;
import farm.query.vgirpc.AnnotatedBatch;
import farm.query.vgirpc.CallContext;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Recipe: let DuckDB cache an expensive result.
 *
 * <p>The worker <em>advertises</em> how long a result stays fresh. DuckDB caches it, and repeat
 * queries inside the TTL never reach the worker. After the TTL, DuckDB revalidates with an ETag; if
 * nothing changed the worker answers "not modified" with zero rows.
 *
 * <pre>
 *   SELECT * FROM cache_expensive_call.main.exchange_rates();
 * </pre>
 */
public final class CacheExpensiveCall extends SimpleTableFunction {

  /**
   * In a real worker, derive this from the upstream: a Last-Modified header, a version column, a
   * content hash. It is the only thing revalidation compares.
   */
  static final String DATA_VERSION = "\"rates-v1\"";

  static final String[] CURRENCIES = {"EUR", "GBP", "JPY"};
  static final double[] RATES = {1.09, 1.27, 0.0067};

  private static final Schema SCHEMA =
      new Schema(
          List.of(
              Schemas.nullable("currency", Schemas.UTF8),
              Schemas.nullable("rate", Schemas.FLOAT64)));

  private static final FunctionSpec SPEC =
      new FunctionSpec(
          "exchange_rates",
          FunctionMetadata.describe("Exchange rates from a slow upstream, cacheable for 5 minutes"),
          List.of());

  @Override
  public FunctionSpec spec() {
    return SPEC;
  }

  @Override
  protected Schema outputSchema() {
    return SCHEMA;
  }

  @Override
  public TableProducerState createProducer(TableInitParams params) {
    return new Rates();
  }

  /** Emits the rates once, with cache metadata on the first (only) batch. */
  public static final class Rates extends TableProducerState {
    public boolean done;

    /** Required no-arg constructor for state deserialization. */
    public Rates() {}

    @Override
    public void produceTick(OutputCollector out, CallContext ctx) {
      produce(null, out);
    }

    @Override
    public void produceTick(AnnotatedBatch input, OutputCollector out, CallContext ctx) {
      // A revalidation request carries the ETag DuckDB holds.
      produce(
          input == null ? null : input.customMetadata().get(CacheControl.IF_NONE_MATCH_KEY), out);
    }

    private void produce(String ifNoneMatch, OutputCollector out) {
      if (done) {
        out.finish();
        return;
      }
      done = true;
      VectorSchemaRoot root = VectorSchemaRoot.create(SCHEMA, Allocators.root());
      root.allocateNew();
      if (DATA_VERSION.equals(ifNoneMatch)) {
        // The client's copy is current: zero rows + not_modified.
        root.setRowCount(0);
        out.emit(
            root,
            CacheControl.builder()
                .ttl(300)
                .etag(DATA_VERSION)
                .revalidatable(true)
                .notModified(true)
                .build()
                .toMetadata());
        return;
      }
      VarCharVector currency = (VarCharVector) root.getVector("currency");
      Float8Vector rate = (Float8Vector) root.getVector("rate");
      for (int i = 0; i < CURRENCIES.length; i++) {
        currency.setSafe(i, CURRENCIES[i].getBytes(StandardCharsets.UTF_8));
        rate.setSafe(i, RATES[i]);
      }
      root.setRowCount(CURRENCIES.length);
      // Cache metadata must ride on the FIRST batch.
      out.emit(
          root,
          CacheControl.builder()
              .ttl(300)
              .etag(DATA_VERSION)
              .revalidatable(true)
              .staleWhileRevalidate(60)
              .staleIfError(600)
              .build()
              .toMetadata());
    }
  }

  static Worker worker() {
    return Worker.builder()
        .catalogName("cache_expensive_call")
        .registerTable(new CacheExpensiveCall());
  }
}
