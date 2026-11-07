```
src/
  main.rs          # binary entry point: logging setup, then Worker::run() (parses argv)
  lib.rs           # build_worker(): registers functions, secret type and catalog
  meta.rs          # catalog/schema metadata + helpers (object_tags, column, example)
  greet.rs         # scalar example (overload, const argument with choices)
  sequence.rs      # table function example (named args, resumable producer)
  fetch_items.rs   # REST API table function (pagination, retries, secrets)
tests/             # cargo unit tests (pure logic + the mock API)
test/sql/          # sqllogictest files run through DuckDB
test/mock_api/     # offline mock of the REST API used by tests
examples/          # recipes: pushdown, caching, catalog tables, aggregates, COPY …
ci/                # integration harness used by `make test-sql` and CI
scripts/           # vgi_template.py (init / template-diff)
vgi-agent-tests.yaml  # private graders for the agent test tasks
```
