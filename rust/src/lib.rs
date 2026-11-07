//! The VGI starter worker: one catalog, one `main` schema, four example
//! functions.
//!
//! To add a function: write it (see [`greet`] / [`sequence`] /
//! [`fetch_items`]), then register it in [`build_worker`] and add its examples
//! to the schema list there.
//!
//! Run it:
//!
//! ```text
//! vgi-starter-worker                      # stdio: what DuckDB launches by default
//! vgi-starter-worker --http --port 8000   # HTTP: for Docker / hosted deploys
//! vgi-starter-worker --unix /tmp/w.sock   # Unix socket (the `launch:` transport)
//! ```
//!
//! Attach it from DuckDB:
//!
//! ```sql
//! ATTACH 'starter' (TYPE vgi, LOCATION '/abs/path/to/target/release/vgi-starter-worker');
//! ```

pub mod fetch_items;
pub mod greet;
pub mod meta;
pub mod sequence;

use vgi::catalog::{CatSchema, CatalogModel};
use vgi::ScalarFunction;
use vgi::Worker;

/// Build the worker: register every function, the secret type and the catalog.
pub fn build_worker() -> Worker {
    let mut worker = Worker::new();
    worker.register_scalar(greet::Greet);
    worker.register_scalar(greet::GreetWithStyle);
    worker.register_table(sequence::SequenceTable);
    worker.register_table(fetch_items::FetchItems);
    worker.register_secret_type(fetch_items::secret_type());

    // Every function's examples, for the schema-wide `vgi.example_queries`.
    let mut examples = greet::Greet.metadata().examples;
    examples.extend(greet::GreetWithStyle.metadata().examples);
    examples
        .extend(vgi::table_function::TableFunction::metadata(&sequence::SequenceTable).examples);
    examples
        .extend(vgi::table_function::TableFunction::metadata(&fetch_items::FetchItems).examples);

    worker.set_catalog(CatalogModel {
        name: meta::CATALOG_NAME.into(),
        comment: Some(meta::catalog_comment()),
        tags: meta::catalog_tags(),
        source_url: Some(meta::SOURCE_URL.into()),
        implementation_version: Some(env!("CARGO_PKG_VERSION").into()),
        schemas: vec![CatSchema {
            name: "main".into(),
            comment: Some(meta::SCHEMA_DOC_MD.into()),
            tags: meta::schema_tags(&examples),
            ..Default::default()
        }],
        ..Default::default()
    });
    worker
}
