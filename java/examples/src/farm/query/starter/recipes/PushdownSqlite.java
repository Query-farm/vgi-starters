package farm.query.starter.recipes;

import farm.query.vgi.Worker;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.function.FunctionSpec;
import farm.query.vgi.pushdown.ComparisonOperator;
import farm.query.vgi.pushdown.FilterApplier;
import farm.query.vgi.pushdown.PushdownFilter;
import farm.query.vgi.table.SimpleTableFunction;
import farm.query.vgi.table.TableInitParams;
import farm.query.vgi.table.TableProducerState;
import farm.query.vgi.types.Schemas;
import farm.query.vgirpc.CallContext;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Recipe: push WHERE filters and column selection down into a database.
 *
 * <p>DuckDB tells a table function which columns the query needs (projection pushdown) and which
 * simple predicates it can apply at the source (filter pushdown). Turning those into the source's
 * own query means less data crosses the wire.
 *
 * <pre>
 *   SELECT id, amount FROM pushdown_sqlite.main.orders() WHERE region = 'west' AND amount > 900;
 * </pre>
 *
 * <p>DuckDB removes every predicate it pushes down from its own plan and does not re-check the
 * rows, so the worker must apply each one exactly. Here only simple top-level comparisons, IN lists
 * and IS [NOT] NULL are translated into SQL, which narrows what SQLite returns; every batch then
 * goes through the SDK's {@link FilterApplier}, which applies all the pushed predicates exactly, so
 * the ones not translated (NOT IN, for example) still hold. Whatever you translate must never drop
 * a matching row. The demo database is created on first use in the system temp directory, through
 * the sqlite-jdbc driver.
 */
public final class PushdownSqlite extends SimpleTableFunction {

  private static final Logger LOG = Logger.getLogger(PushdownSqlite.class.getName());
  static final Path DB_PATH =
      Path.of(System.getProperty("java.io.tmpdir"), "vgi_recipe_orders_java.sqlite");
  static final int PAGE = 500;
  private static final Set<String> COLUMNS = Set.of("id", "region", "amount");

  private static final Schema SCHEMA =
      new Schema(
          List.of(
              Schemas.nonNull("id", Schemas.INT64),
              Schemas.nullable("region", Schemas.UTF8),
              Schemas.nullable("amount", Schemas.INT64)));

  private static final FunctionSpec SPEC =
      new FunctionSpec(
          "orders",
          FunctionMetadata.describe(
                  "Orders from a SQLite database, with filter and projection pushdown")
              // withPushdown(projection, filter, autoApply)
              .withPushdown(true, true, false),
          List.of());

  @Override
  public FunctionSpec spec() {
    return SPEC;
  }

  @Override
  protected Schema outputSchema() {
    return SCHEMA;
  }

