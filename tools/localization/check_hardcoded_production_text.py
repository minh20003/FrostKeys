#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Reject user-visible hardcoded text in Android production source.

This is intentionally a narrow, deterministic CI guard rather than a full Kotlin/Java parser.
It catches the most common UI sinks (Compose Text, View.setText, content descriptions, Toasts and
clipboard labels) while ignoring @Preview functions and purely decorative emoji glyphs. New code
must route visible text through Android resources; the Vietnamese parity task then verifies that
the resource has a compatible `values-vi` translation.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys
import unicodedata


STRING = r'"(?:\\.|[^"\\])*"'
CHECKS = (
    ("Compose Text", re.compile(rf"(?<![A-Za-z0-9_])Text\s*\(\s*(?:text\s*=\s*)?({STRING})")),
    ("View.setText", re.compile(rf"(?<![A-Za-z0-9_])setText\s*\(\s*({STRING})")),
    ("contentDescription", re.compile(rf"\bcontentDescription\s*=\s*({STRING})")),
    ("Toast", re.compile(rf"\bToast\.makeText\s*\([^,\n]+,\s*({STRING})")),
    ("clipboard label", re.compile(rf"\bClipData\.newPlainText\s*\(\s*({STRING})")),
)
XML_TEXT_ATTRIBUTE = re.compile(
    r'android:(?:text|hint|contentDescription|title|summary)\s*=\s*"([^"]+)"'
)
PREVIEW_ANNOTATION = re.compile(r"@Preview\b")
FUNCTION_START = re.compile(r"\bfun\s+\w+")


@dataclass(frozen=True)
class Violation:
    path: Path
    line: int
    kind: str
    literal: str


def decode_literal(raw: str) -> str:
    # We only need enough decoding to recognize an empty/decorative glyph literal. Keep malformed
    # source visible to the compiler rather than attempting to normalize it here.
    body = raw[1:-1]
    if "\\" not in body:
        return body
    try:
        return bytes(body, "utf-8").decode("unicode_escape")
    except UnicodeDecodeError:
        return body


def is_decorative_glyph(value: str) -> bool:
    if not value:
        return True
    for character in value:
        category = unicodedata.category(character)
        if category in {"So", "Mn", "Me", "Cf"}:
            continue
        return False
    return True


def iter_non_preview_lines(path: Path):
    pending_preview = False
    preview_depth: int | None = None
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if preview_depth is not None:
            preview_depth += line.count("{") - line.count("}")
            if preview_depth <= 0:
                preview_depth = None
            yield line_number, None
            continue
        if PREVIEW_ANNOTATION.search(line):
            pending_preview = True
            yield line_number, None
            continue
        if pending_preview:
            if FUNCTION_START.search(line):
                preview_depth = line.count("{") - line.count("}")
                # Kotlin permits a function declaration split across lines. Keep waiting until its
                # opening brace instead of accidentally treating preview content as production.
                if preview_depth <= 0 and "{" not in line:
                    preview_depth = 0
                pending_preview = False
                yield line_number, None
                continue
            if "{" in line:
                preview_depth = line.count("{") - line.count("}")
                pending_preview = False
                yield line_number, None
                continue
        yield line_number, line


def find_violations(source_root: Path, resource_root: Path | None = None) -> list[Violation]:
    violations: list[Violation] = []
    for path in sorted(source_root.rglob("*")):
        if path.suffix not in {".kt", ".java"} or not path.is_file():
            continue
        non_preview_source = "\n".join(
            line if line is not None else ""
            for _, line in iter_non_preview_lines(path)
        )
        for kind, pattern in CHECKS:
            for match in pattern.finditer(non_preview_source):
                literal = decode_literal(match.group(1))
                if is_decorative_glyph(literal):
                    continue
                line_number = non_preview_source.count("\n", 0, match.start(1)) + 1
                violations.append(Violation(path, line_number, kind, literal))
    if resource_root:
        for path in sorted(resource_root.rglob("*.xml")):
            if not path.is_file():
                continue
            for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
                for match in XML_TEXT_ATTRIBUTE.finditer(line):
                    literal = match.group(1)
                    if literal.startswith(("@", "?")) or is_decorative_glyph(literal):
                        continue
                    violations.append(Violation(path, line_number, "XML text attribute", literal))
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--resource-root", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    root = args.source_root.resolve()
    if not root.is_dir():
        parser.error(f"Source root is not a directory: {root}")
    resource_root = args.resource_root.resolve() if args.resource_root else None
    if resource_root is not None and not resource_root.is_dir():
        parser.error(f"Resource root is not a directory: {resource_root}")
    violations = find_violations(root, resource_root)
    report = "\n".join(
        f"{violation.path.relative_to(root if violation.path.is_relative_to(root) else resource_root).as_posix()}:"
        f"{violation.line}: "
        f"{violation.kind}: {violation.literal!r}"
        for violation in violations
    )
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report + ("\n" if report else ""), encoding="utf-8", newline="\n")
    if violations:
        print("Hardcoded production text detected; use string resources instead:", file=sys.stderr)
        print(report, file=sys.stderr)
        return 1
    print("Hardcoded production text check: no violations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
