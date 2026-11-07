- Scalars receive whole Arrow arrays. Use `pyarrow.compute` (`pc.*`) instead of
  per-row Python loops wherever you can.
- Table functions: `@init_single_worker` for sources that can't be split, plus
  `@bind_fixed_schema` with a `FIXED_SCHEMA` when the output columns don't depend on
  arguments. Otherwise implement `on_bind` and return `BindResponse(output_schema=...)`.
- Emit `pa.RecordBatch.from_pydict(..., schema=params.output_schema)` so column types
  match what was bound. Call `out.finish()` when done.
- Use `column(name, type, comment)` from `meta.py` for output fields. The comment
  becomes the column description in DuckDB and agent tools.
- Dependencies go in `pyproject.toml` `[project.dependencies]`; run `uv lock`. Keep
  them few: every dependency ships to every user of the worker.
- Gates: `ruff format`, `ruff check`, `mypy --strict`, `pydoclint`.
