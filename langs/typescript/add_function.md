1. Create `src/<name>.ts`. Copy the closest example: `greet.ts` for a scalar,
   `sequence.ts` for a generator, `fetch_items.ts` for an API.
2. Declare arguments: `params` + `argDocs` (scalars), or the ordered `parameters`
   list when a constant needs `choices`/`ge`/`le`. Table functions use `args`,
   `argDocs` and `argConstraints`; an argument listed in `argDefaults` becomes a
   named argument (`step := 2`), the others are positional.
3. Give it `description`, `tags: objectTags(...)` (from `src/meta.ts`) and
   `examples`.
4. Add it to `FUNCTIONS` in `src/catalog.ts`.
