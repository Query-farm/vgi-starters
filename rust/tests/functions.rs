//! Unit tests. The SQL-level tests live in test/sql and run through DuckDB.

use std::io::{BufRead, BufReader};
use std::path::Path;
use std::process::{Child, Command, Stdio};

use arrow_array::cast::AsArray;
use arrow_array::types::Int64Type;
use arrow_array::StringArray;
use starter_worker::fetch_items::{self, get_text, page_url, parse_page, ItemsProducer};
use starter_worker::greet::greet_array;
use starter_worker::sequence::{self, SequenceProducer};

#[test]
fn greet_casual_and_formal() {
    let names = StringArray::from(vec![Some("Ada"), None, Some("Grace")]);
    let casual: Vec<_> = greet_array(&names, "casual")
        .iter()
        .map(|v| v.map(String::from))
        .collect();
    assert_eq!(
        casual,
        vec![Some("Hi, Ada!".into()), None, Some("Hi, Grace!".into())]
    );
    let formal: Vec<_> = greet_array(&names, "formal")
        .iter()
        .map(|v| v.map(String::from))
        .collect();
    assert_eq!(
        formal,
        vec![
            Some("Good day, Ada.".into()),
            None,
            Some("Good day, Grace.".into())
        ]
    );
}

/// Drain a sequence producer, returning (batch sizes, every n).
fn drain(count: i64, step: i64) -> (Vec<usize>, Vec<i64>) {
    let mut producer = SequenceProducer::new(sequence::schema(), count, step);
    let (mut sizes, mut values) = (Vec::new(), Vec::new());
    while let Some(batch) = producer.next_rows().expect("batch") {
        sizes.push(batch.num_rows());
        values.extend(
            batch
                .column(0)
                .as_primitive::<Int64Type>()
                .values()
                .iter()
                .copied(),
        );
    }
    (sizes, values)
}

#[test]
fn sequence_default_and_named_step() {
    assert_eq!(drain(4, 1).1, vec![0, 1, 2, 3]);
    assert_eq!(drain(3, 5).1, vec![0, 5, 10]);
    assert!(drain(0, 1).0.is_empty());
}

#[test]
fn sequence_streams_in_bounded_batches() {
    let (sizes, values) = drain(25_000, 1);
    assert_eq!(sizes, vec![10_000, 10_000, 5_000]);
    assert_eq!(values.last(), Some(&24_999));
}

#[test]
fn fetch_items_parses_a_page() {
    let body =
        r#"{"items":[{"id":1,"name":"a","category":"hardware","price":1.5}],"next_page":null}"#;
    let (items, next) = parse_page(body).expect("parse");
    assert_eq!(next, None);
    let batch = ItemsProducer::to_batch(&fetch_items::schema(), &items).expect("batch");
    assert_eq!(batch.num_rows(), 1);
    assert_eq!(
        page_url("http://x/", 2, 10),
        "http://x/items?page=2&page_size=10"
    );
}

/// The mock API from test/mock_api, killed on drop.
struct MockApi(Child, String);

impl MockApi {
    fn start(env: &[(&str, &str)]) -> Self {
        let script = Path::new(env!("CARGO_MANIFEST_DIR")).join("test/mock_api/server.py");
        let python = std::env::var("PYTHON").unwrap_or_else(|_| "python3".into());
        let mut child = Command::new(python)
            .arg(script)
            .envs(env.iter().copied())
            .stdout(Stdio::piped())
            .spawn()
            .expect("start the mock API (needs python3 on PATH, or set PYTHON)");
        let mut line = String::new();
        BufReader::new(child.stdout.take().expect("stdout"))
            .read_line(&mut line)
            .expect("PORT line");
        let port = line
            .trim()
            .strip_prefix("PORT:")
            .expect("PORT:<n>")
            .to_string();
        MockApi(child, format!("http://127.0.0.1:{port}"))
    }
}

impl Drop for MockApi {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[test]
fn fetch_items_retries_rate_limits() {
    let api = MockApi::start(&[("MOCK_API_FLAKY", "1")]);
    let (items, next) =
        parse_page(&get_text(&page_url(&api.1, 1, 4), None).expect("GET")).expect("parse");
    assert_eq!(
        items.iter().map(|i| i.id).collect::<Vec<_>>(),
        vec![1, 2, 3, 4]
    );
    assert_eq!(next, Some(2));
}

#[test]
fn fetch_items_reports_the_http_status() {
    let api = MockApi::start(&[("MOCK_API_TOKEN", "test-token")]);
    let err = get_text(&page_url(&api.1, 1, 4), None).expect_err("401");
    assert!(err.to_string().contains("HTTP 401"), "{err}");
    assert!(get_text(&page_url(&api.1, 1, 4), Some("test-token")).is_ok());
}
