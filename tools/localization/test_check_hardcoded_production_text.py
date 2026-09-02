#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Focused regression tests for the production-text localization gate."""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest


TOOLS_DIR = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location(
    "check_hardcoded_production_text", TOOLS_DIR / "check_hardcoded_production_text.py"
)
assert spec and spec.loader
checker = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = checker
spec.loader.exec_module(checker)


class HardcodedProductionTextCheckTest(unittest.TestCase):
    def test_flags_visible_text_in_production_source(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Screen.kt"
            source.write_text('fun Screen() { Text("Visible text") }\n', encoding="utf-8")
            violations = checker.find_violations(Path(directory))
        self.assertEqual(1, len(violations))
        self.assertEqual("Compose Text", violations[0].kind)

    def test_flags_visible_multiline_compose_text(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "Screen.kt"
            source.write_text('fun Screen() { Text(\n  text = "Visible text"\n) }\n', encoding="utf-8")
            violations = checker.find_violations(Path(directory))
        self.assertEqual(1, len(violations))
        self.assertEqual(2, violations[0].line)

    def test_skips_preview_and_decorative_emoji(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Preview.kt").write_text(
                '@Preview\n@Composable\nfun Preview() { Text("Example") }\n', encoding="utf-8"
            )
            (root / "Icon.kt").write_text('fun Icon() { Text("🖼️") }\n', encoding="utf-8")
            violations = checker.find_violations(root)
        self.assertEqual([], violations)

    def test_flags_literal_xml_text_attribute(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            resources = root / "res"
            source.mkdir()
            resources.mkdir()
            (resources / "layout.xml").write_text(
                '<TextView android:text="Visible XML text" />\n', encoding="utf-8"
            )
            violations = checker.find_violations(source, resources)
        self.assertEqual(1, len(violations))
        self.assertEqual("XML text attribute", violations[0].kind)


if __name__ == "__main__":
    unittest.main()
