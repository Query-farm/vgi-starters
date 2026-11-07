//! Recipe: let DuckDB cache an expensive result.
//!
//! The worker *advertises* how long a result stays fresh. DuckDB caches it, and
//! repeat queries inside the TTL never reach the worker. After the TTL, DuckDB
//! revalidates with an ETag; if nothing changed the worker answers "not
//! modified" with zero rows.
//!
//! ```sql
//! SELECT * FROM cache_expensive_call.main.exchange_rates();
//! ```

use std::collections::HashMap;
use std::sync::Arc;

use arrow_array::{ArrayRef, Float64Array, RecordBatch, StringArray};
use arrow_schema::{DataType, Field, Schema, SchemaRef};
use vgi::cache_control::{CacheControl, ConditionalRequest};
use vgi::table_function::{TableFunction, TableProducer};
use vgi::vgi_rpc::OutputCollector;
use vgi::{ArgSpec, BindParams, BindResponse, FunctionMetadata, ProcessParams, Result};

/// In a real worker, derive this from the upstream: a Last-Modified header, a
/// version column, a content hash. It is the only thing revalidation compares.
const DATA_VERSION: &str = "\"rates-v1\"";
const CURRENCIES: [&str; 3] = ["EUR", "GBP", "JPY"];
const RATES: [f64; 3] = [1.09, 1.27, 0.0067];

fn rates_schema() -> SchemaRef {
    Arc::new(Schema::new(vec![
        Field::new("currency", DataType::Utf8, true),
        Field::new("rate", DataType::Float64, true),
    ]))
}

struct RatesProducer {
    schema: SchemaRef,
    done: bool,
    /// Set by `on_conditional_request` before the first `next_batch`.
    if_none_match: Option<String>,
    /// Cache metadata for the batch just returned; the SDK puts it on the wire.
    meta: Option<HashMap<String, String>>,
}

impl TableProducer for RatesProducer {
    fn on_conditional_request(&mut self, request: &ConditionalRequest) {
        self.if_none_match = request.if_none_match.clone();
    }

    /// Emit the rates with cache metadata, or confirm the client's copy is current.
    fn next_batch(&mut self, _out: &mut OutputCollector) -> Result<Option<RecordBatch>> {
        if self.done {
            return Ok(None);
        }
        self.done = true;
        let mut cc = CacheControl::ttl(300)
            .with_etag(DATA_VERSION)
            .with_revalidatable()
            .with_stale_while_revalidate(60)
            .with_stale_if_error(600);
        let not_modified = self.if_none_match.as_deref() == Some(DATA_VERSION);
        if not_modified {
            cc = cc.with_not_modified();
        }
        // Cache metadata must ride on the FIRST batch.
        self.meta = Some(cc.to_metadata());
        let n = if not_modified { 0 } else { CURRENCIES.len() };
        let columns: Vec<ArrayRef> = vec![
            Arc::new(StringArray::from(CURRENCIES[..n].to_vec())),
            Arc::new(Float64Array::from(RATES[..n].to_vec())),
        ];
        RecordBatch::try_new(self.schema.clone(), columns)
            .map(Some)
            .map_err(super::arrow_err)
    }

    fn last_metadata(&self) -> Option<HashMap<String, String>> {
        self.meta.clone()
    }

    fn resume_supported(&self) -> bool {
        // One batch: nothing to resume.
        false
    }
}

struct ExchangeRates;

impl TableFunction for ExchangeRates {
    fn name(&self) -> &str {
        "exchange_rates"
    }

    fn metadata(&self) -> FunctionMetadata {
        FunctionMetadata {
            description: "Exchange rates from a slow upstream, cacheable for 5 minutes".into(),
            ..Default::default()
        }
    }

    fn argument_specs(&self) -> Vec<ArgSpec> {
        Vec::new()
    }

    fn on_bind(&self, _params: &BindParams) -> Result<BindResponse> {
        Ok(BindResponse {
            output_schema: rates_schema(),
            opaque_data: Vec::new(),
        })
    }

    fn producer(&self, params: &ProcessParams) -> Result<Box<dyn TableProducer>> {
        Ok(Box::new(RatesProducer {
            schema: params.output_schema.clone(),
            done: false,
            if_none_match: None,
            meta: None,
        }))
    }
}

/// The `cache_expensive_call` worker.
pub fn worker() -> vgi::Worker {
    let mut worker = vgi::Worker::new();
    worker.register_table(ExchangeRates);
    worker.set_catalog(super::catalog("cache_expensive_call"));
    worker
}
