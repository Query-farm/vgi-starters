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
