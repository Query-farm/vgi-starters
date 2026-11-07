#!/usr/bin/env bash
# Publish the language trees to their template repositories over SSH.
#
#   tools/publish.sh [--dry-run] [--tag vX.Y.Z] [lang ...]
#
# For each language (default: all six), copies the committed <lang>/ tree into
# git@github.com:Query-farm/vgi-starter-<lang> as one new commit on main. With --tag,
# it stamps the version into .vgi-starter.json and tags that commit, which is what
# `make template-diff` in a fork compares against. A missing repository is created
# private with `gh repo create`.
#
# Only committed files are published (git archive HEAD), so build outputs and local
# edits never leak. The script refuses to run on a dirty tree or with unrendered
# shared files. --dry-run builds the commits and prints what would be pushed.
set -euo pipefail

ORG=Query-farm
ALL_LANGS=(python typescript go rust java csharp)
dry_run=0
tag=""
langs=()

while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) dry_run=1 ;;
    --tag) tag="${2:?--tag needs a value like v1.0.0}"; shift ;;
    -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) echo "unknown option: $1" >&2; exit 2 ;;
    *) langs+=("$1") ;;
  esac
  shift
done
[ ${#langs[@]} -eq 0 ] && langs=("${ALL_LANGS[@]}")

if [ -n "$tag" ] && ! [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "--tag must look like v1.2.3, got '$tag'" >&2
  exit 2
fi

root="$(git rev-parse --show-toplevel)"
cd "$root"

if [ -n "$(git status --porcelain)" ]; then
  echo "refusing to publish: the working tree has uncommitted changes" >&2
  git status --short >&2
  exit 1
fi
python3 tools/render.py --check

sha="$(git rev-parse --short=12 HEAD)"
ref="${tag:-$(git rev-parse --abbrev-ref HEAD)}"
work_root="$(mktemp -d)"
trap 'rm -rf "$work_root"' EXIT

for lang in "${langs[@]}"; do
  [ -d "$lang" ] || { echo "no such language tree: $lang" >&2; exit 2; }
  repo="$ORG/vgi-starter-$lang"
  remote="git@github.com:$repo.git"
  work="$work_root/$lang"
  echo "== $repo"

  exists=1
  gh repo view "$repo" >/dev/null 2>&1 || exists=0
  if [ $exists -eq 0 ]; then
    if [ $dry_run -eq 1 ]; then
      echo "   would create private repository $repo"
    else
      gh repo create "$repo" --private \
        --description "VGI worker starter template for $lang: build DuckDB extensions as external workers" >/dev/null
      echo "   created private repository $repo"
    fi
  fi

  git init -q -b main "$work"
  if [ $exists -eq 1 ] && git -C "$work" fetch -q --depth=1 "$remote" main 2>/dev/null; then
    git -C "$work" checkout -q FETCH_HEAD
    git -C "$work" switch -q -C main
  fi
  find "$work" -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
  git archive --format=tar HEAD "$lang" | tar -x -C "$work" --strip-components=1

  if [ -n "$tag" ]; then
    python3 - "$work/.vgi-starter.json" "${tag#v}" <<'PY'
import json, sys
path, version = sys.argv[1], sys.argv[2]
with open(path) as f:
    data = json.load(f)
data["template_version"] = version
with open(path, "w") as f:
    f.write(json.dumps(data, indent=2) + "\n")
PY
  fi

  git -C "$work" add -A
  if git -C "$work" diff --cached --quiet; then
    echo "   no changes since the last publish"
  else
    git -C "$work" commit -q -m "vgi-starters $ref ($sha)"
    echo "   commit: $(git -C "$work" log --oneline -1)"
  fi
  [ -n "$tag" ] && git -C "$work" tag "$tag"

  if [ $dry_run -eq 1 ]; then
    echo "   dry run: would push main${tag:+ and $tag} to $remote"
    continue
  fi
  git -C "$work" push -q "$remote" main
  [ -n "$tag" ] && git -C "$work" push -q "$remote" "refs/tags/$tag"
  echo "   pushed main${tag:+ and $tag}"
done
