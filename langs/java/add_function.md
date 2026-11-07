1. Create `src/main/java/farm/query/starter/<Name>.java`. Copy the closest example:
   `Greet.java` for a scalar, `SequenceTable.java` for a generator, `FetchItems.java` for an API.
2. Scalars extend `ScalarFn` and declare one `compute(...)` method: `@Vector` parameters are
   columns, `@Const(doc=..., choices=...)` parameters are bind-time constants, and the last
   unannotated vector is the output. Table functions extend `SimpleTableFunction` and return a
   `FunctionSpec` whose arguments come from `Meta.positional(...)` and `Meta.named(...)`
   (with a doc string and constraints).
3. Give it `metadata()` (or a `FunctionMetadata` in its spec) with `.withExamples(...)` and
   `.withTags(Meta.objectTags(...))`.
4. Register it in `StarterWorker.build()` (`registerScalar` / `registerTable`).
