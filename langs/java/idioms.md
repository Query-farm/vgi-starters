- Scalars receive whole Arrow vectors. Size the output with `result.allocateNew(rows)`, write
  each row with `setSafe` (or `setNull`), and finish with `result.setValueCount(rows)`.
- Table functions: emit one `VectorSchemaRoot` per `produceTick` call and call `out.finish()`
  when done. `out.emit(root)` takes ownership of the root, so never emit an input root you don't own.
- Build output batches from the projected schema the producer received
  (`TableInitParams.outputSchema()`): DuckDB may ask for only some columns.
- Use `Meta.column(name, type, comment, nullable)` for output fields. The comment becomes the
  column description in DuckDB and agent tools.
- Declared constraints (`ge`/`le`/`choices`/`pattern`) are enforced at bind time on positional
  and named arguments (vgi-java 0.46.1+), so `onBind` doesn't need to re-check them. `pattern`
  is an unanchored search: anchor with `^`/`$`.
- A table function's state has no access to the call's secrets after `createProducer`, so
  `FetchItems` resolves its token there and keeps it in the state. Over HTTP that state leaves the
  worker encrypted (ChaCha20-Poly1305), so this is safe in Java; never log it. The encryption key
  is random per process unless `VGI_SIGNING_KEY` is set: give every HTTP replica the same
  `VGI_SIGNING_KEY` so any replica, or a restarted one, can resume another's scan.
- The worker runs on the JVM, which starts slowly: attach with `LOCATION 'launch:/abs/path/bin/vgi-starter-worker'`
  in production so DuckDB keeps a pooled worker instead of spawning a JVM per connection.
- Dependencies go in `build.gradle.kts`; refresh the lockfile with
  `./gradlew dependencies --write-locks`. Keep them few: every dependency ships in the jar.
- Gates: `spotlessCheck` (google-java-format), `javac -Xlint:all -Werror`, and `./gradlew test`.
