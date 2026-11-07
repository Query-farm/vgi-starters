<!-- managed-by: vgi-starters (edit upstream in shared/AGENTS.md.tmpl, then run tools/render.py) -->
# AGENTS.md: working on this VGI worker

This repository is a **VGI worker** written in Java. DuckDB starts it (or connects
to it over HTTP) and calls its functions from SQL; data moves as Apache Arrow
batches. Background reading, in order of usefulness:

1. https://query.farm/vgi/llms.txt — index of all VGI docs, written for agents
2. https://query.farm/vgi/docs/java/ — the Java SDK (vgi-java)
3. https://query.farm/vgi/building-a-worker — concepts: catalogs, schemas, function kinds

## Definition of done

**`make check` passes.** It runs formatting and static checks, unit tests, the SQL
tests over the stdio, launch and http transports, and the metadata quality check
(vgi-lint, at the `fail_on` level in `vgi-lint.toml`). Don't hand work back until it passes. If a
check is wrong, fix the check and say why. Never delete a test to get green.

Faster loops while iterating: `make test-unit`, `make test-sql`, `make lint-explain`.

## Layout

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

## Recipe: add a function

1. Create `src/main/java/farm/query/starter/<Name>.java`. Copy the closest example:
   `Greet.java` for a scalar, `SequenceTable.java` for a generator, `FetchItems.java` for an API.
2. Scalars extend `ScalarFn` and declare one `compute(...)` method: `@Vector` parameters are
   columns, `@Const(doc=..., choices=...)` parameters are bind-time constants, and the last
   unannotated vector is the output. Table functions extend `SimpleTableFunction` and return a
   `FunctionSpec` whose arguments come from `Meta.positional(...)` and `Meta.named(...)`
   (with a doc string and constraints).
3. Give it `metadata()` (or a `FunctionMetadata` in its spec) with `.withExamples(...)` and
   `.withTags(Meta.objectTags(...))`.
4. Register it in `StarterWorker.build()` (`registerScalar` / `registerTable`).

Then, for every new function:

