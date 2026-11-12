//! Discovery metadata: the one file to edit when you rename or extend the worker.
//!
//! DuckDB, coding agents and the `vgi-lint` metadata checker all read these
//! tags. Keep every object documented and `make lint-explain` stays at 100.
//!
//! * Catalog-level tags (title, license, author, links, long description) are
//!   built by [`catalog_tags`].
//! * Every function carries [`object_tags`]: a title, a category from
//!   [`SCHEMA_CATEGORIES`], search keywords, and LLM- and human-facing docs.

use std::collections::HashMap;
use std::sync::Arc;

use arrow_schema::{DataType, Field};
use serde_json::json;
use vgi::FunctionExample;

/// The name DuckDB ATTACHes. It must match the `ATTACH '<name>'` alias.
pub const CATALOG_NAME: &str = "starter";

/// Where the worker's source lives (shown to users and checked by vgi-lint).
pub const SOURCE_URL: &str = "https://github.com/Query-farm/vgi-starter-rust";

/// Categories group functions in docs and agent tools. Every object's
/// `vgi.category` must name one of these: `(name, title, description)`.
pub const SCHEMA_CATEGORIES: &[(&str, &str, &str)] = &[
    ("text", "Text", "Functions that build or transform strings."),
    (
        "generators",
        "Generators",
        "Table functions that generate rows.",
    ),
    (
        "api",
        "REST APIs",
        "Table functions that read from HTTP APIs.",
    ),
];

/// Turn `"a, b, c"` into the JSON array string `vgi.keywords` requires.
pub fn keywords_array(keywords: &str) -> String {
    let list: Vec<&str> = keywords
        .split(',')
        .map(str::trim)
        .filter(|k| !k.is_empty())
        .collect();
    serde_json::to_string(&list).unwrap_or_default()
}

/// Build the standard per-object discovery tags.
///
/// # Panics
///
/// If `category` is not one of [`SCHEMA_CATEGORIES`]: a typo here is a bug
/// worth failing loudly on at startup.
pub fn object_tags(
    title: &str,
    category: &str,
    keywords: &str,
    doc_llm: &str,
    doc_md: &str,
) -> Vec<(String, String)> {
    assert!(
        SCHEMA_CATEGORIES
            .iter()
            .any(|(name, _, _)| *name == category),
        "unknown category {category:?}; add it to SCHEMA_CATEGORIES"
    );
    vec![
        ("vgi.title".into(), title.into()),
        ("vgi.category".into(), category.into()),
        ("vgi.keywords".into(), keywords_array(keywords)),
        ("vgi.doc_llm".into(), doc_llm.into()),
        ("vgi.doc_md".into(), doc_md.into()),
    ]
}

/// A function example: `(description, sql)`.
pub fn example(description: &str, sql: &str) -> FunctionExample {
    FunctionExample {
        sql: sql.into(),
        description: description.into(),
        expected_output: None,
    }
}

/// The `vgi.example_queries` tag value for a list of examples.
pub fn example_queries_json(examples: &[FunctionExample]) -> String {
    let list: Vec<_> = examples
        .iter()
        .map(|e| json!({"description": e.description, "sql": e.sql}))
        .collect();
    serde_json::to_string(&list).unwrap_or_default()
}

/// Build an Arrow field whose `comment` shows up in DuckDB's `DESCRIBE` and
/// agent tools.
pub fn column(name: &str, data_type: DataType, comment: &str, nullable: bool) -> Field {
    Field::new(name, data_type, nullable).with_metadata(HashMap::from([(
        "comment".to_string(),
        comment.to_string(),
    )]))
}

/// The `vgi.result_columns_schema` tag for a table function's output fields.
pub fn result_columns_schema(fields: &[Arc<Field>]) -> String {
    let list: Vec<_> = fields
        .iter()
        .map(|f| {
            json!({
                "name": f.name(),
                "type": sql_type(f.data_type()),
                "description": f.metadata().get("comment").cloned().unwrap_or_default(),
            })
        })
        .collect();
    serde_json::to_string(&list).unwrap_or_default()
}

fn sql_type(data_type: &DataType) -> &'static str {
    match data_type {
        DataType::Int64 => "BIGINT",
        DataType::Float64 => "DOUBLE",
        DataType::Utf8 => "VARCHAR",
        DataType::Boolean => "BOOLEAN",
        _ => "UNKNOWN",
    }
}

const CATALOG_DOC_LLM: &str =
    "An example VGI worker generated from the Query.Farm starter template. It shows the three \
shapes most workers need: a vectorized scalar function with an overload, a deterministic \
table function with a named default argument, and a table function that pages through a \
REST API with retries and an optional bearer token taken from a DuckDB secret or an \
environment variable. Replace them with your own functions; keep the metadata complete so \
agents can discover and call them.";

