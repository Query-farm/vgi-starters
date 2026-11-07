#!/usr/bin/env bash
# Fail unless the release tag (e.g. v0.1.0) matches <Version> in Directory.Build.props.
set -euo pipefail
tag="${1:?usage: check-version.sh <tag>}"
want="${tag#v}"
have="$(sed -n -E 's|.*<Version>([^<]+)</Version>.*|\1|p' Directory.Build.props | head -1)"
[ "$want" = "$have" ] || { echo "tag $tag != Directory.Build.props version $have" >&2; exit 1; }
echo "version OK: $have"
