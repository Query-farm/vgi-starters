<p align="center">
  <img src="https://query.farm/media-kit/products/vgi/vgi-logo.png" alt="Vector Gateway Interface (VGI)" width="320">
</p>

# VGI worker starter templates

Source for the [VGI](https://query.farm/vgi) worker starter templates, one per language.
Each is published as its own GitHub **template repository**:

| Language | Template | SDK |
|---|---|---|
| Python | [vgi-starter-python](https://github.com/Query-farm/vgi-starter-python) | [vgi-python](https://github.com/Query-farm/vgi-python) |
| TypeScript | [vgi-starter-typescript](https://github.com/Query-farm/vgi-starter-typescript) | [vgi-typescript](https://github.com/Query-farm/vgi-typescript) |
| Go | [vgi-starter-go](https://github.com/Query-farm/vgi-starter-go) | [vgi-go](https://github.com/Query-farm/vgi-go) |
| Rust | [vgi-starter-rust](https://github.com/Query-farm/vgi-starter-rust) | [vgi-rust](https://github.com/Query-farm/vgi-rust) |
| Java | [vgi-starter-java](https://github.com/Query-farm/vgi-starter-java) | [vgi-java](https://github.com/Query-farm/vgi-java) |
| C# | [vgi-starter-csharp](https://github.com/Query-farm/vgi-starter-csharp) | [vgi-csharp](https://github.com/Query-farm/vgi-csharp) |

**Want to build a worker?** Use one of the template repositories above, not this repo.

```bash
gh repo create my-org/weather-worker --template Query-farm/vgi-starter-python --private --clone
cd weather-worker && make init NAME=weather && make check
```

## What every starter contains

The same worker in every language, specified in [CONTRACT.md](CONTRACT.md):

- `greet`: a vectorized scalar function with an overload and a constrained constant argument.
- `sequence_table`: a streaming table function with a named argument.
- `fetch_items`: a REST API reader with pagination, retries and secrets.
- Recipes for table in-out functions, aggregates, filter pushdown, caching, catalog tables
  and `COPY TO`.
- One SQL test suite, run through DuckDB over the stdio, launch and HTTP transports.
- A metadata score of 100/100 on [vgi-lint](https://github.com/Query-farm/vgi-lint-check).
- `AGENTS.md` for coding agents, a branded README, Docker, signed releases, nightly CI.
- `make init NAME=…` to rename, and `make template-diff` to pick up later template changes.

## Working on the templates

```
shared/          files identical across languages (README/AGENTS templates, SQL tests,
                 mock API, integration harness, init tool). Rendered into each tree.
langs/<lang>.*   per-language variables and snippets for the templates
<lang>/          the complete, buildable starter for that language
tools/render.py  shared/ -> <lang>/ (CI runs --check; never edit rendered files)
tools/bump.py    move version pins (SDK, Haybarn, vgi-lint) across trees
CONTRACT.md      what every starter must implement
```

1. Edit `shared/` or `langs/`, or a language tree's own code.
2. `python3 tools/render.py`
3. `make -C <lang> check`

CI runs `make check` and the template-init test for every language.

**Publishing** runs from a maintainer's machine over SSH. Commit, then:

```bash
tools/publish.sh --dry-run                # build the template commits, push nothing
tools/publish.sh                          # push each tree to Query-farm/vgi-starter-<lang> main
tools/publish.sh --tag v1.1.0             # also stamp .vgi-starter.json and tag (forks diff against tags)
tools/publish.sh --tag v1.1.0 go rust     # only some languages
```

Each publish is one snapshot commit per template repo, built from committed files only.
Tag vgi-starters with the same version.

**Version bumps:** run `tools/bump.py` only after the DuckDB `vgi` extension supporting
the new protocol is published. An SDK minor release can change the wire protocol, and
protocol versions must match exactly.

## License

[0BSD](shared/LICENSE). Templates are meant to be copied with no strings attached.