const CATALOG_DOC_MD: &str = "# VGI starter worker\n\n\
**An example [VGI](https://query.farm/vgi) worker that brings custom functions into DuckDB \
SQL.** It was generated from the Query.Farm starter template and is meant to be renamed and \
rewritten: every function here demonstrates one pattern you will reuse, from vectorized scalars \
and streaming table functions to a REST API reader that retries politely and reads a bearer \
token from a DuckDB secret or the environment.\n\n\
The worker speaks Apache Arrow to DuckDB over stdin/stdout, a Unix socket, or HTTP, so the \
same code runs as a local subprocess or a hosted service. See the \
[VGI documentation](https://query.farm/vgi/docs/) to learn more.";

/// The catalog comment (`duckdb_databases().comment`).
pub fn catalog_comment() -> String {
    CATALOG_DOC_MD.to_string()
}

const SCHEMA_DOC_LLM: &str =
    "The `main` schema holds the starter template's example functions, one per common worker \
pattern. `greet(name)` and `greet(name, style)` are vectorized scalars: use them inside a \
projection or predicate, one result per row; `style` is a constant that must be 'casual' or \
'formal'. `sequence_table(count, step := 1)` is a deterministic table function that returns \
`count` rows of `(n, squared)`; use it for demos, tests, and as a template for generators. \
`fetch_items(base_url, page_size := 100)` reads every item from a paginated JSON REST API as \
`(id, name, category, price)` rows, streaming one page per batch, retrying rate-limited \
requests, and authenticating with a `starter_api` DuckDB secret or the `STARTER_API_TOKEN` \
environment variable. Scalars cannot take named arguments; table functions can.";

/// The schema comment.
pub const SCHEMA_DOC_MD: &str = "## main\n\n\
The starter template's example functions, one per common worker pattern.\n\n\
- **Scalars** answer one question per row and slot into a projection or predicate.\n\
- **Table functions** return sets of rows, streamed to DuckDB in Arrow batches.\n\
- **REST readers** page through an HTTP API and authenticate with a DuckDB secret.";

/// Public analyst tasks for `vgi-lint simulate`; private graders live in
/// `vgi-agent-tests.yaml`.
fn agent_test_tasks() -> String {
    json!([
        {"name": "formal_greeting", "prompt": "Using the starter worker, greet the name 'Grace' formally."},
        {"name": "sum_of_squares", "prompt": "Using the starter worker, what is the sum of the squared column for the first 10 rows of the sequence with the default step?"},
        {"name": "stepped_sequence", "prompt": "List the n values of a 4-row sequence that steps by 5, in ascending order."},
        {"name": "items_per_category", "prompt": "The items API is running at https://vgi-starter-api.query-farm.services/v1. How many items does it have in each category?"},
    ])
    .to_string()
}

/// Catalog-level tags (`duckdb_databases().tags`).
pub fn catalog_tags() -> Vec<(String, String)> {
    vec![
        ("vgi.title".into(), "VGI Starter Worker".into()),
        (
            "vgi.subtitle".into(),
            "Example DuckDB functions to rename and replace: a scalar, a table function and a REST API reader.".into(),
        ),
        ("vgi.doc_llm".into(), CATALOG_DOC_LLM.into()),
        ("vgi.doc_md".into(), CATALOG_DOC_MD.into()),
        ("vgi.source_url".into(), SOURCE_URL.into()),
        (
            "vgi.icon_url".into(),
            "https://query.farm/media-kit/products/vgi/vgi-logo.png".into(),
        ),
        ("vgi.license".into(), "0BSD".into()),
        ("vgi.author".into(), "Query.Farm".into()),
        (
            "vgi.copyright".into(),
            "Copyright 2026 Query.Farm LLC".into(),
        ),
        (
            "vgi.keywords".into(),
            keywords_array(
                "vgi, duckdb, starter template, example worker, rest api, table function",
            ),
        ),
        ("vgi.support_contact".into(), format!("{SOURCE_URL}/issues")),
        (
            "vgi.support_policy_url".into(),
            format!("{SOURCE_URL}#support"),
        ),
        ("vgi.agent_test_tasks".into(), agent_test_tasks()),
    ]
}

/// Schema-level tags for `main`. `examples` is every function's examples, for
/// the schema-wide `vgi.example_queries` list.
pub fn schema_tags(examples: &[FunctionExample]) -> Vec<(String, String)> {
    let categories: Vec<_> = SCHEMA_CATEGORIES
        .iter()
        .map(|(name, title, description)| {
            json!({"name": name, "title": title, "description": description})
        })
        .collect();
    vec![
        ("vgi.title".into(), "Starter Functions".into()),
        ("domain".into(), "examples".into()),
        (
            "vgi.keywords".into(),
            keywords_array("greeting, sequence, generator, rest api, pagination"),
        ),
        ("vgi.doc_llm".into(), SCHEMA_DOC_LLM.into()),
        ("vgi.doc_md".into(), SCHEMA_DOC_MD.into()),
        (
            "vgi.categories".into(),
            serde_json::to_string(&categories).unwrap_or_default(),
        ),
        ("vgi.example_queries".into(), example_queries_json(examples)),
    ]
}
