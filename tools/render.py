#!/usr/bin/env python3
"""Render shared/ into every language tree.

    python3 tools/render.py            # write
    python3 tools/render.py --check    # exit 1 if any tree has drifted (CI)
    python3 tools/render.py --lang go  # one language

Every file under shared/ is copied to <lang>/<same path>. Files ending in
``.tmpl`` are rendered first (the suffix is dropped): ``{{name}}`` is replaced
by ``name`` from langs/<lang>.toml, or by the contents of langs/<lang>/<name>.md.
An unknown placeholder is an error, so a template change can't silently leave
a hole in one language.
"""

from __future__ import annotations

import argparse
import difflib
import re
import stat
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SHARED = ROOT / "shared"
LANGS = ROOT / "langs"
PLACEHOLDER = re.compile(r"\{\{([a-z_]+)\}\}")


def variables(lang: str) -> dict[str, str]:
    values: dict[str, str] = {k: str(v) for k, v in tomllib.loads((LANGS / f"{lang}.toml").read_text()).items()}
    snippets = LANGS / lang
    if snippets.is_dir():
        for md in sorted(snippets.glob("*.md")):
            values[md.stem] = md.read_text().rstrip("\n")
    return values


def render(text: str, values: dict[str, str], source: Path) -> str:
    def sub(m: re.Match[str]) -> str:
        key = m.group(1)
        if key not in values:
            raise SystemExit(f"{source.relative_to(ROOT)}: unknown placeholder {{{{{key}}}}}")
        return values[key]

    return PLACEHOLDER.sub(sub, text)


def outputs(lang: str) -> dict[Path, str]:
    values = variables(lang)
    result: dict[Path, str] = {}
    for src in sorted(p for p in SHARED.rglob("*") if p.is_file() and "__pycache__" not in p.parts):
        rel = src.relative_to(SHARED)
        text = src.read_text()
        if src.suffix == ".tmpl":
            rel = rel.with_suffix("")
            text = render(text, values, src)
        result[ROOT / lang / rel] = text
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--check", action="store_true", help="report drift instead of writing")
    parser.add_argument("--lang", action="append", help="limit to these languages")
    args = parser.parse_args()
    langs = args.lang or sorted(p.stem for p in LANGS.glob("*.toml") if (ROOT / p.stem).is_dir())
    drift = 0
    for lang in langs:
        for path, text in outputs(lang).items():
            current = path.read_text() if path.exists() else None
            if current == text:
                continue
            rel = path.relative_to(ROOT)
            if args.check:
                drift += 1
                diff = difflib.unified_diff((current or "").splitlines(), text.splitlines(), f"{rel} (tree)",
                                            f"{rel} (rendered)", lineterm="", n=1)
                print("\n".join(list(diff)[:40]))
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
            if path.suffix in {".sh", ".py"} and text.startswith("#!"):
                path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
            print(f"wrote {rel}")
    if drift:
        print(f"\n{drift} file(s) differ from shared/. Run: python3 tools/render.py", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
