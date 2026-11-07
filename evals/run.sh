#!/usr/bin/env bash
# Agent-usability eval: can a coding agent extend a fresh starter using only AGENTS.md?
#
#   evals/run.sh python            # all prompts for one language
#   evals/run.sh python add_scalar # one prompt
#
# For each prompt: copy the tree, `make init NAME=evalworker`, run Claude Code headless
# with the prompt, then score `make check` (pass/fail) and record the agent's turn count
# and duration. Results go to evals/results/<lang>-<date>.tsv. This spends real agent
# tokens: run it before a release, not on every push.
set -euo pipefail
lang="${1:?usage: run.sh <lang> [prompt-name]}"; only="${2:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
out="$ROOT/evals/results/$lang-$(date +%F).tsv"; mkdir -p "$(dirname "$out")"
command -v claude >/dev/null || { echo "Claude Code CLI (claude) not found"; exit 1; }
while IFS=$'\t' read -r name prompt; do
  [ -n "$name" ] && { [ -z "$only" ] || [ "$only" = "$name" ]; } || continue
  work="$(mktemp -d)/evalworker"; cp -R "$ROOT/$lang" "$work"; cd "$work"
  git init -q && git add -A && git -c user.email=e@e -c user.name=e commit -qm t
  make init NAME=evalworker >/dev/null
  start=$(date +%s)
  claude -p "$prompt" --permission-mode acceptEdits --allowedTools "Bash,Read,Edit,Write" \
    --output-format json > agent.json 2> agent.err || true
  secs=$(( $(date +%s) - start ))
  if make check > check.log 2>&1; then result=pass; else result=fail; fi
  turns=$(python3 -c "import json;print(json.load(open('agent.json')).get('num_turns','?'))" 2>/dev/null || echo "?")
  printf '%s\t%s\t%s\t%ss\t%s\n' "$lang" "$name" "$result" "$secs" "turns=$turns" | tee -a "$out"
  echo "  workdir: $work"
done < "$ROOT/evals/prompts.txt"
