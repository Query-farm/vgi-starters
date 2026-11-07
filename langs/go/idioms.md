- Scalars receive whole Arrow columns. `vgi.MapColumn` / `vgi.MapAllColumns` build the
  output column in one pass and keep NULLs as NULLs.
- Table functions: implement `vgi.TypedTableFunc[S]` and wrap it with
  `vgi.AsTableFunction[S]`. `S` is the per-scan cursor. Over HTTP the SDK gob-encodes it
  into the continuation token after every batch, so **every field that must survive
  must be exported**. An unexported field is silently reset on the next request. Copy
  the arguments into `S` in `NewState` instead of re-parsing them in `Process`.
- Emit with `out.EmitArrays(cols, rows)` in output-schema order, `Release()` the arrays
  you built, and call `out.Finish()` (not an error) when done.
- Use `column(name, type, comment, nullable)` from `meta.go` for output fields. The
  comment becomes the column description in DuckDB and agent tools.
- **No cgo by default** (vgi-go 0.40+). The worker builds as a static ~40 MB binary
  with `CGO_ENABLED=0`. Evaluating pushed filters inside the worker (`Evaluate`, or
  `AutoApplyFilters`) needs `import _ "github.com/Query-farm/vgi-go/vgi/exprduckdb"`,
  which embeds DuckDB through cgo: then set `CGO_ENABLED=1` (Makefile, CI, Dockerfile,
  `cgo: "1"` in release.yml) and expect ~100 MB binaries. Translating filters to SQL
  yourself, as `examples/pushdown_sqlite.go` does, needs neither.
- **Table-function state fields must be exported.** vgi-go 0.40+ refuses unexported fields
  at registration (they would be lost between HTTP batches).
- **The `replace` in `go.mod` is required.** Go doesn't inherit a dependency's `replace`
  directives, and vgi-go needs a patched `github.com/google/flatbuffers` on every
  transport (dictionary fields with Arrow metadata otherwise produce IPC schemas strict
  readers reject, until google/flatbuffers#9414 ships). Keep the line, and update it
  when a template update changes it. The SDK's second `replace`, an Arrow fork, is
  needed only for the optional shared-memory transport (`VGI_RPC_SHM_SIZE_BYTES`), so this
  template leaves it out. Add it from the vgi-go README if you enable SHM.
- Recipes: all six are implemented in Go (see `examples/`).
- Dependencies go in `go.mod` (`go get`, then `go mod tidy`). Keep them few: every
  dependency ships to every user of the worker.
- Gates: `gofmt`, `go vet`, `golangci-lint` (config in `.golangci.yml`), `go test`.