1. **Metadata** (`src/main/java/farm/query/starter/Meta.java` and each function's `metadata()`): title, category from the schema's category list,
   keywords, `doc_llm` (for agents: what it does, arguments, when to use it) and `doc_md`
   (for humans). Describe each argument, and for table functions describe the result
   columns. Add 1–2 `examples` with fully qualified, analytical SQL
   (`starter.main.fn(...)`): project, filter or aggregate rather than `SELECT *`.
2. **Executable examples:** if the function is deterministic and needs no network,
   add an `executable_examples` entry with `ORDER BY` and an `expected_result`.
3. **SQL test:** add cases to `test/sql/starter.test`, or a new `test/sql/<name>.test`
   with the same header (`require vgi`, `require-env STARTER_WORKER`, then `ATTACH`).
   Avoid expected-error text that depends on SDK wording; use `<REGEX>:.*` for errors you
   don't control.
4. **Unit test** for any non-trivial logic.
5. **Agent task:** add a `{name, prompt}` to the catalog's agent test tasks, and its
   grader (`reference_sql` or `check_sql`) to `vgi-agent-tests.yaml`.
6. Run `make check`.

## Passing the metadata check

`make lint-explain` runs [vgi-lint](https://github.com/Query-farm/vgi-lint-check) against
the built worker with the settings in `vgi-lint.toml`. The linter isn't pinned, so new
rules arrive on their own. When it reports findings:

1. Run `make lint-explain LINT_ARGS="--format agent"`. Findings are grouped by rule:
   each rule's fix is stated once, followed by every object it applies to.
2. For a rule you don't understand, run
   `uvx --prerelease=allow --from 'vgi-lint-check>=2,<3' vgi-lint explain VGI123`.
   For tag names and value shapes, read `vgi-lint spec` rather than guessing.
3. Fix errors first, then warnings, then info. Fix the metadata in the worker, never
   the linter's settings.
4. If a finding is genuinely wrong for this worker, waive it in `vgi-lint.toml` with a
   `kind` and a `reason` (see the comments there). Never add an unexplained `ignore`.

The check executes examples (assurance level L2). `make lint-ai` adds L3: an agent must
solve the tasks in `vgi-agent-tests.yaml` using only the metadata, and the docs are
reviewed. It's optional, so run it before publishing.

## Choosing the function kind

| Need | Kind |
|---|---|
| one value per input row | scalar |
| rows from arguments (API, file, generator) | table function |
| transform the rows of a subquery | table in-out function |
| combine many rows into one value | aggregate |
| a fixed dataset referenced by name | catalog table |
| write data out to a file or service | `COPY … TO` copy function |
| accept INSERT / UPDATE / DELETE | catalog table with insert/update/delete functions (see the SDK API reference) |

Recipes for most kinds are in `examples/`.

## Sharp edges (read before writing code)

- **Never write to stdout.** stdout is the Arrow protocol channel, and one stray print
  corrupts the stream. Log to stderr. Log through SLF4J (`org.slf4j.LoggerFactory.getLogger(...)`); the bundled `slf4j-simple` backend writes to stderr. `--debug` turns on debug logging.
- **Scalars have no named arguments.** For an optional scalar argument, add an overload
  (a second function with the same SQL name and one more argument). Named arguments
  (`step := 2`) work on table functions only.
- **Declared constraints aren't a guarantee.** `choices`, `ge`, `le` and `pattern` are
  published so DuckDB and agents can discover them, but SDKs differ in which ones they
  enforce: some skip named table arguments, C# enforces none, and `pattern` matching
  differs (Java requires a full match). Where its SDK doesn't enforce a constraint, the
  starter re-checks it in bind code. Do the same, and on a columnar scalar argument validate
  values in the compute body.
- **Reserved argument names.** DuckDB keywords such as `interval`, `end`, `from`, `order`
  and `table` break `name := value` syntax. Pick another name (`start_date`, `end_date`).
- **State must be serializable.** Table-function state is a `TableProducerState` subclass with a public no-arg constructor. Over HTTP each batch can be served by a different request, so its non-transient fields travel with the request in a token the SDK encrypts (ChaCha20-Poly1305) with the worker's key. Keep them small; mark caches `transient`. The SDK gives per-batch code no access to the call's secrets, so `fetch_items` carries its token in a state field: acceptable only because of that encryption.
- **Examples must be deterministic.** Add `ORDER BY`, and no `now()`, random values or
  network. Examples that hit a live API go in `examples`, not `executable_examples`.
- **Credentials.** Read them from a DuckDB secret first (`CREATE SECRET (TYPE starter_api, …)`),
  then from an environment variable. Never put them in logs, examples or the repo, and
  keep them out of table-function state unless the state rule above says
  the SDK encrypts it. Workers that need real credentials set `execute: false` on the metadata check.
- **Errors.** Raise errors with a clear message. For HTTP failures, include the status
  code. DuckDB shows the message to the user.
- **Rate limits.** Retry 429 and 5xx with backoff, honour `Retry-After`, and set timeouts on
  every request.
- **Version pins.** Don't bump the vgi-java minor version by hand. A minor release can
  change the wire protocol, which must match the DuckDB extension exactly. Template
  updates (`make template-diff`) carry tested bumps.

## Language notes (Java)

- Scalars receive whole Arrow vectors. Size the output with `result.allocateNew(rows)`, write
  each row with `setSafe` (or `setNull`), and finish with `result.setValueCount(rows)`.
- Table functions: emit one `VectorSchemaRoot` per `produceTick` call and call `out.finish()`
  when done. `out.emit(root)` takes ownership of the root, so never emit an input root you don't own.
- Build output batches from the projected schema the producer received
  (`TableInitParams.outputSchema()`): DuckDB may ask for only some columns.
- Use `Meta.column(name, type, comment, nullable)` for output fields. The comment becomes the
  column description in DuckDB and agent tools.
- Declared constraints (`ge`/`le`/`choices`/`pattern`) are enforced at bind time on positional
  and named arguments (vgi-java 0.46.1+), so `onBind` doesn't need to re-check them. `pattern`
  is an unanchored search: anchor with `^`/`$`.
- A table function's state has no access to the call's secrets after `createProducer`, so
  `FetchItems` resolves its token there and keeps it in the state. Over HTTP that state leaves the
  worker encrypted (ChaCha20-Poly1305), so this is safe in Java; never log it. The encryption key
  is random per process unless `VGI_SIGNING_KEY` is set: give every HTTP replica the same
  `VGI_SIGNING_KEY` so any replica, or a restarted one, can resume another's scan.
- The worker runs on the JVM, which starts slowly: attach with `LOCATION 'launch:/abs/path/bin/vgi-starter-worker'`
  in production so DuckDB keeps a pooled worker instead of spawning a JVM per connection.
- Dependencies go in `build.gradle.kts`; refresh the lockfile with
  `./gradlew dependencies --write-locks`. Keep them few: every dependency ships in the jar.
- Gates: `spotlessCheck` (google-java-format), `javac -Xlint:all -Werror`, and `./gradlew test`.

## Useful prompts

- "Add a scalar `slugify(text VARCHAR) -> VARCHAR` with full metadata and tests."
- "Replace `fetch_items` with a table function for <API>. Map its JSON to typed columns,
  read the token from a `starter_api` secret, and add mock-server tests."
- "Add a catalog table `countries` (code, name) with a primary key, following
  `examples/catalog_tables`."
