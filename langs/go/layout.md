```
cmd/vgi-starter-worker/  # main.go: the worker binary (flags -> serve.Main)
internal/starter/
  worker.go        # assembles the catalog; register new functions in NewWorker
  meta.go          # catalog/schema metadata + helpers (objectTags, column)
  greet.go         # scalar example (overloads, constant arg with choices)
  sequence.go      # table function example (named args, streaming state)
  fetch_items.go   # REST API table function (pagination, retries, secrets)
  version.go       # the worker version (checked against release tags)
  *_test.go        # go test unit tests
internal/serve/    # --http / --unix / stdio and logging flags, shared with recipes
test/sql/          # sqllogictest files run through DuckDB
test/mock_api/     # offline mock of the REST API used by tests
examples/          # recipes (one package main, served by bin/recipes <name>)
ci/                # integration harness used by `make test-sql` and CI
scripts/           # vgi_template.py (init / template-diff)
vgi-agent-tests.yaml  # private graders for the agent test tasks
```
