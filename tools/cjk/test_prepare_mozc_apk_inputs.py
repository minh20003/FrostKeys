#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Focused safety checks for the Gradle-facing Mozc APK-input verifier."""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest


TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))
_spec = importlib.util.spec_from_file_location(
    "prepare_mozc_apk_inputs", TOOLS_DIR / "prepare_mozc_apk_inputs.py"
)
assert _spec and _spec.loader
preparer = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = preparer
_spec.loader.exec_module(preparer)


class PrepareMozcApkInputsTest(unittest.TestCase):
    def test_runtime_assets_do_not_duplicate_the_jni_library(self) -> None:
        self.assertNotIn(preparer.NATIVE_RELATIVE_PATH, preparer.APK_ASSET_PATHS)
        self.assertEqual(
            {
                preparer.DATA_RELATIVE_PATH,
                preparer.LICENSE_RELATIVE_PATH,
                preparer.BUILD_INPUTS_RELATIVE_PATH,
            },
            set(preparer.APK_ASSET_PATHS),
        )

    def test_rejects_unsafe_bundle_relative_paths(self) -> None:
        for value in ("../mozc.data", "data\\mozc.data", "/data/mozc.data", "data//mozc.data"):
            with self.subTest(value=value), self.assertRaises(preparer.PreparationError):
                preparer.require_safe_relative_path(value, "test path")

    def test_rejects_an_unlisted_file_in_external_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text("{}", encoding="utf-8")
            payload = root / "data" / "mozc.data"
            payload.parent.mkdir()
            payload.write_bytes(b"expected")
            (root / "builder-machine-path.txt").write_text("must not ship", encoding="utf-8")

            with self.assertRaises(preparer.PreparationError):
                preparer.ensure_bundle_has_no_unlisted_files(root.resolve(), {"data/mozc.data": payload.resolve()})

    def test_sanitized_provenance_drops_external_builder_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_inputs = root / "build-inputs.json"
            build_inputs.write_text(
                json.dumps(
                    {
                        "toolchain": {"ndkRevision": "28.0.13004108"},
                        "native": {"path": "native/libfrostkeys_mozc.so", "sha256": "a" * 64},
                        "bridge": {"target": "//android/jni:frostkeys_mozc.arm64"},
                        "localBuildDirectory": r"D:\\private-builder\\mozc",
                    }
                ),
                encoding="utf-8",
            )
            source = SimpleNamespace(
                license="BSD-3-Clause",
                checkout_commit="851c3fe33060d2a6090363e4d7ec44fafde2c03d",
                commit="851c3fe33060d2a6090363e4d7ec44fafde2c03d",
                manifest_source="https://github.com/google/mozc",
            )
            provenance = preparer.create_sanitized_provenance(
                {
                    "engine": "mozc",
                    "files": [],
                    "untrustedBuilderPath": r"C:\\users\\builder",
                },
                source,
                {preparer.BUILD_INPUTS_RELATIVE_PATH: build_inputs},
            )

        encoded = json.dumps(provenance, sort_keys=True)
        self.assertNotIn("private-builder", encoded)
        self.assertNotIn("users\\\\builder", encoded)
        self.assertEqual("none", provenance["network"])
        self.assertEqual("arm64-v8a", provenance["runtime"]["abi"])


if __name__ == "__main__":
    unittest.main()
