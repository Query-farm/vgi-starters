```
src/main/java/farm/query/starter/
  StarterWorker.java   # main(): assembles the catalog; register new functions in build()
  Meta.java            # catalog/schema metadata + helpers (objectTags, column, positional, named)
  Greet.java           # scalar example: greet(name)
  GreetWithStyle.java  # scalar overload: greet(name, style), a @Const with choices
  SequenceTable.java   # table function example (named args, streaming state)
  FetchItems.java      # REST API table function (pagination, retries, secrets)
src/test/java/         # JUnit unit tests
bin/                   # vgi-starter-worker: the launcher DuckDB runs (java -jar build/libs/...)
test/sql/              # sqllogictest files run through DuckDB
test/mock_api/         # offline mock of the REST API used by tests
examples/              # recipes: pushdown, caching, catalog tables, aggregates, COPY …
ci/                    # integration harness used by `make test-sql` and CI
scripts/               # vgi_template.py (init / template-diff)
vgi-agent-tests.yaml   # private graders for the agent test tasks
```
