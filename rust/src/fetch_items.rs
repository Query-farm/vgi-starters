//! Table function example: `fetch_items`, wrapping a paginated REST API.
//!
//! This is the pattern most workers start from: page through an HTTP API and
//! stream each page to DuckDB as one Arrow batch.
//!
//! * **Streaming.** [`ItemsProducer`] fetches one page per batch and keeps the
//!   next page number, so a 10,000-page API never sits in memory.
//! * **Politeness.** Requests time out, and 429/5xx responses are retried with
//!   backoff that honours `Retry-After`.
//! * **Credentials.** A bearer token comes from a DuckDB secret if one exists
//!   (`CREATE SECRET (TYPE starter_api, TOKEN '...')`), otherwise from the
//!   `STARTER_API_TOKEN` environment variable. Only the page number goes into
//!   the resume state, because that state can travel back to DuckDB over HTTP;
//!   the token is re-read from the call's secrets whenever the producer is built.
//!
//! The tests run against `test/mock_api/server.py`; point `base_url` at your
//! real API once you adapt the parsing in [`parse_page`].

use std::sync::Arc;
use std::time::Duration;

use arrow_array::{ArrayRef, Float64Array, Int64Array, RecordBatch, StringArray};
use arrow_schema::{DataType, Field, Schema, SchemaRef};
use serde::Deserialize;
use vgi::catalog::SecretTypeSpec;
use vgi::secrets::{SecretLookup, Secrets};
use vgi::table_function::{TableFunction, TableProducer};
use vgi::vgi_rpc::OutputCollector;
use vgi::{ArgSpec, BindParams, BindResponse, FunctionMetadata, ProcessParams, Result, RpcError};

use crate::meta::{column, example, example_queries_json, object_tags, result_columns_schema};

/// The DuckDB secret type: `CREATE SECRET (TYPE starter_api, TOKEN '...')`.
pub const SECRET_TYPE: &str = "starter_api";
/// Fallback when no secret exists.
pub const TOKEN_ENV_VAR: &str = "STARTER_API_TOKEN";
const TIMEOUT: Duration = Duration::from_secs(30);
const MAX_ATTEMPTS: u32 = 4;
const USER_AGENT: &str = "vgi-starter-worker/0.1";
const MAX_PAGE_SIZE: i64 = 1000;
const URL_PATTERN: &str = "^https?://";

/// Registered with DuckDB so users can run `CREATE SECRET (TYPE starter_api, TOKEN '...')`.
pub fn secret_type() -> SecretTypeSpec {
    let token = Field::new("token", DataType::Utf8, true).with_metadata(
        [
            ("redact".to_string(), "true".to_string()),
            (
                "vgi_doc".to_string(),
                "Bearer token sent in the Authorization header.".to_string(),
            ),
        ]
        .into_iter()
        .collect(),
    );
    let doc_url = [(
        "vgi_doc_url".to_string(),
        "https://github.com/Query-farm/vgi-starter-rust#secrets-and-configuration".to_string(),
    )];
    SecretTypeSpec {
        name: SECRET_TYPE.into(),
        description: "Bearer token for the API that fetch_items reads.".into(),
        parameters_schema: Arc::new(
            Schema::new(vec![token]).with_metadata(doc_url.into_iter().collect()),
        ),
    }
}

/// The output columns.
pub fn schema() -> SchemaRef {
    Arc::new(Schema::new(vec![
        column(
            "id",
            DataType::Int64,
            "Item identifier assigned by the API.",
            false,
        ),
        column("name", DataType::Utf8, "Item display name.", true),
        column(
            "category",
            DataType::Utf8,
            "Item category, e.g. 'hardware'.",
            true,
        ),
        column(
            "price",
            DataType::Float64,
            "Item price in US dollars.",
            true,
        ),
    ]))
}

/// One item as the API returns it. Adapt this to your API.
#[derive(Debug, Deserialize)]
pub struct Item {
    /// Item identifier.
    pub id: i64,
    /// Display name.
    pub name: Option<String>,
    /// Category.
    pub category: Option<String>,
    /// Price in US dollars.
    pub price: Option<f64>,
}

#[derive(Deserialize)]
struct Page {
    items: Vec<Item>,
    next_page: Option<i64>,
}

