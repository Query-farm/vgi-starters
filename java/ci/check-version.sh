#!/usr/bin/env bash
# Fail unless the release tag (e.g. v0.1.0) matches the version in build.gradle.kts.
set -euo pipefail
tag="${1:?usage: check-version.sh <tag>}"
want="${tag#v}"
have="$(sed -n -E 's/^version = "([^"]+)"/\1/p' build.gradle.kts | head -1)"
[ "$want" = "$have" ] || { echo "tag $tag != build.gradle.kts version $have" >&2; exit 1; }
echo "version OK: $have"
