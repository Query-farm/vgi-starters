# The starter contract

Every `vgi-starter-<lang>` tree implements exactly this. The Python tree (`python/`)
is the reference implementation: when in doubt, do what it does.

## 1. Worker behaviour

| Item | Requirement |
|---|---|
| Catalog | name `starter`, one schema `main` |
| `greet(name VARCHAR) -> VARCHAR` | `'Hi, ' \|\| name \|\| '!'`; NULL → NULL; vectorized |
| `greet(name VARCHAR, style VARCHAR) -> VARCHAR` | overload; `style` is a **constant** param with choices `['casual','formal']`, enforced at bind; formal → `'Good day, ' \|\| name \|\| '.'` |
| `sequence_table(count BIGINT, step BIGINT := 1)` | returns `(n BIGINT NOT NULL, squared BIGINT NOT NULL)`, `n = i*step` for `i in 0..count-1`; `count` constrained `ge 0, le 1000000`; `step` named, default 1, `ge 1`; emits ≤10,000 rows per batch with serializable cursor state (or the SDK's equivalent) |
| `fetch_items(base_url VARCHAR, page_size BIGINT := 100)` | GET `<base_url without trailing />/items?page=N&page_size=M`; body `{"items":[{id,name,category,price}], "next_page": N+1 or null}`; returns `(id BIGINT NOT NULL, name VARCHAR, category VARCHAR, price DOUBLE)`; one page per emitted batch; `base_url` must match `^https?://`; `page_size` named, default 100, `ge 1, le 1000`; 30 s timeout; retry 429/5xx up to 4 attempts honouring integer `Retry-After`; error message includes the HTTP status code (e.g. `HTTP 401`) |
| Secret type | `starter_api` with one redacted VARCHAR field `token`; `fetch_items` sends `Authorization: Bearer <token>` from the secret, else from env `STARTER_API_TOKEN`; never stored in state |
| CLI | no args → stdio; `--http --port 0 [--host H]` → HTTP, printing `PORT:<n>` on **stdout** once listening; `GET /health` → 200; `--unix PATH` if the SDK supports it |
| Logging | stderr only, never stdout |

## 2. Metadata (vgi-lint-check 2.x unpinned, `fail_on = "info"`, execute on → score 100)

Mirror `python/src/starter_worker/meta.py` and each function's `Meta`:

- **Catalog tags:**
  - `vgi.title`, `vgi.doc_llm`, `vgi.doc_md` (≥300 characters, multiple paragraphs)
  - `vgi.source_url` = `https://github.com/Query-farm/vgi-starter-<lang>`
  - `vgi.icon_url`
  - `vgi.license` = `0BSD` (vgi-lint's SPDX list lacks MIT-0)
  - `vgi.author`, `vgi.copyright`, `vgi.keywords` (JSON array)
  - `vgi.support_contact`, `vgi.support_policy_url`
  - `vgi.agent_test_tasks` (JSON array of `{name, prompt}`, the same 4 tasks as Python)
  - catalog `source_url` field if the SDK has one
- **Schema tags:**
  - `vgi.title`, `domain` = `examples`, `vgi.keywords`
  - `vgi.doc_llm` (detailed), `vgi.doc_md`
  - `vgi.categories` (JSON: text, generators, api)
  - `vgi.example_queries`
- **Each function:**
  - `vgi.title`, `vgi.category`, `vgi.keywords`, `vgi.doc_llm`, `vgi.doc_md`
  - examples with descriptions, also published as `vgi.example_queries`
  - per-argument docs
  - `vgi.result_columns_schema` for table functions
  - `vgi.executable_examples` (with `expected_result`) on `sequence_table`
  - output column comments
- **No SQL in inline code spans** in doc_md/doc_llm. Use a ```` ```sql ```` fence.
- **No bare `SELECT *` examples.**
- **`vgi-agent-tests.yaml`:** rendered from `shared/`.

## 3. Repository layout and tooling

- **Shared files:** rendered by `tools/render.py` from `shared/`. Never edit them in a language tree:
  - `README.md`, `AGENTS.md`, `CLAUDE.md`, `LICENSE`
  - `ci/run-integration.sh`, `scripts/vgi_template.py`
  - `test/sql/starter.test`, `test/mock_api/server.py`
  - `examples/test/recipes.test`, `examples/README.md`, `vgi-agent-tests.yaml`, `vgi-lint.toml` (from `lint_location`)
- **Language variables:** `langs/<lang>.toml` + `langs/<lang>/{layout,add_function,idioms}.md` feed the templates. Copy the Python ones' keys exactly.
- **`Makefile` targets** (same names and semantics as Python):
  - `help` `init` (template-only block) `build` `tools`
  - `fmt` `lint` `test-unit` `test-sql` `test-sql-all` `test`
  - `lint-explain` `lint-ai` `check` `run` `run-http` `debug`
  - `template-diff` `clean`

  `HAYBARN_VERSION ?= 2.0.0rc11`. vgi-lint is **not** pinned (`vgi-lint-check>=2,<3`, per vgi-lint-check's policy); its settings live in the rendered `vgi-lint.toml`. Tools install into `.tools/` (never global).
- **`test-sql`:** calls `ci/run-integration.sh` with:
  - `WORKER` = absolute path of a single executable. Use a small wrapper script in `bin/` if the runtime needs one, e.g. `bun run …`, `java -jar …`.
  - `RECIPES_WORKER` = a command that runs a recipe by name.
  - `HAYBARN_UNITTEST`, `TRANSPORT`, and `PYTHON` (any python3; `uv run --no-project python` works).
- **`.vgi-starter.json`:**
  - `template`, `lang`, `template_version` `0.1.0` (set by publish; `template-diff` compares against that tag)
  - `post_init` commands that regenerate lockfiles after the rename **without upgrading anything**:
    the SDK version the lockfile pins must survive `make init` (no `--force-evaluate` or
    `--write-locks` against a floating range; template-init CI compares the pin before and after)
  - `template_only_files` `[".github/workflows/template-init.yml"]`
  - `optional_files`
- **`scripts/vgi_template.py init <name>`** must yield a tree that passes `make check` with no leftover word `starter` except "starter template". It renames:
  - `vgi-starter-worker`, `starter_worker`, `starter-worker`, `StarterWorker`, `Starter`, `starter`
  - `STARTER_`, `starter_api`
  - the paths containing them, including the launchers in a top-level `bin/`, also when the
    copy is not a git checkout

  Name identifiers so these rules produce valid code (e.g. a Go module path, a C# namespace `Starter.Worker`, a Java package `farm.query.starter`).
- **Recipes** (`examples/`), all served by one dispatcher:

  | Recipe | Shows |
  |---|---|
  | `transform_rows` | `positive_only(TABLE)` table in-out, keeps `value > 0` |
  | `aggregate` | `int_sum(BIGINT)` |
  | `pushdown_sqlite` | `orders()` with filter + projection pushdown into SQLite, the deterministic 2000-row db Python builds |
  | `cache_expensive_call` | `exchange_rates()` with TTL + ETag |
  | `catalog_tables` | table `countries` with a NOT NULL + PRIMARY KEY constraint |
  | `copy_to_sink` | COPY TO format `jsonl_out` |

  If an SDK lacks a capability, skip that recipe and say so in `langs/<lang>/idioms.md`. CI must still be green, so remove that section from your copy of the test.
- **Docker:** `Dockerfile` (HTTP by default on `$PORT`=8000, `stdio` argument, non-root, `/health` healthcheck), `docker-entrypoint.sh`, `.dockerignore`.
- **`ci/check-version.sh <tag>`:** checks the tag against the version file.
- **`.github/workflows/`:**
  - `ci.yml`, mirroring Python's jobs: `quality`, `integration` matrix (`{ubuntu, macos, windows} × {stdio, launch, http}`, no windows+launch), `metadata` (vgi-lint-check@v2, unpinned, `fail-on` read from `vgi-lint.toml`), `docker`, plus the template-only/fork-only markers
  - `release.yml` (vgi-actions `<lang>-release.yml@v1` where one exists)
  - `docker-publish.yml`, `nightly.yml`, `template-init.yml`, `.github/dependabot.yml` (ignoring SDK minor/major)
  - All must pass `actionlint`.
- **SDK dependency:** use the published registry release only, never a path or git dependency. Pin to the minor version:

  | Language | SDK |
  |---|---|
  | TS | `@query-farm/vgi` 0.48.x |
  | Go | `github.com/Query-farm/vgi-go` v0.40.x |
  | Rust | `vgi` 0.49.x |
  | Java | `farm.query:vgi` 0.46.x |
  | C# | `QueryFarm.Vgi` 0.24.x |
  | Python | `vgi-python` 0.49.x |

## 3a. Rules learned while building the trees

- **Examples call the hosted demo API.** vgi-lint executes examples, so `fetch_items`'
  examples use `https://vgi-starter-api.query-farm.services/v1` (source in `demo-api/`, same contract as the test
  mock; `/v1` is frozen). The local mock is only for `ci/run-integration.sh`, on a random port.
- **`post_init` runs the formatter.** The rename lengthens identifiers, which can break the
  format check.
- **Leave the package name out of lockfiles where possible.** For example, Bun records
  `package.json`'s `name` in `bun.lock`, and the rename skips lockfiles.
- **No class-body annotations the SDK may evaluate at runtime.** Python found
  `pa.Field[Any]` in a class body crashes, because the SDK evaluates annotations.

## 4. Done means

1. `make check` passes in `<lang>/`:
   - lint and unit tests
   - test-sql over stdio, launch and http (core + recipes)
   - vgi-lint 100 / no findings
2. `python3 tools/render.py --check` reports no drift.
3. Copy the tree to a temp dir, `git init && git add -A && git commit`, run `make init NAME=acme_demo`, and then:
   - `git grep -n -I -i -w starter -- . ':!scripts/vgi_template.py' ':!.vgi-starter.json' | grep -vi 'starter template'` prints nothing
   - `make check` passes
4. `actionlint <lang>/.github/workflows/*.yml` is clean.
