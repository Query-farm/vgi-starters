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
