package farm.query.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.ParameterExtractor;
import farm.query.vgirpc.OutputCollector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.Test;

class SequenceTableTest {

  /** Drive the producer the way the framework does: one tick per batch until finish(). */
  static List<long[]> run(long count, long step) {
    SequenceTable.State state = new SequenceTable.State(count, step, SequenceTable.OUTPUT_SCHEMA);
    List<long[]> rows = new ArrayList<>();
    for (int ticks = 0; ticks < 1000; ticks++) {
      OutputCollector out = new OutputCollector(SequenceTable.OUTPUT_SCHEMA, "test", true);
      state.produceTick(out, null);
      if (out.finished()) {
        return rows;
      }
      try (VectorSchemaRoot root = out.dataEntry().root()) {
        BigIntVector n = (BigIntVector) root.getVector("n");
        BigIntVector sq = (BigIntVector) root.getVector("squared");
        assertEquals(true, root.getRowCount() <= SequenceTable.BATCH_ROWS);
        for (int i = 0; i < root.getRowCount(); i++) {
          rows.add(new long[] {n.get(i), sq.get(i)});
        }
      }
    }
    throw new AssertionError("producer never finished");
  }

  @Test
  void defaultStep() {
    List<long[]> rows = run(4, 1);
    assertEquals(4, rows.size());
    assertEquals(3, rows.get(3)[0]);
    assertEquals(9, rows.get(3)[1]);
  }

  @Test
  void namedStep() {
    List<long[]> rows = run(3, 5);
    assertEquals(10, rows.get(2)[0]);
  }

  @Test
  void streamsInBatches() {
    List<long[]> rows = run(25_000, 1);
    assertEquals(25_000, rows.size());
    assertEquals(24_999, rows.get(24_999)[0]);
  }

  @Test
  void rejectsOutOfRangeArguments() {
    Arguments badStep = new Arguments(List.of(3L), Map.of("step", 0L));
    assertThrows(
        IllegalArgumentException.class, () -> SequenceTable.args(ParameterExtractor.of(badStep)));
    Arguments badCount = new Arguments(List.of(-1L), Map.of());
    assertThrows(
        IllegalArgumentException.class, () -> SequenceTable.args(ParameterExtractor.of(badCount)));
  }
}
