#!/usr/bin/env python3
"""Fail when a guide's code block no longer matches the example it quotes.

A guide marks a fenced block with the example region it reproduces:

    <!-- example: pure-mode-hello/src/main/java/eu/exeris/examples/hello/HelloHandler.java#handler -->
    ```java
    ...
    ```

The path is relative to examples/. The region is the text between `tag::<name>[]` and
`end::<name>[]` comment lines in that file, with its common indentation removed. The block must
equal the region exactly, so the code a reader copies is the code CI compiled and ran.
"""
import pathlib
import re
import sys
import textwrap

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXAMPLES = ROOT / "examples"
GUIDES = ROOT / "docs" / "guides"
MARKER = re.compile(r"<!--\s*example:\s*(\S+?)#(\S+?)\s*-->\s*\n```[^\n]*\n(.*?)\n```", re.S)


def region(path: pathlib.Path, name: str) -> str:
    lines = path.read_text().splitlines()
    start = next((i for i, l in enumerate(lines) if f"tag::{name}[]" in l), None)
    end = next((i for i, l in enumerate(lines) if f"end::{name}[]" in l), None)
    if start is None or end is None or end <= start:
        raise LookupError(f"{path.relative_to(ROOT)} has no region '{name}'")
    return textwrap.dedent("\n".join(lines[start + 1:end])).strip("\n")


def main() -> int:
    problems, checked = [], 0
    for guide in sorted(GUIDES.glob("*.md")):
        for match in MARKER.finditer(guide.read_text()):
            rel, name, block = match.groups()
            checked += 1
            source = EXAMPLES / rel
            where = f"{guide.relative_to(ROOT)} → {rel}#{name}"
            if not source.is_file():
                problems.append(f"{where}: no such example file")
                continue
            try:
                expected = region(source, name)
            except LookupError as ex:
                problems.append(f"{where}: {ex}")
                continue
            if block.strip("\n") != expected:
                problems.append(f"{where}: the block differs from the example")
    print(f"{checked} guide snippets checked against examples/")
    for p in problems:
        print(f"::error::{p}")
    return 1 if problems or checked == 0 else 0


if __name__ == "__main__":
    sys.exit(main())
