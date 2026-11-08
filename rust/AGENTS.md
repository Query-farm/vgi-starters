<!-- managed-by: vgi-starters (edit upstream in shared/AGENTS.md.tmpl, then run tools/render.py) -->
# AGENTS.md: working on this VGI worker

This repository is a **VGI worker** written in Rust. DuckDB starts it (or connects
to it over HTTP) and calls its functions from SQL; data moves as Apache Arrow
batches. Background reading, in order of usefulness:

1. https://query.farm/vgi/llms.txt — index of all VGI docs, written for agents
2. https://query.farm/vgi/docs/rust/ — the Rust SDK (vgi)
3. https://query.farm/vgi/building-a-worker — concepts: catalogs, schemas, function kinds

## Definition of done

**`make check` passes.** It runs formatting and static checks, unit tests, the SQL
tests over the stdio, launch and http transports, and the metadata quality check
(vgi-lint, at the `fail_on` level in `vgi-lint.toml`). Don't hand work back until it passes. If a
check is wrong, fix the check and say why. Never delete a test to get green.

Faster loops while iterating: `make test-unit`, `make test-sql`, `make lint-explain`.

## Layout

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

## Recipe: add a function

1. Create `src/<name>.rs` and add `pub mod <name>;` to `src/lib.rs`. Copy the closest
   example: `greet.rs` for a scalar, `sequence.rs` for a generator, `fetch_items.rs`
   for an API.
2. Implement the trait: `vgi::ScalarFunction` (one output row per input row) or
   `vgi::table_function::TableFunction` plus a `TableProducer`. Declare arguments in
   `argument_specs()`: `ArgSpec::column(name, pos, "varchar", doc)` for per-row values,
   `ArgSpec::const_arg(name, pos, "int64", doc)` for constants, and position `-1` for a
   named table-function argument (`.with_default(..)`). Add `.with_ge`, `.with_le`,
   `.with_choices`, `.with_pattern` constraints.
3. Fill `metadata()`: `description`, `tags` from `meta::object_tags(...)` plus
   `vgi.example_queries`, and `examples`.
4. Register it in `build_worker()` in `src/lib.rs` (`worker.register_scalar(..)` or
   `worker.register_table(..)`) and add its examples to the schema-wide list there.

Then, for every new function:

1. **Metadata** (`src/meta.rs` and each function's `metadata()`): title, category from the schema's category list,
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
| accept INSERT / UPDATE / DELETE | writable catalog table: `CatTable::with_insert_function` / `with_update_function` / `with_delete_function` |

Recipes for most kinds are in `examples/`. Writable tables have no recipe:

A writable table is a `CatTable` that names a table in-out function per operation with
`with_insert_function`, `with_update_function` and `with_delete_function`. The
[`vgi::write_results`](https://docs.rs/vgi/latest/vgi/write_results/) module reads DuckDB's
`write_options` argument (`WriteOptions::from_arguments`) and builds the result
(`write_result_schema`, `write_result_batch`); UPDATE and DELETE need a `row_id_field` column
emitted by a scan with projection pushdown. Transactions: set `CatalogModel::transactions` to a
`CatalogTransactions` implementation (`supports_transactions` per ATTACH, `begin` / `commit` /
`rollback`). These need vgi 0.49.5 or newer: if `Cargo.lock` has an older 0.49.x, run
`cargo update -p vgi` (the newest 0.49.x; every 0.49.x speaks the same protocol). See
[Writable tables, transactions and attach secrets](https://github.com/Query-farm/vgi-rust#writable-tables-transactions-and-attach-secrets).

## Sharp edges (read before writing code)

- **Never write to stdout.** stdout is the Arrow protocol channel, and one stray print
  corrupts the stream. Log to stderr. Use the `log` crate macros (`log::info!`, `log::debug!`); `env_logger` sends them to stderr. `--debug` or `RUST_LOG=debug` turns them up.
- **Scalars have no named arguments.** For an optional scalar argument, add an overload
  (a second function with the same SQL name and one more argument). Named arguments
  (`step := 2`) work on table functions only.
- **Declared constraints are enforced at bind.** `choices`, `ge`, `le` and `pattern` on a
  constant argument are published so DuckDB and agents can discover them, and the SDK
  (vgi) rejects a violating value when the query is bound, before your code runs, for
  positional and named arguments alike. `pattern` is an unanchored search (JSON Schema
  semantics) in every SDK: write `^https?://`, not `https?://`. Constraints cover constant
  arguments only: validate the values of a columnar scalar argument in the compute body.
- **Reserved argument names.** DuckDB keywords such as `interval`, `end`, `from`, `order`
  and `table` break `name := value` syntax. Pick another name (`start_date`, `end_date`).
- **State must be serializable.** A table function's producer is rebuilt from the call's arguments on every HTTP request, so list the fields that advance in `vgi::resume_fields!(...)`; they travel in the continuation token. Without it a multi-batch scan fails over HTTP. Keep that state small, and never put secrets in it.
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
- **Version pins.** Don't bump the vgi minor version by hand. A minor release can
  change the wire protocol, which must match the DuckDB extension exactly. Template
  updates (`make template-diff`) carry tested bumps.

## Language notes (Rust)

- Scalars receive whole Arrow arrays (`batch.column(i)`). Build the output with one
  pass over the array (`iter().map(..).collect::<StringArray>()`) and return a batch of
  `params.output_schema`. Read constant arguments from `params.arguments.const_str(i)`.
- **Producers must be resumable to serve HTTP.** Over stdio and Unix sockets DuckDB
  ticks a producer directly, one batch per tick. Over HTTP every batch is its own
  request, and the SDK refuses a multi-batch producer that cannot serialize its
  position ("emits more than one batch but cannot serve an HTTP continuation"). List the
  advancing fields with `vgi::resume_fields!(cursor)`; for a genuinely single-batch
  producer write `fn resume_supported(&self) -> bool { false }` (it has no default).
- **Declared constraints are enforced before `on_bind`** (vgi 0.49.3+), on positional and
  named arguments, including a named argument's default. `pattern` is an unanchored
  search, so anchor it with `^`.
- Secrets: return a `SecretLookup` from `secret_lookups()`; DuckDB re-binds with any
  matching secret, which arrives in `params.secrets.of_type("starter_api")`. With no
  secret the call still binds.
- Output fields: use `meta::column(name, type, comment, nullable)`; the comment becomes
  the column description in DuckDB and agent tools.
- Errors: return `RpcError::value_error(..)` (bad input) or `RpcError::runtime_error(..)`.
  Never `panic!` in a function body.
- HTTP calls use blocking `ureq` (the SDK calls producers synchronously on every
  transport, so there is no async runtime to share).
- Dependencies go in `Cargo.toml`; `Cargo.lock` is committed and the build is
  `--locked`. Arrow crates must stay on the major version `vgi` uses (59).
- Recipes are modules of one dispatcher, `examples/run.rs` (`cargo build --release
  --example recipes`); recipe-only crates (`rusqlite`, `arrow-json`, …) are
  dev-dependencies, so they never ship in the worker binary. All six recipes are
  implemented.
- Gates: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`,
  `RUSTDOCFLAGS="-D warnings" cargo doc --no-deps`, `cargo test`.

## Useful prompts

- "Add a scalar `slugify(text VARCHAR) -> VARCHAR` with full metadata and tests."
- "Replace `fetch_items` with a table function for <API>. Map its JSON to typed columns,
  read the token from a `starter_api` secret, and add mock-server tests."
- "Add a catalog table `countries` (code, name) with a primary key, following
  `examples/catalog_tables`."
