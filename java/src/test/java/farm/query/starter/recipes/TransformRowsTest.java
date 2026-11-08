package farm.query.starter.recipes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import farm.query.vgi.InvalidArgumentException;
import farm.query.vgi.function.Arguments;
import farm.query.vgi.tableinout.TableInOutBindParams;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;

class TransformRowsTest {

  static TableInOutBindParams bind(String... columns) {
    List<Field> fields =
        java.util.Arrays.stream(columns)
            .map(c -> Field.nullable(c, new ArrowType.Int(64, true)))
            .toList();
    return new TableInOutBindParams(
        "positive_only", new Arguments(List.of(), Map.of()), new Schema(fields), Map.of());
  }

  @Test
  void bindsAnInputWithAValueColumn() {
    new TransformRows().onBind(bind("id", "value"));
  }

  @Test
  void rejectsAnInputWithoutAValueColumnWithAFriendlyMessage() {
    InvalidArgumentException e =
        assertThrows(InvalidArgumentException.class, () -> new TransformRows().onBind(bind("id")));
    assertEquals("positive_only needs an input column named 'value'", e.getMessage());
  }
}
