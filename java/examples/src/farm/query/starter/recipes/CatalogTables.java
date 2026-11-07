package farm.query.starter.recipes;

import farm.query.vgi.Worker;
import farm.query.vgi.catalog.CatalogTable;
import farm.query.vgi.catalog.ConstraintRecord;
import farm.query.vgi.catalog.ConstraintRecord.Enforcement;
import farm.query.vgi.catalog.ConstraintRecord.Validation;
import farm.query.vgi.function.FunctionMetadata;
import farm.query.vgi.function.FunctionSpec;
import farm.query.vgi.internal.SchemaUtil;
import farm.query.vgi.table.SimpleTableFunction;
import farm.query.vgi.table.TableInitParams;
import farm.query.vgi.table.TableProducerState;
import farm.query.vgi.types.Schemas;
import farm.query.vgirpc.CallContext;
import farm.query.vgirpc.OutputCollector;
import farm.query.vgirpc.wire.Allocators;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Recipe: expose a dataset as a named table, with a primary key.
 *
 * <p>A catalog table is queried by name, with no parentheses, like any DuckDB table:
 *
 * <pre>
 *   SELECT * FROM catalog_tables.main.countries WHERE continent = 'Europe';
 * </pre>
 *
 * <p>Use a table for a fixed or slowly changing dataset (reference data, a directory, a lookup
 * list). Use a table function when the caller supplies arguments. Declaring NOT NULL and a primary
 * key tells the optimizer, and agents, what identifies a row.
 *
 * <p>Constraints are the worker's own attestation: declare a record as ENFORCED with ALL_ROWS
 * validation only if every row the table can return satisfies it.
 */
public final class CatalogTables extends SimpleTableFunction {

  static final String[][] COUNTRIES = {
    {"DE", "Germany", "Europe"},
    {"FR", "France", "Europe"},
    {"JP", "Japan", "Asia"},
    {"KE", "Kenya", "Africa"},
    {"US", "United States", "North America"},
  };

  static final Schema SCHEMA =
      new Schema(
          List.of(
              column("code", false, "ISO 3166-1 alpha-2 country code."),
              column("name", true, "English short name."),
              column("continent", true, "Continent the country is in.")));

  private static Field column(String name, boolean nullable, String comment) {
    return new Field(
        name, new FieldType(nullable, Schemas.UTF8, null, Map.of("comment", comment)), null);
  }

  private static final FunctionSpec SPEC =
      new FunctionSpec(
          "countries_source",
          FunctionMetadata.describe("Backing rows for the countries table"),
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
    return new Rows(params.outputSchema());
  }

  /** Emits every country once. */
  public static final class Rows extends TableProducerState {
    public boolean done;
    public Schema schema;

    /** Required no-arg constructor for state deserialization. */
    public Rows() {}

    Rows(Schema schema) {
      this.schema = schema;
    }

    @Override
    public void produceTick(OutputCollector out, CallContext ctx) {
      if (done) {
        out.finish();
        return;
      }
      done = true;
      VectorSchemaRoot root = VectorSchemaRoot.create(schema, Allocators.root());
      root.allocateNew();
      List<String> all = SCHEMA.getFields().stream().map(Field::getName).toList();
      for (Field f : schema.getFields()) {
        int col = all.indexOf(f.getName());
        VarCharVector v = (VarCharVector) root.getVector(f.getName());
        for (int r = 0; r < COUNTRIES.length; r++) {
          v.setSafe(r, COUNTRIES[r][col].getBytes(StandardCharsets.UTF_8));
        }
      }
      root.setRowCount(COUNTRIES.length);
      out.emit(root);
    }
  }

  static final CatalogTable COUNTRIES_TABLE =
      CatalogTable.builder("main", "countries", SchemaUtil.serializeSchema(SCHEMA))
          .comment("A small reference list of countries.")
          .scanFunction("countries_source")
          .cardinality(COUNTRIES.length, COUNTRIES.length)
          .constraints(
              List.of(
                  ConstraintRecord.builder("main", "countries", "code_not_null")
                      .notNull("code")
                      .enforcement(Enforcement.ENFORCED)
                      .validation(Validation.ALL_ROWS)
                      .build(),
                  ConstraintRecord.builder("main", "countries", "pk")
                      .primaryKey("code")
                      .enforcement(Enforcement.ENFORCED)
                      .validation(Validation.ALL_ROWS)
                      .build()),
              "countries-v1".getBytes(StandardCharsets.UTF_8))
          .build();

  static Worker worker() {
    return Worker.builder()
        .catalogName("catalog_tables")
        .registerTable(new CatalogTables())
        .registerCatalogTable(COUNTRIES_TABLE);
  }
}
