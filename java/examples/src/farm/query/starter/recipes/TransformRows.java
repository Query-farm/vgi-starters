package farm.query.starter.recipes;

import farm.query.vgi.InvalidArgumentException;
import farm.query.vgi.Worker;
import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.protocol.BindResponse;
import farm.query.vgi.tableinout.PassthroughTIOFunction;
import farm.query.vgi.tableinout.TableInOutBindParams;
import farm.query.vgi.tableinout.TableInOutExchangeState;
import farm.query.vgi.tableinout.TableInOutInitParams;
import farm.query.vgirpc.AnnotatedBatch;
import farm.query.vgirpc.CallContext;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import java.util.List;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.apache.arrow.vector.util.TransferPair;

/**
 * Recipe: transform the rows of a query with a table in-out function.
 *
 * <p>A table in-out function receives another query's rows, batch by batch, and emits rows back.
 * Use it to clean, enrich, filter or reshape data in SQL:
 *
 * <pre>
 *   SELECT * FROM transform_rows.main.positive_only((SELECT * FROM readings));
 * </pre>
 *
 * <p>Here: keep only rows whose {@code value} column is greater than zero. The output schema equals
 * the input schema, so it works on any table that has a {@code value} column.
 */
public final class TransformRows extends PassthroughTIOFunction {

  @Override
  public String name() {
    return "positive_only";
  }

  @Override
  public FunctionMetadata metadata() {
    return FunctionMetadata.describe("Keep the rows of a query whose value column is positive");
  }

  @Override
  public List<ArgSpec> argumentSpecs() {
    return List.of(ArgSpec.table("data", 0));
  }

  /** Output columns are the input columns; fail early without a {@code value} column. */
  @Override
  public BindResponse onBind(TableInOutBindParams params) {
    Schema in = params.inputSchema();
    if (in != null && !in.getFields().isEmpty() && in.findField("value") == null) {
      throw new InvalidArgumentException("positive_only needs an input column named 'value'");
    }
    return super.onBind(params);
  }

  @Override
  public TableInOutExchangeState createExchange(TableInOutInitParams params) {
    return new Filter();
  }

  /** Filters each incoming batch and emits the survivors. */
  public static final class Filter extends TableInOutExchangeState {
    @Override
    public void onInputBatch(AnnotatedBatch input, OutputCollector out, CallContext ctx) {
      VectorSchemaRoot in = input.root();
      FieldVector value = in.getVector("value");
      VectorSchemaRoot result = VectorSchemaRoot.create(in.getSchema(), Allocators.root());
      result.allocateNew();
      int kept = 0;
      for (int row = 0; row < in.getRowCount(); row++) {
        // NULL -> drop. getObject() works for every numeric vector type.
        if (value.getObject(row) instanceof Number n && n.doubleValue() > 0) {
          for (int c = 0; c < in.getFieldVectors().size(); c++) {
            TransferPair copy = in.getVector(c).makeTransferPair(result.getVector(c));
            copy.copyValueSafe(row, kept);
          }
          kept++;
        }
      }
      for (FieldVector v : result.getFieldVectors()) {
        v.setValueCount(kept);
      }
      result.setRowCount(kept);
      out.emit(result); // emit() takes ownership; never emit the input root itself
    }
  }

  static Worker worker() {
    return Worker.builder().catalogName("transform_rows").registerTableInOut(new TransformRows());
  }
}
