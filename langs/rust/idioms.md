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
