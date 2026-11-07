```
src/starter_worker/
  worker.py        # assembles the catalog; add new functions to FUNCTIONS
  meta.py          # catalog/schema metadata + helpers (object_tags, column)
  greet.py         # scalar example (overloads, ConstParam choices)
  sequence.py      # table function example (named args, streaming state)
  fetch_items.py   # REST API table function (pagination, retries, secrets)
tests/             # pytest unit tests (in-process client)
test/sql/          # sqllogictest files run through DuckDB
test/mock_api/     # offline mock of the REST API used by tests
examples/          # recipes: pushdown, caching, catalog tables, aggregates, COPY …
ci/                # integration harness used by `make test-sql` and CI
scripts/           # vgi_template.py (init / template-diff)
vgi-agent-tests.yaml  # private graders for the agent test tasks
```
