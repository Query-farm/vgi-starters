package farm.query.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import farm.query.vgirpc.wire.Allocators;
import java.nio.charset.StandardCharsets;
import org.apache.arrow.vector.VarCharVector;
import org.junit.jupiter.api.Test;

class GreetTest {

  @Test
  void greetingTones() {
    assertEquals("Hi, Ada!", Greet.greeting("Ada", "casual"));
    assertEquals("Good day, Ada.", Greet.greeting("Ada", "formal"));
  }

  @Test
  void greetsAWholeColumnAndKeepsNulls() {
    try (VarCharVector names = new VarCharVector("name", Allocators.root());
        VarCharVector out = new VarCharVector("result", Allocators.root())) {
      names.allocateNew(3);
      names.setSafe(0, "Ada".getBytes(StandardCharsets.UTF_8));
      names.setNull(1);
      names.setSafe(2, "Grace".getBytes(StandardCharsets.UTF_8));
      names.setValueCount(3);

      Greet.greet(names, "formal", out);

      assertEquals(3, out.getValueCount());
      assertEquals("Good day, Ada.", out.getObject(0).toString());
      assertTrue(out.isNull(1));
      assertEquals("Good day, Grace.", out.getObject(2).toString());
    }
  }
}