/// Split one API response into rows and the next page number. Adapt this to your API.
pub fn parse_page(body: &str) -> Result<(Vec<Item>, Option<i64>)> {
    let page: Page = serde_json::from_str(body)
        .map_err(|e| RpcError::runtime_error(format!("unexpected API response: {e}")))?;
    Ok((page.items, page.next_page))
}

/// The bearer token: DuckDB secret first, then the environment.
pub fn token(secrets: &Secrets) -> Option<String> {
    secrets
        .of_type(SECRET_TYPE)
        .find_map(|s| s.get("token").filter(|t| !t.is_empty()).cloned())
        .or_else(|| std::env::var(TOKEN_ENV_VAR).ok().filter(|t| !t.is_empty()))
}

/// The page URL: `<base_url without trailing />/items?page=N&page_size=M`.
pub fn page_url(base_url: &str, page: i64, page_size: i64) -> String {
    format!(
        "{}/items?page={page}&page_size={page_size}",
        base_url.trim_end_matches('/')
    )
}

fn backoff(attempt: u32) -> Duration {
    Duration::from_millis(500 * (1 << attempt))
}

/// GET `url` and return the body, retrying 429 and 5xx with backoff.
pub fn get_text(url: &str, token: Option<&str>) -> Result<String> {
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(TIMEOUT))
        .http_status_as_error(false)
        .user_agent(USER_AGENT)
        .build()
        .into();
    for attempt in 1..=MAX_ATTEMPTS {
        let mut request = agent.get(url).header("Accept", "application/json");
        if let Some(t) = token {
            request = request.header("Authorization", format!("Bearer {t}"));
        }
        let mut response = match request.call() {
            Ok(response) => response,
            Err(err) if attempt < MAX_ATTEMPTS => {
                log::warn!("GET {url} failed ({err}); retrying");
                std::thread::sleep(backoff(attempt));
                continue;
            }
            Err(err) => return Err(RpcError::runtime_error(format!("GET {url} failed: {err}"))),
        };
        let status = response.status().as_u16();
        if (200..300).contains(&status) {
            return response
                .body_mut()
                .read_to_string()
                .map_err(|e| RpcError::runtime_error(format!("GET {url}: {e}")));
        }
        let retryable = status == 429 || status >= 500;
        if !retryable || attempt == MAX_ATTEMPTS {
            let mut body = response.body_mut().read_to_string().unwrap_or_default();
            body.truncate(500);
            return Err(RpcError::runtime_error(format!(
                "GET {url} failed with HTTP {status}: {body}"
            )));
        }
        let delay = response
            .headers()
            .get("Retry-After")
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.trim().parse::<u64>().ok())
            .map(Duration::from_secs)
            .unwrap_or_else(|| backoff(attempt));
        log::debug!("GET {url}: HTTP {status}, retrying in {delay:?}");
        std::thread::sleep(delay.min(Duration::from_secs(30)));
    }
    unreachable!("the loop returns on the last attempt")
}

/// Read `(base_url, page_size)`. The SDK has already enforced `base_url`'s
/// pattern and `page_size`'s range.
fn args(arguments: &vgi::arguments::Arguments) -> Result<(String, i64)> {
    let base_url = arguments.const_str(0).unwrap_or_default();
    let page_size = arguments.named_i64("page_size").unwrap_or(100);
    Ok((base_url, page_size))
}

/// The scan cursor: the next page to request, or 0 once the API says there
/// are no more. Only `next_page` travels in the HTTP resume state.
pub struct ItemsProducer {
    schema: SchemaRef,
    base_url: String,
    page_size: i64,
    token: Option<String>,
    next_page: i64,
}

impl ItemsProducer {
    /// Turn one page of items into a batch of the bound schema.
    pub fn to_batch(schema: &SchemaRef, items: &[Item]) -> Result<RecordBatch> {
        let columns: Vec<ArrayRef> = vec![
            Arc::new(items.iter().map(|i| i.id).collect::<Int64Array>()),
            Arc::new(
                items
                    .iter()
                    .map(|i| i.name.as_deref())
                    .collect::<StringArray>(),
            ),
            Arc::new(
                items
                    .iter()
                    .map(|i| i.category.as_deref())
                    .collect::<StringArray>(),
            ),
            Arc::new(items.iter().map(|i| i.price).collect::<Float64Array>()),
        ];
        RecordBatch::try_new(schema.clone(), columns)
            .map_err(|e| RpcError::runtime_error(e.to_string()))
    }
}

