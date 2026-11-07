//! Table function example: `sequence_table`.
//!
//! A table function returns a set of rows. This one is a deterministic
//! generator, the simplest shape to learn from:
//!
//! * `count` is positional and required; `step` is *named* with a default, so
//!   callers write `sequence_table(5)` or `sequence_table(5, step := 2)`.
//!   (Named arguments work on table functions, not on scalars.)
//! * `ge`/`le` constraints are published so agents know the valid range, and
//!   the SDK enforces them (positional and named) before `on_bind` runs.
//! * Output streams in bounded batches. [`SequenceProducer`] remembers where
//!   the last batch stopped, and `vgi::resume_fields!` makes that position
//!   serializable, so the same code works when DuckDB talks to the worker over
//!   HTTP, where each batch may hit a different process.

use std::sync::Arc;

use arrow_array::{ArrayRef, Int64Array, RecordBatch};
use arrow_schema::{DataType, Schema, SchemaRef};
use serde_json::json;
use vgi::table_function::{TableCardinality, TableFunction, TableProducer};
use vgi::vgi_rpc::OutputCollector;
use vgi::{ArgSpec, BindParams, BindResponse, FunctionMetadata, ProcessParams, Result, RpcError};

use crate::meta::{column, example, example_queries_json, object_tags, result_columns_schema};

/// Rows per emitted batch.
pub const BATCH_ROWS: i64 = 10_000;
/// The largest `count` accepted.
pub const MAX_COUNT: i64 = 1_000_000;

/// The output columns.
pub fn schema() -> SchemaRef {
    Arc::new(Schema::new(vec![
        column(
            "n",
            DataType::Int64,
            "The generated value: row index times step.",
            false,
        ),
        column("squared", DataType::Int64, "n multiplied by itself.", false),
    ]))
}

/// Read `(count, step)`. The SDK has already enforced their declared ranges.
fn args(arguments: &vgi::arguments::Arguments) -> Result<(i64, i64)> {
    let count = arguments.const_i64(0).unwrap_or(0);
    let step = arguments.named_i64("step").unwrap_or(1);
    Ok((count, step))
}

/// The scan cursor. Only `emitted` advances; everything else is rebuilt from
/// the call's arguments when an HTTP request resumes the scan.
pub struct SequenceProducer {
    schema: SchemaRef,
    count: i64,
    step: i64,
    emitted: i64,
}

impl SequenceProducer {
    /// A producer positioned at row zero.
    pub fn new(schema: SchemaRef, count: i64, step: i64) -> Self {
        Self {
            schema,
            count,
            step,
            emitted: 0,
        }
    }

    /// The next bounded batch, or `None` once `count` rows are out.
    pub fn next_rows(&mut self) -> Result<Option<RecordBatch>> {
        if self.emitted >= self.count {
            return Ok(None);
        }
        let rows = BATCH_ROWS.min(self.count - self.emitted);
        let n: Vec<i64> = (self.emitted..self.emitted + rows)
            .map(|i| i * self.step)
            .collect();
        let squared: Vec<i64> = n.iter().map(|v| v * v).collect();
        self.emitted += rows;
        let columns: Vec<ArrayRef> = vec![
            Arc::new(Int64Array::from(n)),
            Arc::new(Int64Array::from(squared)),
        ];
        RecordBatch::try_new(self.schema.clone(), columns)
            .map(Some)
            .map_err(|e| RpcError::runtime_error(e.to_string()))
    }
}

impl TableProducer for SequenceProducer {
    fn next_batch(&mut self, _out: &mut OutputCollector) -> Result<Option<RecordBatch>> {
        self.next_rows()
    }

    // Serializes `emitted` into the HTTP continuation token (and declares
    // resume_supported = true). Without it, a multi-batch scan fails over HTTP.
    vgi::resume_fields!(emitted);
}

/// `sequence_table(count, step := 1)`.
pub struct SequenceTable;

fn examples() -> Vec<vgi::FunctionExample> {
    vec![
        example(
            "The first five values and their squares",
            "SELECT n, squared FROM starter.main.sequence_table(5) ORDER BY n",
        ),
        example(
            "Four values stepping by ten",
            "SELECT n FROM starter.main.sequence_table(4, step := 10) ORDER BY n",
        ),
    ]
}

impl TableFunction for SequenceTable {
    fn name(&self) -> &str {
        "sequence_table"
    }

    fn metadata(&self) -> FunctionMetadata {
        let mut tags = object_tags(
            "Sequence Table",
            "generators",
            "sequence, series, range, generate rows, numbers, squares",
            "Generate a deterministic table of `count` rows with columns `n` and `squared`, \
             where `n` runs `0, step, 2*step, ...`. `count` is positional (0 to 1,000,000); \
             `step` is a named argument that defaults to 1, for example \
             `sequence_table(5, step := 2)`. Rows stream back in batches, so large counts do \
             not buffer in memory.",
            "## sequence_table(count, step := 1)\n\n\
             `count` rows of `(n, squared)` with `n = 0, step, 2*step, ...`.\n\n\
             Deterministic, so it is handy for tests and demos.",
        );
        tags.push((
            "vgi.result_columns_schema".into(),
            result_columns_schema(schema().fields()),
        ));
        tags.push((
            "vgi.executable_examples".into(),
            json!([
                {
                    "description": "Three rows stepping by two",
                    "sql": "SELECT n, squared FROM starter.main.sequence_table(3, step := 2) ORDER BY n",
                    "expected_result": [[0, 0], [2, 4], [4, 16]],
                },
                {
                    "description": "Count the rows generated",
                    "sql": "SELECT count(*) AS rows FROM starter.main.sequence_table(1000)",
                    "expected_result": [[1000]],
                },
            ])
            .to_string(),
        ));
        tags.push((
            "vgi.example_queries".into(),
            example_queries_json(&examples()),
        ));
        FunctionMetadata {
            description: "Generate count rows of (n, squared), stepping n by step".into(),
            examples: examples(),
            tags,
            ..Default::default()
        }
    }

    fn argument_specs(&self) -> Vec<ArgSpec> {
        vec![
            ArgSpec::const_arg(
                "count",
                0,
                "int64",
                "How many rows to generate, from 0 to 1,000,000.",
            )
            .with_ge(0.0)
            .with_le(MAX_COUNT as f64),
            ArgSpec::const_arg(
                "step",
                -1,
                "int64",
                "Gap between consecutive n values; at least 1.",
            )
            .with_ge(1.0)
            .with_default(1),
        ]
    }

    fn on_bind(&self, params: &BindParams) -> Result<BindResponse> {
        args(&params.arguments)?;
        Ok(BindResponse {
            output_schema: schema(),
            opaque_data: Vec::new(),
        })
    }

    fn cardinality(&self, params: &BindParams) -> Option<TableCardinality> {
        let count = params.arguments.const_i64(0)?;
        Some(TableCardinality {
            estimate: Some(count),
            max: Some(count),
        })
    }

    fn producer(&self, params: &ProcessParams) -> Result<Box<dyn TableProducer>> {
        let (count, step) = args(&params.arguments)?;
        Ok(Box::new(SequenceProducer::new(
            params.output_schema.clone(),
            count,
            step,
        )))
    }
}
