# Common tasks. `make help` lists them. Requires a JDK 25+ and uv (https://docs.astral.sh/uv/).
# managed-by: vgi-starters (language section below is java-specific)

HAYBARN_VERSION ?= 2.0.0rc11
# vgi-lint is deliberately not pinned: the gate tracks the current quality bar.
VGI_LINT        := uvx --prerelease=allow --from 'vgi-lint-check>=2,<3' vgi-lint lint
LINT_ARGS       ?=
WORKER          := $(CURDIR)/bin/vgi-starter-worker
RECIPES_WORKER  := $(CURDIR)/bin/recipes
TOOLS           := $(CURDIR)/.tools
HAYBARN_UNITTEST := $(TOOLS)/bin/haybarn-unittest
TRANSPORT       ?= stdio
GRADLE          ?= ./gradlew --console=plain

.DEFAULT_GOAL := help
.PHONY: help build test test-unit test-sql test-sql-all lint lint-explain lint-ai fmt check run run-http debug tools template-diff clean
# template-only:start
.PHONY: init
# template-only:end

help: ## List the targets
	@awk 'BEGIN{FS=":.*## "} /^[a-zA-Z_-]+:.*## /{printf "  %-14s %s\n",$$1,$$2}' $(MAKEFILE_LIST)

# template-only:start
init: ## Rename this template: make init NAME=weather
	@test -n "$(NAME)" || { echo "usage: make init NAME=<snake_case_name>"; exit 2; }
	uv run --no-project python scripts/vgi_template.py init $(NAME)
# template-only:end

build: ## Build the worker jar and the recipes jar (build/libs, build/recipes)
	$(GRADLE) shadowJar recipesJar

tools: ## Install the pinned SQL test runner into .tools
	@test -x $(HAYBARN_UNITTEST) || { uv venv -q $(TOOLS) && uv pip install -q -p $(TOOLS) haybarn-unittest==$(HAYBARN_VERSION); }

fmt: ## Format the code (google-java-format)
	$(GRADLE) spotlessApply

lint: ## Static checks: formatting, javac -Xlint:all -Werror
	$(GRADLE) spotlessCheck compileJava compileTestJava compileExamplesJava

test-unit: ## Unit tests (JUnit)
	$(GRADLE) test

test-sql: build tools ## SQL tests through DuckDB: make test-sql TRANSPORT=stdio|launch|http
	WORKER=$(WORKER) RECIPES_WORKER="$(RECIPES_WORKER)" HAYBARN_UNITTEST=$(HAYBARN_UNITTEST) TRANSPORT=$(TRANSPORT) PYTHON="uv run --no-project python" ci/run-integration.sh

test-sql-all: ## SQL tests over every transport
	$(MAKE) test-sql TRANSPORT=stdio
	$(MAKE) test-sql TRANSPORT=launch
	$(MAKE) test-sql TRANSPORT=http

test: test-unit test-sql ## Unit + SQL tests

lint-explain: build ## Metadata quality report (vgi-lint, settings in vgi-lint.toml). LINT_ARGS="--format agent" for agents
	$(VGI_LINT) "$(WORKER)" $(LINT_ARGS)

lint-ai: ## Opt-in L3 check: an agent runs vgi-agent-tests.yaml and docs are reviewed (needs the claude CLI)
	@$(MAKE) --no-print-directory lint-explain LINT_ARGS="--ai $(LINT_ARGS)"

check: lint test-unit test-sql-all lint-explain ## Everything CI runs. Done = this passes.

run: build ## Serve over stdio (what DuckDB launches)
	$(WORKER)

run-http: build ## Serve over HTTP on port 8000
	$(WORKER) --http --host 127.0.0.1 --port 8000

debug: build tools ## Run one query against the worker with debug logging on stderr
	@printf "LOAD vgi;\nATTACH 'starter' (TYPE vgi, LOCATION '$(WORKER) --debug');\nSELECT starter.main.greet('debug');\n" \
	  | uvx --from haybarn-cli==$(HAYBARN_VERSION) haybarn -no-agent

template-diff: ## Show upstream template changes since this project was created
	uv run --no-project python scripts/vgi_template.py template-diff --stat

clean: ## Remove build outputs and tool caches
	rm -rf build .gradle .tools
