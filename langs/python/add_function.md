1. Create `src/starter_worker/<name>.py`. Copy the closest example: `greet.py` for a
   scalar, `sequence.py` for a generator, `fetch_items.py` for an API.
2. Declare arguments with `Annotated[..., Param(...)]`/`ConstParam(...)` (scalars) or a
   frozen dataclass of `Annotated[..., Arg(...)]` (table functions). Use `Arg(0, ...)`
   for positional arguments and `Arg("name", default=...)` for named ones.
3. Give the class a `class Meta` with `name`, `description`, `tags=object_tags(...)`
   and `examples`.
4. Add the class to `FUNCTIONS` in `src/starter_worker/worker.py`.
