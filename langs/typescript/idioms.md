- Scalars receive a whole Arrow batch. Read argument columns by position
  (`batch.getChildAt(0)`), return one value per row (`null` for NULL), and keep the
  per-row work allocation-free.
- `BIGINT` arrives as `bigint`, never `number`; convert with `Number(...)` only when
  the value is known to fit (counts, page sizes).
- Table functions declare their output with `onBind: () => ({ outputSchema })` and
  emit `batchFromColumns(columns, params.outputSchema)`, so column types match what
  was bound (and projection pushdown is applied for you). Call `out.finish()` when done.
- Use `column(name, type, comment)` from `src/meta.ts` for output fields. The comment
  becomes the column description in DuckDB and agent tools.
- Secrets: return `lookupSecretTypes: ["<type>"]` from the first `onBind` (when
  `params.resolvedSecretsProvided` is false), then read them with
  `secretsOfType(params.secrets, "<type>")` in `process`.
- HTTP calls: global `fetch` with `AbortSignal.timeout(...)`; no HTTP client
  dependency needed.
- Dependencies go in `package.json`; run `bun install` and commit `bun.lock`. Keep
  them few: every dependency ships to every user of the worker.
- `strict` plus `noUncheckedIndexedAccess` is on (needs `@query-farm/vgi` 0.48.1+).
- Test clients: pass `int64: "bigint"` to `tableFunctionRows` so BIGINT columns are
  always `bigint`. The default returns a `number` when the value is small enough and
  a `bigint` otherwise.
- Gates: `biome format`, `biome lint`, `tsc --noEmit` (strict).
