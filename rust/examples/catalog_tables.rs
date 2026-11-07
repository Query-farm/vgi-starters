//! Recipe: expose a dataset as a named table, with a primary key.
//!
//! A catalog table is queried by name, with no parentheses, like any DuckDB table:
//!
//! ```sql
//! SELECT * FROM catalog_tables.main.countries WHERE continent = 'Europe';
//! ```
//!
//! Use a table for a fixed or slowly changing dataset (reference data, a
//! directory, a lookup list). Use a table function when the caller supplies
//! arguments. Declaring NOT NULL and a primary key tells the optimizer, and
//! agents, what identifies a row.
//!
//! Constraints are the worker's own attestation: declare a record as enforced
//! only if every row the table can return satisfies it.

use std::collections::HashMap;
use std::sync::Arc;

use arrow_array::{ArrayRef, RecordBatch, StringArray};
use arrow_schema::{DataType, Field, Schema, SchemaRef};
use vgi::catalog::{CatSchema, CatTable};
use vgi::constraints::{constraint_record, ConstraintKind, ConstraintRecord};
use vgi::table_function::{TableFunction, TableProducer};
use vgi::vgi_rpc::OutputCollector;
use vgi::{ArgSpec, BindParams, BindResponse, FunctionMetadata, ProcessParams, Result};

const CODES: [&str; 5] = ["DE", "FR", "JP", "KE", "US"];
const NAMES: [&str; 5] = ["Germany", "France", "Japan", "Kenya", "United States"];
const CONTINENTS: [&str; 5] = ["Europe", "Europe", "Asia", "Africa", "North America"];

fn commented(name: &str, nullable: bool, comment: &str) -> Field {
    Field::new(name, DataType::Utf8, nullable).with_metadata(HashMap::from([(
        "comment".to_string(),
        comment.to_string(),
    )]))
}

fn countries_schema() -> SchemaRef {
    Arc::new(Schema::new(vec![
        commented("code", false, "ISO 3166-1 alpha-2 country code."),
        commented("name", true, "English short name."),
        commented("continent", true, "Continent the country is in."),
    ]))
}

struct CountriesProducer {
    schema: SchemaRef,
    done: bool,
}

impl TableProducer for CountriesProducer {
    /// Emit every country once.
    fn next_batch(&mut self, _out: &mut OutputCollector) -> Result<Option<RecordBatch>> {
        if std::mem::replace(&mut self.done, true) {
            return Ok(None);
        }
        let columns: Vec<ArrayRef> = vec![
            Arc::new(StringArray::from(CODES.to_vec())),
            Arc::new(StringArray::from(NAMES.to_vec())),
            Arc::new(StringArray::from(CONTINENTS.to_vec())),
        ];
        RecordBatch::try_new(self.schema.clone(), columns)
            .map(Some)
            .map_err(super::arrow_err)
    }

    fn resume_supported(&self) -> bool {
        // One batch: nothing to resume.
        false
    }
}

/// Rows for the `countries` table. An ordinary table function.
struct CountriesSource;

impl TableFunction for CountriesSource {
    fn name(&self) -> &str {
        "countries_source"
    }

    fn metadata(&self) -> FunctionMetadata {
        FunctionMetadata {
            description: "Backing rows for the countries table".into(),
            ..Default::default()
        }
    }

    fn argument_specs(&self) -> Vec<ArgSpec> {
        Vec::new()
    }

    fn on_bind(&self, _params: &BindParams) -> Result<BindResponse> {
        Ok(BindResponse {
            output_schema: countries_schema(),
            opaque_data: Vec::new(),
        })
    }

    fn producer(&self, params: &ProcessParams) -> Result<Box<dyn TableProducer>> {
        Ok(Box::new(CountriesProducer {
            schema: params.output_schema.clone(),
            done: false,
        }))
    }
}

fn constraint(name: &str, kind: ConstraintKind) -> vgi::vgi_rpc::Bytes {
    constraint_record(
        &ConstraintRecord::new(&["main"], "countries", name, kind)
            .with_columns(&[&["code"]])
            .enforced(),
    )
    .expect("valid constraint record")
}

/// The `catalog_tables` worker.
pub fn worker() -> vgi::Worker {
    let mut worker = vgi::Worker::new();
    worker.register_table(CountriesSource);
    let countries = CatTable {
        name: "countries".into(),
        columns: countries_schema(),
        scan_function: "countries_source".into(),
        comment: Some("A small reference list of countries.".into()),
        constraints: vec![
            constraint("code_not_null", ConstraintKind::NotNull),
            constraint("pk", ConstraintKind::PrimaryKey),
        ],
        ..Default::default()
    };
    worker.set_catalog(vgi::catalog::CatalogModel {
        schemas: vec![CatSchema {
            name: "main".into(),
            tables: vec![countries],
            ..Default::default()
        }],
        ..super::catalog("catalog_tables")
    });
    worker
}
