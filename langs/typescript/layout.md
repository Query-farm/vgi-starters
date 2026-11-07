```
src/
  worker.ts        # entry point: serves the catalog over stdio, --unix or --http
  catalog.ts       # assembles the catalog; add new functions to FUNCTIONS
  meta.ts          # catalog/schema metadata + helpers (objectTags, column)
  greet.ts         # scalar example (overloads, const param with choices)
  sequence.ts      # table function example (named args, streaming state)
  fetch_items.ts   # REST API table function (pagination, retries, secrets)
  cli.ts           # flag parsing: stdio / --unix / --http (PORT:<n>, /health)
  log.ts           # stderr logger
bin/               # vgi-starter-worker (what DuckDB launches) and run-recipe
tests/             # bun unit tests (worker subprocess + mock API)
test/sql/          # sqllogictest files run through DuckDB
test/mock_api/     # offline mock of the REST API used by tests
examples/          # recipes: pushdown, caching, catalog tables, aggregates, COPY …
ci/                # integration harness used by `make test-sql` and CI
scripts/           # vgi_template.py (init / template-diff)
vgi-agent-tests.yaml  # private graders for the agent test tasks
```
