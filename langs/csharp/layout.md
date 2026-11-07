```
src/Starter.Worker/
  Program.cs        # entry point: flags, logging to stderr, RunFromArgsAsync
  StarterWorker.cs  # assembles the catalog; register new functions here
  Meta.cs           # catalog/schema metadata + helpers (ObjectTags, Column, Argument)
  Greet.cs          # scalar example (overloads, [ConstParam] with choices)
  SequenceTable.cs  # table function example (named args, streaming producer)
  FetchItems.cs     # REST API table function (pagination, retries, secrets)
tests/Starter.Worker.Tests/  # xunit unit tests (producers, parsing, HTTP /health)
test/sql/          # sqllogictest files run through DuckDB
test/mock_api/     # offline mock of the REST API used by tests
examples/          # recipes (examples/Starter.Recipes): pushdown, caching, catalog tables …
ci/                # integration harness used by `make test-sql` and CI
scripts/           # vgi_template.py (init / template-diff)
Starter.slnx       # the solution; Directory.*.props hold shared settings and versions
vgi-agent-tests.yaml  # private graders for the agent test tasks
```
