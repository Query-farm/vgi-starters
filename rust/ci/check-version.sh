#!/usr/bin/env bash
# Fail unless the release tag (e.g. v0.1.0) matches the package version in Cargo.toml.
set -euo pipefail
tag="${1:?usage: check-version.sh <tag>}"
want="${tag#v}"
have="$(sed -n -E 's/^version = "([^"]+)"/\1/p' Cargo.toml | head -1)"
[ "$want" = "$have" ] || { echo "tag $tag != Cargo.toml version $have" >&2; exit 1; }
echo "version OK: $have"
