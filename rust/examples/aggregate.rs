//! Recipe: an aggregate function, driven by GROUP BY.
//!
//! ```sql
//! SELECT category, aggregate.main.int_sum(amount) FROM sales GROUP BY category;
//! ```
//!
//! Aggregates keep per-group state across phases:
//!
//! - `update`   fold a batch of values into each group's state
//! - `combine`  merge partial states for the same group (parallel execution)
//! - `finalize` turn each group's state into one output value
//!
//! State is opaque bytes because partial states can be combined in a
//! different process; encoding it is the function's job.

use std::collections::HashMap;
use std::sync::Arc;

use arrow_array::cast::AsArray;
use arrow_array::types::Int64Type;
use arrow_array::{Array, ArrayRef, Int64Array, RecordBatch};
use arrow_schema::{DataType, Field, Schema, SchemaRef};
use vgi::aggregate::{AggregateBindParams, AggregateFunction};
use vgi::{ArgSpec, BindResponse, FunctionMetadata, Result, RpcError};

fn encode(total: i64) -> Vec<u8> {
    total.to_le_bytes().to_vec()
}

fn decode(bytes: &[u8]) -> Result<i64> {
    bytes
        .try_into()
        .map(i64::from_le_bytes)
        .map_err(|_| RpcError::runtime_error("corrupt int_sum state"))
}

struct IntSum;

impl AggregateFunction for IntSum {
    fn name(&self) -> &str {
        "int_sum"
    }

    fn metadata(&self) -> FunctionMetadata {
        FunctionMetadata {
            description: "Sum integer values per group".into(),
            // DEFAULT: DuckDB skips NULL inputs, so update never sees one.
            null_handling: Some("DEFAULT".into()),
            ..Default::default()
        }
    }

    fn argument_specs(&self) -> Vec<ArgSpec> {
        vec![ArgSpec::column("value", 0, "int64", "Values to add up")]
    }

    fn on_bind(&self, _params: &AggregateBindParams) -> Result<BindResponse> {
        Ok(BindResponse {
            output_schema: Arc::new(Schema::new(vec![Field::new(
                "result",
                DataType::Int64,
                true,
            )])),
            opaque_data: Vec::new(),
        })
    }

    /// A fresh accumulator for each group.
    fn initial_state(&self) -> Vec<u8> {
        encode(0)
    }

    /// Add a batch of values to each group's total.
    fn update(
        &self,
        states: &mut HashMap<i64, Vec<u8>>,
        group_ids: &Int64Array,
        columns: &[ArrayRef],
    ) -> Result<()> {
        let values = columns
            .first()
            .ok_or_else(|| RpcError::value_error("int_sum: missing value column"))?
            .as_primitive::<Int64Type>();
        for i in 0..group_ids.len() {
            if values.is_null(i) {
                continue;
            }
            let gid = group_ids.value(i);
            let total = states
                .get(&gid)
                .map(|s| decode(s))
                .transpose()?
                .unwrap_or(0);
            states.insert(gid, encode(total + values.value(i)));
        }
        Ok(())
    }

    /// Merge two partial totals (must be associative and commutative).
    fn combine(&self, target: Vec<u8>, source: Vec<u8>) -> Result<Vec<u8>> {
        Ok(encode(decode(&target)? + decode(&source)?))
    }

    /// One row per group id; `None` (no non-NULL input) is SQL NULL.
    fn finalize(
        &self,
        output_schema: &SchemaRef,
        _group_ids: &Int64Array,
        states: &[Option<Vec<u8>>],
    ) -> Result<RecordBatch> {
        let totals = states
            .iter()
            .map(|s| s.as_deref().map(decode).transpose())
            .collect::<Result<Int64Array>>()?;
        RecordBatch::try_new(output_schema.clone(), vec![Arc::new(totals) as ArrayRef])
            .map_err(super::arrow_err)
    }
}

/// The `aggregate` worker.
pub fn worker() -> vgi::Worker {
    let mut worker = vgi::Worker::new();
    worker.register_aggregate(IntSum);
    worker.set_catalog(super::catalog("aggregate"));
    worker
}