  /** Create a deterministic 2,000-row orders table if it does not exist. */
  static synchronized void ensureDb() throws SQLException {
    if (Files.exists(DB_PATH)) {
      return;
    }
    Path tmp = DB_PATH.resolveSibling(DB_PATH.getFileName() + "." + ProcessHandle.current().pid());
    try (Connection con = DriverManager.getConnection("jdbc:sqlite:" + tmp)) {
      con.setAutoCommit(false);
      try (Statement st = con.createStatement()) {
        st.execute(
            "CREATE TABLE orders (id INTEGER PRIMARY KEY, region TEXT NOT NULL,"
                + " amount INTEGER NOT NULL)");
      }
      String[] regions = {"north", "south", "east", "west"};
      try (PreparedStatement ps = con.prepareStatement("INSERT INTO orders VALUES (?, ?, ?)")) {
        for (int i = 1; i <= 2000; i++) {
          ps.setInt(1, i);
          ps.setString(2, regions[i % 4]);
          ps.setInt(3, (i * 37) % 1000);
          ps.addBatch();
        }
        ps.executeBatch();
      }
      con.commit();
    }
    try {
      Files.move(tmp, DB_PATH, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.io.IOException e) {
      throw new SQLException("cannot publish " + DB_PATH, e);
    }
  }

  /** A WHERE fragment and its parameters. */
  record Where(List<String> clauses, List<Object> values) {}

  /** Translate the filters SQLite can evaluate into parameterized SQL. */
  static Where where(List<PushdownFilter> filters) {
    List<String> clauses = new ArrayList<>();
    List<Object> values = new ArrayList<>();
    for (PushdownFilter f : filters) {
      if (!COLUMNS.contains(f.columnName())) {
        continue;
      }
      String col = '"' + f.columnName() + '"';
      switch (f) {
        case PushdownFilter.Constant c
            when c.op() != ComparisonOperator.DISTINCT_FROM
                && c.op() != ComparisonOperator.NOT_DISTINCT_FROM -> {
          clauses.add(col + " " + c.op().symbol() + " ?");
          values.add(c.value());
        }
        case PushdownFilter.In in when !in.values().isEmpty() -> {
          clauses.add(
              col + " IN (" + String.join(", ", in.values().stream().map(v -> "?").toList()) + ")");
          values.addAll(in.values());
        }
        case PushdownFilter.IsNull n -> clauses.add(col + " IS NULL");
        case PushdownFilter.IsNotNull n -> clauses.add(col + " IS NOT NULL");
        default -> {
          // Anything else: not translated; produceTick's FilterApplier applies it.
        }
      }
    }
    return new Where(clauses, values);
  }

  @Override
  public TableProducerState createProducer(TableInitParams params) {
    try {
      ensureDb();
    } catch (SQLException e) {
      throw new IllegalStateException("cannot create " + DB_PATH, e);
    }
    Where w = where(params.decodeFilters().filters());
    return new Cursor(
        params.outputSchema(), String.join(" AND ", w.clauses()), w.values(), params.filters());
  }

  /** Keyset pagination cursor: the last id emitted, plus the translated query. */
  public static final class Cursor extends TableProducerState {
    public long lastId;
    public Schema schema;
    public String where;
    public List<Object> values;
    // DuckDB drops every pushed predicate from its plan: apply them all to each batch.
    public FilterApplier filters;

    /** Required no-arg constructor: the HTTP transport rebuilds state from its fields. */
    public Cursor() {}

    Cursor(Schema schema, String where, List<Object> values, FilterApplier filters) {
      this.schema = schema;
      this.where = where;
      this.values = values;
      this.filters = filters;
    }

    @Override
    public void produceTick(OutputCollector out, CallContext ctx) {
      List<String> wanted = schema.getFields().stream().map(Field::getName).toList();
      StringBuilder sql = new StringBuilder("SELECT id");
      for (String c : wanted) {
        sql.append(", \"").append(c).append('"');
      }
      sql.append(" FROM orders WHERE id > ?");
      if (!where.isEmpty()) {
        sql.append(" AND ").append(where);
      }
      sql.append(" ORDER BY id LIMIT ").append(PAGE);
      LOG.fine(() -> "sqlite: " + sql + " " + values);

      VectorSchemaRoot root = VectorSchemaRoot.create(schema, Allocators.root());
      root.allocateNew();
      int rows = 0;
      try (Connection con =
              DriverManager.getConnection("jdbc:sqlite:file:" + DB_PATH + "?mode=ro");
          PreparedStatement ps = con.prepareStatement(sql.toString())) {
        ps.setLong(1, lastId);
        for (int i = 0; i < values.size(); i++) {
          ps.setObject(i + 2, values.get(i));
        }
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            lastId = rs.getLong(1);
            for (int c = 0; c < wanted.size(); c++) {
              FieldVector v = root.getVector(c);
              Object value = rs.getObject(c + 2);
              if (value == null) {
                v.setNull(rows);
              } else if (v instanceof BigIntVector b) {
                b.setSafe(rows, ((Number) value).longValue());
              } else if (v instanceof VarCharVector s) {
                s.setSafe(rows, value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
              }
            }
            rows++;
          }
        }
      } catch (SQLException e) {
        root.close();
        throw new IllegalStateException("sqlite query failed: " + e.getMessage(), e);
      }
      if (rows == 0) {
        root.close();
        out.finish();
        return;
      }
      root.setRowCount(rows);
      out.emit(filters.apply(root));
    }
  }

  static Worker worker() {
    return Worker.builder().catalogName("pushdown_sqlite").registerTable(new PushdownSqlite());
  }
}
