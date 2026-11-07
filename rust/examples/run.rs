//! Run one recipe as a worker: `recipes <recipe> [worker flags]`.
//!
//! ```sql
//! ATTACH 'aggregate' (TYPE vgi, LOCATION '/abs/target/release/examples/recipes aggregate');
//! ```
//!
//! Each recipe is a module next to this file that builds a complete
//! [`vgi::Worker`]. `make build` compiles this dispatcher.

mod aggregate;
mod cache_expensive_call;
mod catalog_tables;
mod copy_to_sink;
mod pushdown_sqlite;
mod transform_rows;

const RECIPES: [&str; 6] = [
    "transform_rows",
    "aggregate",
    "pushdown_sqlite",
    "cache_expensive_call",
    "catalog_tables",
    "copy_to_sink",
];

fn main() {
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("warn"))
        .target(env_logger::Target::Stderr)
        .init();
    // The SDK reads transport flags (--http, --unix, ...) from argv; the recipe
    // name is just one more argument it ignores.
    let worker = match std::env::args().nth(1).as_deref() {
        Some("transform_rows") => transform_rows::worker(),
        Some("aggregate") => aggregate::worker(),
        Some("pushdown_sqlite") => pushdown_sqlite::worker(),
        Some("cache_expensive_call") => cache_expensive_call::worker(),
        Some("catalog_tables") => catalog_tables::worker(),
        Some("copy_to_sink") => copy_to_sink::worker(),
        _ => {
            eprintln!("usage: recipes <{}> [worker flags]", RECIPES.join("|"));
            std::process::exit(2);
        }
    };
    worker.run();
}

/// A catalog named `name` with no extra metadata (the recipes stay small).
fn catalog(name: &str) -> vgi::catalog::CatalogModel {
    vgi::catalog::CatalogModel {
        name: name.into(),
        ..Default::default()
    }
}

/// Map an Arrow error into the SDK's error type.
fn arrow_err(e: impl std::fmt::Display) -> vgi::RpcError {
    vgi::RpcError::runtime_error(e.to_string())
}
