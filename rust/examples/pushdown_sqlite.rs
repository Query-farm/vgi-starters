//! Recipe: push WHERE filters and column selection down into a database.
//!
//! DuckDB tells a table function which columns the query needs (projection
//! pushdown) and which predicates it can apply at the source (filter
//! pushdown). Turning those into the source's own query means less data
//! crosses the wire.
//!
//! ```sql
//! SELECT id, amount FROM pushdown_sqlite.main.orders() WHERE region = 'west' AND amount > 900;
//! ```
//!
//! Only equality / IN lists and integer ranges are translated here. The
//! function declares `FilterHandling::Sdk`, so the SDK re-applies every pushed
//! filter exactly to the rows it emits: translating a filter only makes the
//! SQL return fewer rows, and a filter left out costs speed, never
//! correctness. The demo database is created on first use in the system temp
//! directory.

use std::path::PathBuf;
use std::sync::Arc;

use arrow_array::cast::AsArray;
use arrow_array::types::Float64Type;
use arrow_array::{new_null_array, Array, ArrayRef, Int64Array, RecordBatch, StringArray};
use arrow_schema::{DataType, Field, Schema, SchemaRef};
use rusqlite::types::Value;
use rusqlite::{params_from_iter, Connection, OpenFlags};
use vgi::pushdown::PushdownFilters;
use vgi::table_function::{TableFunction, TableProducer};
use vgi::vgi_rpc::OutputCollector;
use vgi::{
    ArgSpec, BindParams, BindResponse, FilterHandling, FunctionMetadata, ProcessParams, Result,
    RpcError,
};

const PAGE: i64 = 500;
const COLUMNS: [&str; 3] = ["id", "region", "amount"];

fn db_path() -> PathBuf {
    std::env::temp_dir().join("vgi_recipe_orders.sqlite")
}

fn sql_err(e: rusqlite::Error) -> RpcError {
    RpcError::runtime_error(format!("sqlite: {e}"))
}

/// Create a deterministic 2,000-row orders table if it does not exist.
fn ensure_db() -> Result<()> {
    let path = db_path();
    if path.exists() {
        return Ok(());
    }
    // Build under a unique name, then rename: concurrent workers never see a
    // half-written file.
    let tmp = path.with_extension(format!("tmp{}", std::process::id()));
    let _ = std::fs::remove_file(&tmp);
    let mut con = Connection::open(&tmp).map_err(sql_err)?;
    con.execute_batch("CREATE TABLE orders (id INTEGER PRIMARY KEY, region TEXT NOT NULL, amount INTEGER NOT NULL)")
        .map_err(sql_err)?;
    let tx = con.transaction().map_err(sql_err)?;
    {
        let mut insert = tx
            .prepare("INSERT INTO orders VALUES (?1, ?2, ?3)")
            .map_err(sql_err)?;
        let regions = ["north", "south", "east", "west"];
        for i in 1..=2000i64 {
            insert
                .execute((i, regions[(i % 4) as usize], (i * 37) % 1000))
                .map_err(sql_err)?;
        }
    }
    tx.commit().map_err(sql_err)?;
    drop(con);
    std::fs::rename(&tmp, &path).map_err(|e| RpcError::runtime_error(format!("rename db: {e}")))
}

fn orders_schema() -> SchemaRef {
    Arc::new(Schema::new(vec![
        Field::new("id", DataType::Int64, false),
        Field::new("region", DataType::Utf8, true),
        Field::new("amount", DataType::Int64, true),
    ]))
}

/// Translate the filters SQLite can evaluate into parameterized SQL clauses.
fn where_clauses(filters: Option<&PushdownFilters>) -> (Vec<String>, Vec<Value>) {
    let mut clauses = Vec::new();
    let mut values = Vec::new();
    let Some(filters) = filters else {
        return (clauses, values);
    };
    for column in COLUMNS {
        // `col = x` or `col IN (...)`.
        if let Some(set) = filters.get_column_values(column) {
            let items: Vec<Value> = match set.data_type() {
                DataType::Utf8 => set
                    .as_string::<i32>()
                    .iter()
                    .flatten()
                    .map(|s| Value::Text(s.into()))
                    .collect(),
                _ => filters
                    .get_column_values_i64(column)
                    .unwrap_or_default()
                    .into_iter()
                    .map(Value::Integer)
                    .collect(),
            };
            if !items.is_empty() {
                clauses.push(format!(
                    "\"{column}\" IN ({})",
                    vec!["?"; items.len()].join(", ")
                ));
                values.extend(items);
            }
        }
        // Ranges: `col > x`, `col <= y`, ... with the literal's own type.
        if column != "region" {
            for f in filters.get_column_constant_filters(column) {
                let sql_op = match f.op.as_str() {
                    "gt" => ">",
                    "ge" => ">=",
                    "lt" => "<",
                    "le" => "<=",
                    _ => continue,
                };
                let Ok(as_f64) = arrow_cast::cast(&f.value, &DataType::Float64) else {
                    continue;
                };
                let v = as_f64.as_primitive::<Float64Type>();
                if v.is_null(0) {
                    continue;
                }
                clauses.push(format!("\"{column}\" {sql_op} ?"));
                values.push(Value::Real(v.value(0)));
            }
        }
    }
    (clauses, values)
}

