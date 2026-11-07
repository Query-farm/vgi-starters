package farm.query.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import farm.query.vgi.function.Arguments;
import farm.query.vgi.function.ParameterExtractor;
import farm.query.vgirpc.OutputCollector;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** fetch_items against a tiny in-process API: 3 pages of 2 items, a 429 first, a 401 route. */
class FetchItemsTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger rateLimited = new AtomicInteger();

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/items",
        exchange -> {
          String query = exchange.getRequestURI().getQuery();
          int page = Integer.parseInt(query.replaceAll(".*page=(\\d+).*", "$1"));
          if (rateLimited.getAndIncrement() == 0) {
            exchange.getResponseHeaders().add("Retry-After", "0");
            reply(exchange, 429, "{}");
            return;
          }
          String next = page < 3 ? String.valueOf(page + 1) : "null";
          int a = page * 2 - 1;
          int b = page * 2;
          reply(
              exchange,
              200,
              "{\"items\":[" + item(a) + "," + item(b) + "],\"next_page\":" + next + "}");
        });
    server.createContext(
        "/locked/items", exchange -> reply(exchange, 401, "{\"error\":\"no token\"}"));
    server.start();
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static String item(int id) {
    return "{\"id\":"
        + id
        + ",\"name\":\"item-"
        + id
        + "\",\"category\":\"hardware\",\"price\":1.5}";
  }

  private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
      throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  @Test
  void pagesThroughTheApiAndRetries429() {
    FetchItems.State state = new FetchItems.State(baseUrl + "/", 2, FetchItems.OUTPUT_SCHEMA);
    long total = 0;
    int batches = 0;
    while (true) {
      OutputCollector out = new OutputCollector(FetchItems.OUTPUT_SCHEMA, "test", true);
      state.produceTick(out, null);
      if (out.finished()) {
        break;
      }
      batches++;
      try (VectorSchemaRoot root = out.dataEntry().root()) {
        BigIntVector id = (BigIntVector) root.getVector("id");
        for (int i = 0; i < root.getRowCount(); i++) {
          total += id.get(i);
        }
      }
    }
    assertEquals(3, batches);
    assertEquals(21, total); // ids 1..6
    assertEquals(4, rateLimited.get(), "one 429, retried, then three pages");
  }

  @Test
  void errorsIncludeTheHttpStatus() {
    FetchItems.ApiException e =
        assertThrows(
            FetchItems.ApiException.class,
            () -> FetchItems.getJson(baseUrl + "/locked/items", null));
    assertTrue(e.getMessage().contains("HTTP 401"), e.getMessage());
  }

  @Test
  void validatesArguments() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FetchItems.args(
                ParameterExtractor.of(new Arguments(List.of("ftp://example.com"), Map.of()))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            FetchItems.args(
                ParameterExtractor.of(
                    new Arguments(List.of(baseUrl), Map.of("page_size", 1001L)))));
    assertEquals(
        100,
        FetchItems.args(ParameterExtractor.of(new Arguments(List.of(baseUrl), Map.of())))
            .pageSize());
  }
}
