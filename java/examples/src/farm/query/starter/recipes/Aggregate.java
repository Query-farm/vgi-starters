package farm.query.starter.recipes;

import farm.query.vgi.Worker;
import farm.query.vgi.aggregate.AggregateFunction;
import farm.query.vgi.function.FunctionSpec;
import farm.query.vgi.types.Schemas;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Recipe: an aggregate function, driven by GROUP BY.
 *
 * <pre>
 *   SELECT category, aggregate.main.int_sum(amount) FROM sales GROUP BY category;
 * </pre>
 *
 * <p>Aggregates keep per-group state across phases:
 *
 * <ul>
 *   <li>{@code update} folds a batch of values into each group's state
 *   <li>{@code combine} merges partial states for the same group (parallel execution)
 *   <li>{@code finalize} turns each group's state into one output value
 * </ul>
 *
 * <p>State is {@link Serializable} because partial states can be combined in a different process.
 */
public final class Aggregate implements AggregateFunction<Aggregate.SumState> {

  /** Running total for one group. */
  public static final class SumState implements Serializable {
    private static final long serialVersionUID = 1L;
    long total;
  }

  private static final FunctionSpec SPEC =
      FunctionSpec.builder("int_sum")
          .description("Sum integer values per group")
          .arg("value", Schemas.INT64)
          .build();

  private static final Schema OUTPUT =
      new Schema(List.of(Schemas.nullable("result", Schemas.INT64)));

  @Override
  public FunctionSpec spec() {
    return SPEC;
  }

  @Override
  public Schema outputSchema() {
    return OUTPUT;
  }

  @Override
  public SumState newState() {
    return new SumState();
  }

  @Override
  public void update(Map<Long, SumState> states, long[] groupIds, VectorSchemaRoot input) {
    BigIntVector values = (BigIntVector) input.getFieldVectors().get(0);
    for (int i = 0; i < input.getRowCount(); i++) {
      if (!values.isNull(i)) {
        SumState s = states.computeIfAbsent(groupIds[i], k -> new SumState());
        s.total = Math.addExact(s.total, values.get(i));
      }
    }
  }

  @Override
  public void combine(SumState target, SumState source) {
    target.total = Math.addExact(target.total, source.total);
  }

  @Override
  public void finalize(FieldVector result, int rowIndex, SumState state) {
    ((BigIntVector) result).setSafe(rowIndex, state.total);
  }

  static Worker worker() {
    return Worker.builder().catalogName("aggregate").registerAggregate(new Aggregate());
  }
}
