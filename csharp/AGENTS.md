<!-- managed-by: vgi-starters (edit upstream in shared/AGENTS.md.tmpl, then run tools/render.py) -->
# AGENTS.md: working on this VGI worker

This repository is a **VGI worker** written in C#. DuckDB starts it (or connects
to it over HTTP) and calls its functions from SQL; data moves as Apache Arrow
batches. Background reading, in order of usefulness:

1. https://query.farm/vgi/llms.txt — index of all VGI docs, written for agents
2. https://query.farm/vgi/docs/csharp/ — the C# SDK (QueryFarm.Vgi)
3. https://query.farm/vgi/building-a-worker — concepts: catalogs, schemas, function kinds

## Definition of done

**`make check` passes.** It runs formatting and static checks, unit tests, the SQL
tests over the stdio, launch and http transports, and the metadata quality check
(vgi-lint, at the `fail_on` level in `vgi-lint.toml`). Don't hand work back until it passes. If a
check is wrong, fix the check and say why. Never delete a test to get green.

Faster loops while iterating: `make test-unit`, `make test-sql`, `make lint-explain`.

## Layout

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

## Recipe: add a function

1. Create `src/Starter.Worker/<Name>.cs`. Copy the closest example: `Greet.cs` for a
   scalar, `SequenceTable.cs` for a generator, `FetchItems.cs` for an API.
2. Scalars subclass `ScalarFn` and declare a private `Compute` method whose parameters
   carry `[Param(Doc = ...)]` (columns) or `[ConstParam(Doc = ...)]` (constants), with
   the output builder last. Table functions implement `ITableFunction`: declare
   arguments with `Meta.Argument(...)` (`named: true` plus `ParameterDefaultValues`
   for named ones), validate them in `Bind`, and return an `ITableFunctionProducer`
   from `CreateProducer`.
3. Give the class `Tags` built with `Meta.ObjectTags(...)` (plus
   `vgi.result_columns_schema` for table functions), and `Examples`.
4. Register it in `StarterWorker.Build()` (`RegisterScalar` / `RegisterTable`).

Then, for every new function:

1. **Metadata** (`src/Starter.Worker/Meta.cs` and each function's `Tags` and `Examples`): title, category from the schema's category list,
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
| accept INSERT / UPDATE / DELETE | writable catalog table: `CatalogTable.InsertFunction` / `UpdateFunction` / `DeleteFunction` |

Recipes for most kinds are in `examples/`. Writable tables have no recipe:

A writable table is a `CatalogTable` that names an `ITableInOutFunction` per operation in
`InsertFunction`, `UpdateFunction` and `DeleteFunction`, with the richest result each one
produces in `WriteResultModes`. `QueryFarm.Vgi.Catalog.WriteResults` and `WriteRequest.From`
read DuckDB's `write_options` argument and build the result (`WriteResults.Schema`,
`WriteResults.Batch`); UPDATE and DELETE need a `WriteResults.RowIdField()` column, named in
`CatalogTable.RowIdColumn` and emitted by a scan with projection pushdown. Transactions:
return `AttachContext { SupportsTransactions = … }` from `Worker.OnAttach` and pass a
`TransactionHooks` subclass to `Worker.TransactionHooks`. The `WriteResults` helpers and
`TransactionHooks` need QueryFarm.Vgi 0.24.2 or newer: if `packages.lock.json` has an older
0.24.x, run `make lock` (the newest 0.24.x; every 0.24.x speaks the same protocol). See
[Writable tables](https://github.com/Query-farm/vgi-csharp#writable-tables) in the SDK README.

## Sharp edges (read before writing code)

- **Never write to stdout.** stdout is the Arrow protocol channel, and one stray print
  corrupts the stream. Log to stderr. Write diagnostics with `Console.Error.WriteLine` (or an `ILogger` writing to stderr); `--debug` or `--log-level Debug` turns up the HTTP host's logging, which `Program.cs` routes to stderr.
- **Scalars have no named arguments.** For an optional scalar argument, add an overload
  (a second function with the same SQL name and one more argument). Named arguments
  (`step := 2`) work on table functions only.
- **Declared constraints are enforced at bind.** `choices`, `ge`, `le` and `pattern` on a
  constant argument are published so DuckDB and agents can discover them, and the SDK
  (QueryFarm.Vgi) rejects a violating value when the query is bound, before your code runs, for
  positional and named arguments alike. `pattern` is an unanchored search (JSON Schema
  semantics) in every SDK: write `^https?://`, not `https?://`. Constraints cover constant
  arguments only: validate the values of a columnar scalar argument in the compute body.
- **Reserved argument names.** DuckDB keywords such as `interval`, `end`, `from`, `order`
  and `table` break `name := value` syntax. Pick another name (`start_date`, `end_date`).
- **State must be serializable.** A table function's cursor lives on its `ITableFunctionProducer`, which the SDK keeps in the worker process for the whole call (over HTTP too), so plain fields work. Keep it small anyway, and never send secrets back to DuckDB in batch metadata.
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
- **Version pins.** Don't bump the QueryFarm.Vgi minor version by hand. A minor release can
  change the wire protocol, which must match the DuckDB extension exactly. Template
  updates (`make template-diff`) carry tested bumps.

## Language notes (C#)

- Scalars receive whole Arrow arrays. Loop over the rows once and append to the output
  builder; check `IsNull(row)` first.
- `ScalarFn` finds `Compute` by reflection; it may be static. Override `Tags`,
  `Examples` and `Categories` on the subclass.
- Declare choices on the constant itself:
  `[ConstParam(Doc = "...", Choices = ["casual", "formal"])]`. The SDK publishes them
  as `vgi_choices` and rejects other values when the query is planned. `Ge`/`Le` and
  `Pattern` work the same way.
- Table-function constraints (`ge`/`le`/`pattern`) published by `Meta.Argument` are
  enforced by the SDK at bind, for positional and named arguments alike. There is no
  need to re-check them in `Bind`.
- Read a table function's arguments with `params.Arguments.Int64(0)`,
  `StringPositional(0)` and `Int64Named("step", 1)`.
- Read secrets in `CreateProducer` with
  `initParams.SecretValues.FindByType("starter_api")?.GetString("token")`, after
  declaring them in `RequiredSecrets`.
- Emit `new RecordBatch(initParams.OutputSchema, ...)` so column types match what was
  bound. Call `output.Finish()` when done.
- Use `Meta.Column(name, type, comment)` for output fields. The comment becomes the
  column description in DuckDB and agent tools.
- Dependencies go in the `.csproj`, with versions in `Directory.Packages.props`. Run
  `make lock` after changing them. Keep them few: every dependency ships to every user
  of the worker.
- Gates: `dotnet format --verify-no-changes`, `dotnet build -warnaserror` (with
  the recommended analyzers), `dotnet test` (xunit).
- The `pushdown_sqlite` recipe reads pushed-down filters from `initParams.Filters`
  (a `FilterSet`) and turns the simple ones into SQL.

## Useful prompts

- "Add a scalar `slugify(text VARCHAR) -> VARCHAR` with full metadata and tests."
- "Replace `fetch_items` with a table function for <API>. Map its JSON to typed columns,
  read the token from a `starter_api` secret, and add mock-server tests."
- "Add a catalog table `countries` (code, name) with a primary key, following
  `examples/catalog_tables`."