impl TableProducer for ItemsProducer {
    fn next_batch(&mut self, _out: &mut OutputCollector) -> Result<Option<RecordBatch>> {
        if self.next_page == 0 {
            return Ok(None);
        }
        let url = page_url(&self.base_url, self.next_page, self.page_size);
        let (items, next_page) = parse_page(&get_text(&url, self.token.as_deref())?)?;
        self.next_page = next_page.unwrap_or(0);
        Self::to_batch(&self.schema, &items).map(Some)
    }

    vgi::resume_fields!(next_page);
}

/// `fetch_items(base_url, page_size := 100)`.
pub struct FetchItems;

fn examples() -> Vec<vgi::FunctionExample> {
    vec![
        example(
            "The five priciest hardware items from the public demo API",
            "SELECT id, name, price FROM starter.main.fetch_items('https://vgi-starter-api.query-farm.services/v1') \
             WHERE category = 'hardware' ORDER BY price DESC LIMIT 5",
        ),
        example(
            "Summarize the API's items by category, ten per request",
            "SELECT category, count(*) AS items, avg(price) AS avg_price \
             FROM starter.main.fetch_items('https://vgi-starter-api.query-farm.services/v1', page_size := 10) \
             GROUP BY category ORDER BY category",
        ),
    ]
}

impl TableFunction for FetchItems {
    fn name(&self) -> &str {
        "fetch_items"
    }

    fn metadata(&self) -> FunctionMetadata {
        let mut tags = object_tags(
            "Fetch Items From API",
            "api",
            "rest api, http, pagination, fetch, items, json api, bearer token",
            "Read all items from a paginated JSON REST API at `base_url` and return them as \
             rows `(id, name, category, price)`. Pages are requested `page_size` at a time \
             (named argument, default 100) and streamed to DuckDB as they arrive. If a \
             DuckDB secret of type `starter_api` exists its `token` is sent as a bearer \
             token; otherwise the `STARTER_API_TOKEN` environment variable is used. \
             Rate-limit (429) and server errors are retried with backoff.",
            "## fetch_items(base_url, page_size := 100)\n\n\
             Every item from a paginated REST API, streamed one page per batch.\n\n\
             Authenticate with a `starter_api` secret or the `STARTER_API_TOKEN` \
             environment variable:\n\n\
             ```sql\nCREATE SECRET (TYPE starter_api, TOKEN 'your-token');\n```",
        );
        tags.push((
            "vgi.result_columns_schema".into(),
            result_columns_schema(schema().fields()),
        ));
        tags.push((
            "vgi.example_queries".into(),
            example_queries_json(&examples()),
        ));
        FunctionMetadata {
            description: "Read every item from a paginated REST API, one page per batch".into(),
            examples: examples(),
            tags,
            ..Default::default()
        }
    }

    fn argument_specs(&self) -> Vec<ArgSpec> {
        vec![
            ArgSpec::const_arg(
                "base_url",
                0,
                "varchar",
                "Root URL of the API, e.g. 'https://vgi-starter-api.query-farm.services/v1'.",
            )
            .with_pattern(URL_PATTERN),
            ArgSpec::const_arg(
                "page_size",
                -1,
                "int64",
                "Items requested per page; 1 to 1000.",
            )
            .with_ge(1.0)
            .with_le(MAX_PAGE_SIZE as f64)
            .with_default(100),
        ]
    }

    /// Ask DuckDB for any `starter_api` secret. With none, bind still succeeds
    /// and the producer falls back to the environment variable.
    fn secret_lookups(&self, _params: &BindParams) -> Vec<SecretLookup> {
        vec![SecretLookup {
            secret_type: SECRET_TYPE.into(),
            scope: None,
            name: None,
        }]
    }

    fn on_bind(&self, params: &BindParams) -> Result<BindResponse> {
        args(&params.arguments)?;
        Ok(BindResponse {
            output_schema: schema(),
            opaque_data: Vec::new(),
        })
    }

    fn producer(&self, params: &ProcessParams) -> Result<Box<dyn TableProducer>> {
        let (base_url, page_size) = args(&params.arguments)?;
        Ok(Box::new(ItemsProducer {
            schema: params.output_schema.clone(),
            base_url,
            page_size,
            token: token(&params.secrets),
            next_page: 1,
        }))
    }
}