/// Keyset pagination: only `last_id` advances.
struct OrdersProducer {
    /// Every column (the SDK filters, then narrows to the projection).
    schema: SchemaRef,
    /// Columns the query reads or filters on; the rest are not selected.
    wanted: Vec<bool>,
    clauses: Vec<String>,
    values: Vec<Value>,
    last_id: i64,
}

impl TableProducer for OrdersProducer {
    /// Fetch one page of matching rows, selecting only the needed columns.
    fn next_batch(&mut self, _out: &mut OutputCollector) -> Result<Option<RecordBatch>> {
        let selected: Vec<&str> = COLUMNS
            .iter()
            .zip(&self.wanted)
            .filter(|(c, w)| **w && **c != "id")
            .map(|(c, _)| *c)
            .collect();
        let mut clauses = vec!["id > ?".to_string()];
        clauses.extend(self.clauses.iter().cloned());
        let sql = format!(
            "SELECT {} FROM orders WHERE {} ORDER BY id LIMIT {PAGE}",
            std::iter::once(&"id")
                .chain(&selected)
                .map(|c| format!("\"{c}\""))
                .collect::<Vec<_>>()
                .join(", "),
            clauses.join(" AND ")
        );
        let mut values = vec![Value::Integer(self.last_id)];
        values.extend(self.values.iter().cloned());
        log::debug!("sqlite: {sql} {values:?}");

        let con = Connection::open_with_flags(db_path(), OpenFlags::SQLITE_OPEN_READ_ONLY)
            .map_err(sql_err)?;
        let mut stmt = con.prepare(&sql).map_err(sql_err)?;
        let rows: Vec<Vec<Value>> = stmt
            .query_map(params_from_iter(values), |row| {
                (0..=selected.len())
                    .map(|i| row.get::<_, Value>(i))
                    .collect()
            })
            .map_err(sql_err)?
            .collect::<std::result::Result<_, _>>()
            .map_err(sql_err)?;
        let Some(last) = rows.last() else {
            return Ok(None);
        };
        if let Value::Integer(id) = last[0] {
            self.last_id = id;
        }

        let columns: Vec<ArrayRef> = self
            .schema
            .fields()
            .iter()
            // Row value 0 is always `id` (the pagination key); selected columns follow.
            .map(|field| {
                let index = if field.name() == "id" {
                    Some(0)
                } else {
                    selected
                        .iter()
                        .position(|c| c == field.name())
                        .map(|i| i + 1)
                };
                match index {
                    None => new_null_array(field.data_type(), rows.len()),
                    Some(i) if field.data_type() == &DataType::Utf8 => Arc::new(
                        rows.iter()
                            .map(|r| match &r[i] {
                                Value::Text(s) => Some(s.clone()),
                                _ => None,
                            })
                            .collect::<StringArray>(),
                    )
                        as ArrayRef,
                    Some(i) => Arc::new(
                        rows.iter()
                            .map(|r| match r[i] {
                                Value::Integer(v) => Some(v),
                                _ => None,
                            })
                            .collect::<Int64Array>(),
                    ) as ArrayRef,
                }
            })
            .collect();
        RecordBatch::try_new(self.schema.clone(), columns)
            .map(Some)
            .map_err(super::arrow_err)
    }

    vgi::resume_fields!(last_id);
}

/// Scan the SQLite `orders` table, pushing filters and projections into SQL.
struct Orders;

impl TableFunction for Orders {
    fn name(&self) -> &str {
        "orders"
    }

    /// Opt in to both kinds of pushdown.
    fn metadata(&self) -> FunctionMetadata {
        FunctionMetadata {
            description: "Orders from a SQLite database, with filter and projection pushdown"
                .into(),
            projection_pushdown: true,
            ..Default::default()
        }
        .with_filter_handling(FilterHandling::Sdk)
    }

    fn argument_specs(&self) -> Vec<ArgSpec> {
        Vec::new()
    }

    fn on_bind(&self, _params: &BindParams) -> Result<BindResponse> {
        Ok(BindResponse {
            output_schema: orders_schema(),
            opaque_data: Vec::new(),
        })
    }

    fn producer(&self, params: &ProcessParams) -> Result<Box<dyn TableProducer>> {
        ensure_db()?;
        let filters = params.current_pushdown_filters.as_ref();
        let filtered = filters.map(|f| f.filtered_columns()).unwrap_or_default();
        let wanted = COLUMNS
            .iter()
            .map(|c| params.output_schema.column_with_name(c).is_some() || filtered.contains(*c))
            .collect();
        let (clauses, values) = where_clauses(filters);
        Ok(Box::new(OrdersProducer {
            schema: params.bind_output_schema.clone(),
            wanted,
            clauses,
            values,
            last_id: 0,
        }))
    }
}

/// The `pushdown_sqlite` worker.
pub fn worker() -> vgi::Worker {
    let mut worker = vgi::Worker::new();
    worker.register_table(Orders);
    worker.set_catalog(super::catalog("pushdown_sqlite"));
    worker
}
