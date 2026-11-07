//! Recipe: write query results out with `COPY … TO` and a custom format.
//!
//! ```sql
//! ATTACH 'copy_to_sink' (TYPE vgi, LOCATION '…/recipes copy_to_sink');
//! COPY (SELECT * FROM t) TO 'out.jsonl' (FORMAT 'copy_to_sink.jsonl_out');
//! ```
//!
//! The format name is qualified by the attach alias. `write` runs once per
//! batch (possibly in parallel, possibly in different processes), so it
//! stashes each batch in the execution-scoped `storage`; `close` runs once and
//! writes the file. Swap the file write for an upload to object storage, a
//! warehouse load, or an API call.

use std::fs::File;
use std::io::{BufWriter, Write};

use arrow_array::RecordBatch;
use vgi::copy_to::{CopyToCloseContext, CopyToFunction, CopyToWriteContext};
use vgi::{ArgSpec, Result, RpcError};

const SHARDS: &[u8] = b"shards";

struct WriteJsonLines;

/// Reorder a batch's columns by name (the `sort_keys` option).
fn sorted_columns(batch: &RecordBatch) -> Result<RecordBatch> {
    let schema = batch.schema();
    let mut order: Vec<usize> = (0..schema.fields().len()).collect();
    order.sort_by(|a, b| schema.field(*a).name().cmp(schema.field(*b).name()));
    batch.project(&order).map_err(super::arrow_err)
}

impl CopyToFunction for WriteJsonLines {
    fn format(&self) -> &str {
        "jsonl_out"
    }

    fn handler_name(&self) -> &str {
        "write_json_lines"
    }

    fn comment(&self) -> Option<String> {
        Some("Newline-delimited JSON, one object per row".into())
    }

    /// COPY options are named arguments (position -1).
    fn argument_specs(&self) -> Vec<ArgSpec> {
        vec![ArgSpec::column(
            "sort_keys",
            -1,
            "boolean",
            "Sort the keys of each JSON object",
        )
        .with_default(false)]
    }

    /// Stash this batch, scoped to this COPY by `execution_id`.
    fn write(&self, ctx: &CopyToWriteContext, batch: &RecordBatch) -> Result<()> {
        if batch.num_rows() > 0 {
            ctx.storage
                .append(ctx.execution_id, SHARDS, b"", vgi::ipc::write_batch(batch)?);
        }
        Ok(())
    }

    /// Write every stashed batch to the file once; return the row count.
    fn close(&self, ctx: &CopyToCloseContext) -> Result<i64> {
        let sort_keys = ctx.options.named_bool("sort_keys").unwrap_or(false);
        let file = File::create(ctx.path)
            .map_err(|e| RpcError::runtime_error(format!("create {}: {e}", ctx.path)))?;
        let mut writer = arrow_json::LineDelimitedWriter::new(BufWriter::new(file));
        let mut written = 0i64;
        for (_id, blob) in ctx
            .storage
            .scan(ctx.execution_id, SHARDS, b"", -1, usize::MAX)
        {
            let batch = vgi::ipc::read_batch(&blob)?;
            let batch = if sort_keys {
                sorted_columns(&batch)?
            } else {
                batch
            };
            written += batch.num_rows() as i64;
            writer.write(&batch).map_err(super::arrow_err)?;
        }
        writer.finish().map_err(super::arrow_err)?;
        writer.into_inner().flush().map_err(super::arrow_err)?;
        Ok(written)
    }
}

/// The `copy_to_sink` worker.
pub fn worker() -> vgi::Worker {
    let mut worker = vgi::Worker::new();
    worker.register_copy_to(WriteJsonLines);
    worker.set_catalog(super::catalog("copy_to_sink"));
    worker
}
