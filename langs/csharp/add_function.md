1. Create `src/Starter.Worker/<Name>.cs`. Copy the closest example: `Greet.cs` for a
   scalar, `SequenceTable.cs` for a generator, `FetchItems.cs` for an API.
2. Scalars subclass `ScalarFn` and declare a private `Compute` method whose parameters
   carry `[Param(Doc = ...)]` (columns) or `[ConstParam(Doc = ...)]` (constants), with
   the output builder last. Table functions implement `ITableFunction`: declare
   arguments with `Meta.Argument(...)` (`named: true` plus `ParameterDefaultValues`
   for named ones), validate them in `Bind`, and return an `ITableFunctionProducer`
   from `CreateProducer`.
3. Give the class `Tags` built with `Meta.ObjectTags(...)` (plus
   `vgi.result_columns_schema` for table functions), and `Examples`.
4. Register it in `StarterWorker.Build()` (`RegisterScalar` / `RegisterTable`).
