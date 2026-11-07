//! Recipe: transform the rows of a query with a table in-out function.
//!
//! A table in-out function receives another query's rows, batch by batch, and
//! emits rows back. Use it to clean, enrich, filter or reshape data in SQL:
//!
//! ```sql
//! SELECT * FROM transform_rows.main.positive_only((SELECT * FROM readings));
//! ```
//!
//! Here: keep only rows whose `value` column is greater than zero. The output
//! schema equals the input schema, so it works on any table that has a
//! `value` column.

use arrow_array::cast::AsArray;
use arrow_array::types::Int64Type;
use arrow_array::{Array, BooleanArray, RecordBatch};
use arrow_schema::DataType;
use vgi::table_in_out::TableInOutFunction;
use vgi::{ArgSpec, BindParams, BindResponse, FunctionMetadata, ProcessParams, Result, RpcError};

struct PositiveOnly;

impl TableInOutFunction for PositiveOnly {
    fn name(&self) -> &str {
        "positive_only"
    }

    fn metadata(&self) -> FunctionMetadata {
        FunctionMetadata {
            description: "Keep the rows of a query whose value column is positive".into(),
            ..Default::default()
        }
    }

    /// The TABLE argument is declared with the type string `"table"`.
    fn argument_specs(&self) -> Vec<ArgSpec> {
        vec![ArgSpec::column(
            "data",
            0,
            "table",
            "Rows to filter; must have a numeric `value` column.",
        )]
    }

    /// Output columns are the input columns; fail early without a `value` column.
    fn on_bind(&self, params: &BindParams) -> Result<BindResponse> {
        let schema = params
            .input_schema
            .clone()
            .filter(|s| s.column_with_name("value").is_some())
            .ok_or_else(|| {
                RpcError::value_error("positive_only needs an input column named 'value'")
            })?;
        Ok(BindResponse {
            output_schema: schema,
            opaque_data: Vec::new(),
        })
    }

    /// Filter each incoming batch and emit the survivors (NULL values are dropped).
    fn process(&self, _params: &ProcessParams, batch: &RecordBatch) -> Result<Vec<RecordBatch>> {
        let value = batch
            .column_by_name("value")
            .ok_or_else(|| RpcError::value_error("expected a `value` column"))?;
        let value = arrow_cast::cast(value, &DataType::Int64)
            .map_err(|e| RpcError::value_error(format!("`value` must be an integer: {e}")))?;
        let value = value.as_primitive::<Int64Type>();
        let keep: BooleanArray = (0..value.len())
            .map(|i| Some(value.is_valid(i) && value.value(i) > 0))
            .collect();
        let kept =
            arrow_select::filter::filter_record_batch(batch, &keep).map_err(super::arrow_err)?;
        Ok(if kept.num_rows() == 0 {
            Vec::new()
        } else {
            vec![kept]
        })
    }
}

/// The `transform_rows` worker.
pub fn worker() -> vgi::Worker {
    let mut worker = vgi::Worker::new();
    worker.register_table_in_out(PositiveOnly);
    worker.set_catalog(super::catalog("transform_rows"));
    worker
}
