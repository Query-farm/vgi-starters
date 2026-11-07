package farm.query.starter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import farm.query.vgi.SecretTypeSpec;
import farm.query.vgi.Secrets;
import farm.query.vgi.function.ArgSpec;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.function.FunctionSpec;
import farm.query.vgi.function.ParameterExtractor;
import farm.query.vgi.protocol.FunctionExample;
import farm.query.vgi.protocol.FunctionRequiredSecret;
import farm.query.vgi.table.SimpleTableFunction;
import farm.query.vgi.table.TableInitParams;
import farm.query.vgi.table.TableProducerState;
import farm.query.vgi.types.Schemas;
import farm.query.vgirpc.CallContext;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Table function example: {@code fetch_items}, wrapping a paginated REST API.
 *
 * <p>This is the pattern most workers start from: page through an HTTP API and stream each page to
 * DuckDB as one Arrow batch.
 *
 * <ul>
 *   <li><b>Streaming.</b> {@link State#produceTick} fetches one page per call and keeps the next
 *       page number in the state, so a 10,000-page API never sits in memory.
 *   <li><b>Politeness.</b> Requests time out, and 429/5xx responses are retried with backoff that
 *       honours {@code Retry-After}.
 *   <li><b>Credentials.</b> A bearer token comes from a DuckDB secret if one exists ({@code CREATE
 *       SECRET (TYPE starter_api, TOKEN '...')}), otherwise from the {@code STARTER_API_TOKEN}
 *       environment variable. It is resolved once per query and carried in the state, which the
 *       HTTP transport encrypts before it leaves the worker (see {@link State#token}).
 * </ul>
 *
 * <p>The tests run against {@code test/mock_api/server.py}; point {@code base_url} at your real API
 * once you adapt the parsing in {@link #parsePage}.
 */
public final class FetchItems extends SimpleTableFunction {

  static final String SECRET_TYPE = "starter_api";
  static final String TOKEN_ENV_VAR = "STARTER_API_TOKEN";
  static final Duration TIMEOUT = Duration.ofSeconds(30);
  static final int MAX_ATTEMPTS = 4;
  static final String USER_AGENT = "vgi-starter-worker/0.1";
  // The SDK full-matches argument patterns (other SDKs search), so the trailing `.*` keeps the
  // published pattern meaning "starts with http:// or https://" everywhere.
  static final Pattern URL_PATTERN = Pattern.compile("^https?://");

  /** Registered with DuckDB so users can run CREATE SECRET (TYPE starter_api, TOKEN '...'). */
  static final SecretTypeSpec API_SECRET_TYPE =
      new SecretTypeSpec(
          SECRET_TYPE,
          "Bearer token for the API that fetch_items reads.",
          new Schema(
              List.of(
                  new Field(
                      "token",
                      new FieldType(true, Schemas.UTF8, null, Map.of("redact", "true")),
                      null))));

  static final Schema OUTPUT_SCHEMA =
      new Schema(
          List.of(
              Meta.column("id", Schemas.INT64, "Item identifier assigned by the API.", false),
              Meta.column("name", Schemas.UTF8, "Item display name.", true),
              Meta.column("category", Schemas.UTF8, "Item category, e.g. 'hardware'.", true),
              Meta.column("price", Schemas.FLOAT64, "Item price in US dollars.", true)));

  private static final List<ArgSpec> ARGS =
      List.of(
          Meta.positional(
              "base_url",
              0,
              Schemas.UTF8,
              "Root URL of the API, e.g. 'https://vgi-starter-api.query-farm.services/v1'.",
              ArgSpec.Constraints.pattern(URL_PATTERN.pattern())),
          Meta.named(
              "page_size",
              Schemas.INT64,
              "100",
              "Items requested per page; 1 to 1000.",
              ArgSpec.Constraints.range(1, 1000, null, null)));

  static final List<FunctionExample> EXAMPLES =
      List.of(
          Meta.example(
              "SELECT id, name, price FROM starter.main.fetch_items('https://vgi-starter-api.query-farm.services/v1') "
                  + "WHERE category = 'hardware' ORDER BY price DESC LIMIT 5",
              "The five priciest hardware items from the public demo API"),
          Meta.example(
              "SELECT category, count(*) AS items, avg(price) AS avg_price "
                  + "FROM starter.main.fetch_items('https://vgi-starter-api.query-farm.services/v1', page_size := 10) "
                  + "GROUP BY category ORDER BY category",
              "Summarize the API's items by category, ten per request"));

  private static final FunctionSpec SPEC =
      new FunctionSpec(
          "fetch_items",
          FunctionMetadata.describe("Read every item from a paginated REST API, one page per batch")
              .withExamples(EXAMPLES)
              .withTags(
                  Meta.objectTags(
                      "Fetch Items From API",
                      "api",
                      "rest api, http, pagination, fetch, items, json api, bearer token",
                      "Read all items from a paginated JSON REST API at `base_url` and return them"
                          + " as rows `(id, name, category, price)`. Pages are requested"
                          + " `page_size` at a time (named argument, default 100) and streamed to"
                          + " DuckDB as they arrive. If a DuckDB secret of type `starter_api`"
                          + " exists its `token` is sent as a bearer token; otherwise the"
                          + " `STARTER_API_TOKEN` environment variable is used. Rate-limit (429)"
                          + " and server errors are retried with backoff.",
                      "## fetch_items(base_url, page_size := 100)\n\n"
                          + "Every item from a paginated REST API, streamed one page per batch.\n\n"
                          + "Authenticate with a `starter_api` secret or the `STARTER_API_TOKEN` "
                          + "environment variable:\n\n"
                          + "```sql\nCREATE SECRET (TYPE starter_api, TOKEN 'your-token');\n```"))
              .withTag(
                  "vgi.result_columns_schema",
                  Meta.json(
                      List.of(
                          SequenceTable.column(
                              "id", "BIGINT", "Item identifier assigned by the API."),
                          SequenceTable.column("name", "VARCHAR", "Item display name."),
                          SequenceTable.column(
                              "category", "VARCHAR", "Item category, e.g. 'hardware'."),
                          SequenceTable.column("price", "DOUBLE", "Item price in US dollars."))))
              .withTag("vgi.example_queries", Meta.exampleQueries(EXAMPLES)),
          ARGS);

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .connectTimeout(TIMEOUT)
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  @Override
  public FunctionSpec spec() {
    return SPEC;
  }

  @Override
  protected Schema outputSchema() {
    return OUTPUT_SCHEMA;
  }

  /** Ask DuckDB to resolve an optional {@code starter_api} secret before bind. */
  @Override
  public List<FunctionRequiredSecret> requiredSecrets() {
    return List.of(new FunctionRequiredSecret(SECRET_TYPE, null, null));
  }

  /** The validated arguments. */
  record Args(String baseUrl, long pageSize) {}

  static Args args(ParameterExtractor p) {
    String baseUrl = p.positional(0, "base_url").asString().required();
    if (!URL_PATTERN.matcher(baseUrl).find()) {
      throw new IllegalArgumentException(
          "base_url must match the required pattern ^https?:// (got '" + baseUrl + "')");
    }
    long pageSize = p.named("page_size").asLong().between(1, 1000).orElse(100L);
    return new Args(baseUrl, pageSize);
  }

  @Override
  public TableProducerState createProducer(TableInitParams params) {
    Args a = args(ParameterExtractor.of(params.arguments()));
    State state = new State(a.baseUrl(), a.pageSize(), params.outputSchema());
    state.token = token(params.secrets());
    return state;
  }

  /**
   * Return the bearer token: DuckDB secret first, then the environment.
   *
   * @param secretBytes the resolved secrets DuckDB sent with the call
   * @return the token, or {@code null} for none
   */
  static String token(byte[] secretBytes) {
    for (Map<String, String> secret : Secrets.parse(secretBytes).ofType(SECRET_TYPE)) {
      String value = secret.get("token");
      if (value != null && !value.isEmpty()) {
        return value;
      }
    }
    String env = System.getenv(TOKEN_ENV_VAR);
    return env == null || env.isEmpty() ? null : env;
  }

  /**
   * GET {@code url} and decode JSON, retrying 429 and 5xx with backoff.
   *
   * @param url the page URL
   * @param token bearer token or {@code null}
   * @return the decoded body
   */
  static JsonNode getJson(String url, String token) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .GET();
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    for (int attempt = 1; ; attempt++) {
      try {
        HttpResponse<byte[]> response =
            HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        int status = response.statusCode();
        if (status / 100 == 2) {
          return JSON.readTree(response.body());
        }
        boolean retryable = status == 429 || status >= 500;
        if (!retryable || attempt == MAX_ATTEMPTS) {
          String body = new String(response.body(), StandardCharsets.UTF_8);
          throw new ApiException(
              "GET " + url + " failed with HTTP " + status + ": " + truncate(body, 500));
        }
        String retryAfter = response.headers().firstValue("Retry-After").orElse("");
        long delayMs =
            retryAfter.matches("\\d+")
                ? Long.parseLong(retryAfter) * 1000L
                : (long) (500 * Math.pow(2, attempt));
        sleep(Math.min(delayMs, 30_000L));
      } catch (IOException e) {
        if (attempt == MAX_ATTEMPTS) {
          throw new ApiException("GET " + url + " failed: " + e, e);
        }
        sleep((long) (500 * Math.pow(2, attempt)));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ApiException("GET " + url + " interrupted", e);
      }
    }
  }

  private static String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException("interrupted while backing off", e);
    }
  }

  /**
   * Split one API response into rows and the next page number. Adapt this to your API.
   *
   * @param body the decoded JSON body
   * @return the page
   */
  static Page parsePage(JsonNode body) {
    List<JsonNode> items = new ArrayList<>();
    body.path("items").forEach(items::add);
    JsonNode next = body.path("next_page");
    return new Page(items, next.isIntegralNumber() ? next.asLong() : 0L);
  }

  /** One page of items; {@code nextPage} is 0 when there are no more. */
  record Page(List<JsonNode> items, long nextPage) {}

  /** The API returned an error we will not retry. */
  static final class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    ApiException(String message) {
      super(message);
    }

    ApiException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Cursor: the next page to request, or 0 once the API says there are no more. */
  public static final class State extends TableProducerState {
    public String baseUrl;
    public long pageSize;
    public long nextPage = 1;
    public Schema schema;

    /**
     * Bearer token for every page. Java table-function state has no access to the call's secrets
     * after {@code createProducer}, so the token rides in the state. Over HTTP the state travels in
     * a token that vgi-java seals with ChaCha20-Poly1305 (encrypted, not just signed), so DuckDB
     * and anything between see ciphertext only. Never log this field.
     */
    public String token;

    /** Required no-arg constructor: the HTTP transport rebuilds state from its fields. */
    public State() {}

    State(String baseUrl, long pageSize, Schema schema) {
      this.baseUrl = baseUrl;
      this.pageSize = pageSize;
      this.schema = schema;
    }

    String pageUrl() {
      String root = baseUrl.replaceAll("/+$", "");
      return root + "/items?page=" + nextPage + "&page_size=" + pageSize;
    }

    @Override
    public void produceTick(OutputCollector out, CallContext ctx) {
      if (nextPage == 0) {
        out.finish();
        return;
      }
      String bearer = token != null ? token : token(null);
      Page page = parsePage(getJson(pageUrl(), bearer));
      out.emit(toBatch(page.items(), schema));
      nextPage = page.nextPage();
    }
  }

  /**
   * Build one Arrow batch from JSON items, filling only the columns in {@code schema}.
   *
   * @param items JSON objects with id, name, category, price
   * @param schema the (possibly projected) output schema
   * @return the batch; the caller owns it
   */
  static VectorSchemaRoot toBatch(List<JsonNode> items, Schema schema) {
    VectorSchemaRoot root = VectorSchemaRoot.create(schema, Allocators.root());
    root.allocateNew();
    for (FieldVector vector : root.getFieldVectors()) {
      String name = vector.getName();
      for (int i = 0; i < items.size(); i++) {
        JsonNode value = items.get(i).path(name);
        if (value.isNull() || value.isMissingNode()) {
          vector.setNull(i);
        } else if (vector instanceof BigIntVector v) {
          v.setSafe(i, value.asLong());
        } else if (vector instanceof Float8Vector v) {
          v.setSafe(i, value.asDouble());
        } else if (vector instanceof VarCharVector v) {
          v.setSafe(i, value.asText().getBytes(StandardCharsets.UTF_8));
        }
      }
    }
    root.setRowCount(items.size());
    return root;
  }
}
