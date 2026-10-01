#!/usr/bin/env python3
"""Checks the string resources in both directions.

A string nobody uses is dead weight in a folder somebody reads looking for the copy. A `R.string.x` that no
longer exists is a compile error. Neither shows up in a Kotlin-only scan, because resources live in XML.

Usage: python tools/check_strings.py [app/src]
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

DECLARED = re.compile(r'<string\s+name="([^"]+)"')
USED_IN_KOTLIN = re.compile(r"R\.string\.([A-Za-z0-9_]+)")
USED_IN_XML = re.compile(r"@string/([A-Za-z0-9_]+)")


def main(roots: list[Path]) -> int:
    root = roots[0]
    values = root / "main" / "res" / "values" / "strings.xml"
    if not values.is_file():
        print(f"{values} does not exist")
        return 1

    xml = values.read_text()
    declared = DECLARED.findall(xml)
    duplicates = sorted({name for name in declared if declared.count(name) > 1})

    sources: list[Path] = list((root / "main").rglob("*.kt"))
    sources += list((root / "test").rglob("*.kt")) if (root / "test").exists() else []
    sources += list((root / "androidTest").rglob("*.kt")) if (root / "androidTest").exists() else []
    sources += list((root / "main" / "res").rglob("*.xml"))
    manifest = root / "main" / "AndroidManifest.xml"
    if manifest.is_file():
        sources.append(manifest)

    kotlin_text = "\n".join(path.read_text(errors="replace") for path in sources if path.suffix == ".kt")
    xml_text = "\n".join(path.read_text(errors="replace") for path in sources if path.suffix != ".kt")

    # A declaration is `<string name="x">`, which is not `R.string.x` or `@string/x`: nothing needs to be
    # subtracted, and subtracting the declared set would erase every real reference.
    referenced = set(USED_IN_KOTLIN.findall(kotlin_text)) | set(USED_IN_XML.findall(xml_text))

    unused = sorted(set(declared) - referenced)
    undefined = sorted(referenced - set(declared))

    print(f"strings.xml: {len(declared)} declared, {len(referenced)} referenced from code or XML")
    problems = 0
    if duplicates:
        problems += len(duplicates)
        print(f"\n{len(duplicates)} name(s) declared more than once:")
        for name in duplicates:
            print(f"  {name}")
    if unused:
        problems += len(unused)
        print(f"\n{len(unused)} string(s) nothing refers to:")
        for name in unused:
            print(f"  {name}")
    if undefined:
        problems += len(undefined)
        print(f"\n{len(undefined)} reference(s) with no string behind them:")
        for name in undefined:
            print(f"  R.string.{name} / @string/{name}")

    if problems:
        return 1
    print("every string is declared once and used, and every reference resolves")
    return 0


if __name__ == "__main__":
    arguments = [Path(argument) for argument in sys.argv[1:]]
    if not arguments:
        arguments = [Path("app/src")]
    sys.exit(main(arguments))
