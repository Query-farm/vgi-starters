#!/usr/bin/env python3
"""Move version pins across every starter tree.

vgi-lint is deliberately unpinned (vgi-lint-check policy), so there is nothing to bump.

    python3 tools/bump.py --haybarn 2.0.0rc12        # SQL test runner (all trees)
    python3 tools/bump.py --sdk python 0.50.0        # one SDK

Then regenerate lockfiles (each tree's `post_init` commands in .vgi-starter.json do
it) and run `make check` in each touched tree.

Bump an SDK minor version (or Haybarn across a protocol change) only after the DuckDB
`vgi` extension that speaks the new protocol is published. Protocol versions must
match exactly, so bumping early turns every starter's CI red.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LANGS = ["python", "typescript", "go", "rust", "java", "csharp"]

# (glob relative to the tree, regex with one group around the version, replacement template)
SHARED_PINS = {
    "haybarn": [
        ("Makefile", r"(?m)^(HAYBARN_VERSION\s*\?=\s*)\S+", r"\g<1>{v}"),
        (".github/workflows/ci.yml", r'(HAYBARN_VERSION:\s*")[^"]+(")', r"\g<1>{v}\g<2>"),
    ],
}

# SDK pins per language. Each version constraint stays within one minor version.
SDK_PINS: dict[str, list[tuple[str, str, str]]] = {
    "python": [
        ("pyproject.toml", r'(vgi-python\[[a-z,]+\]>=)[0-9.]+(,<)[0-9.]+', "\\g<1>{v}\\g<2>{next_minor}"),
    ],
    "typescript": [
        ("package.json", r'("@query-farm/vgi":\s*"~)[0-9.]+(")', "\\g<1>{v}\\g<2>"),
    ],
    "csharp": [
        ("Directory.Packages.props", r'(Include="QueryFarm\.Vgi" Version=")[0-9.]+\.\*(")', "\\g<1>{minor}.*\\g<2>"),
    ],
    "rust": [
        ("Cargo.toml", r'(?m)^(vgi\s*=\s*")[0-9.]+(")', "\\g<1>{v}\\g<2>"),
    ],
    "go": [
        ("go.mod", r'(github\.com/Query-farm/vgi-go v)[0-9.]+', "\\g<1>{v}"),
    ],
    "java": [
        ("build.gradle.kts", r'("farm\.query:vgi:)[0-9.]+\.\+(")', "\\g<1>{minor}.+\\g<2>"),
    ],
}


def next_minor(version: str) -> str:
    major, minor = version.split(".")[:2]
    return f"{major}.{int(minor) + 1}"


def apply(lang: str, rules: list[tuple[str, str, str]], version: str, dry: bool) -> int:
    changed = 0
    for glob, pattern, template in rules:
        for path in sorted((ROOT / lang).glob(glob)):
            text = path.read_text()
            repl = template.format(v=version, next_minor=next_minor(version), minor=".".join(version.split(".")[:2]))
            new, n = re.subn(pattern, repl, text)
            if n == 0:
                print(f"  ! {path.relative_to(ROOT)}: pattern not found: {pattern}", file=sys.stderr)
                continue
            if new != text:
                changed += 1
                print(f"  {path.relative_to(ROOT)}")
                if not dry:
                    path.write_text(new)
    return changed


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--haybarn", metavar="VERSION")
    parser.add_argument("--sdk", nargs=2, metavar=("LANG", "VERSION"))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if not (args.haybarn or args.sdk):
        parser.error("nothing to bump")
    present = [lang for lang in LANGS if (ROOT / lang).is_dir()]
    for key in ("haybarn",):
        version = getattr(args, key)
        if version:
            print(f"{key} -> {version}")
            for lang in present:
                apply(lang, SHARED_PINS[key], version, args.dry_run)
    if args.sdk:
        lang, version = args.sdk
        if lang not in SDK_PINS:
            parser.error(f"no SDK pin rules for {lang!r} yet; add them to SDK_PINS")
        print(f"{lang} SDK -> {version}")
        apply(lang, SDK_PINS[lang], version, args.dry_run)
    print("next: regenerate lockfiles, then make check in each touched tree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
