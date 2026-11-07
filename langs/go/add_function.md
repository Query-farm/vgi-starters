1. Create `internal/starter/<name>.go`. Copy the closest example: `greet.go` for a
   scalar, `sequence.go` for a generator, `fetch_items.go` for an API.
2. Declare arguments as a struct with `vgi:"..."` tags. Use `pos=N` for positional
   arguments and `default=V` (no `pos`) for named ones; add `ge=`, `le=`, `pattern=` or
   `choices='a,b'` constraints. Scalar column arguments are `*array.String` (etc.) fields
   with `const=false`. `doc=` must be the last key in the tag.
3. Implement `Name()`, `Metadata()` (with `Description`, `Tags: withExamples(objectTags(...), examples)`
   and `Examples`), then `OnBindTyped`/`ProcessTyped` (scalar, wrap with
   `vgi.AsScalarFunction[Args]`) or `ArgumentSpecs`/`OnBind`/`NewState`/`Process` (table,
   wrap with `vgi.AsTableFunction[State]`).
4. Register it in `NewWorker` in `internal/starter/worker.go`, and add its examples to
   `allExamples`.
