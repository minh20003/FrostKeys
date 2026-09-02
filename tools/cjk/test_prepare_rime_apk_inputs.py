#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Focused safety checks for the Gradle-facing Rime APK-input verifier."""

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
    "prepare_rime_apk_inputs", TOOLS_DIR / "prepare_rime_apk_inputs.py"
)
assert _spec and _spec.loader
preparer = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = preparer
_spec.loader.exec_module(preparer)


class PrepareRimeApkInputsTest(unittest.TestCase):
    def test_runtime_assets_do_not_duplicate_native_libraries(self) -> None:
        self.assertTrue({preparer.BRIDGE_PATH, preparer.CORE_PATH}.issubset(preparer.APK_ASSET_EXCLUDED_PATHS))

    def test_rejects_unsafe_bundle_relative_paths(self) -> None:
        for value in ("../librime.so", "shared\\t2s.json", "/shared/t2s.json", "shared//t2s.json"):
            with self.subTest(value=value), self.assertRaises(preparer.PreparationError):
                preparer.require_safe_relative_path(value, "test path")

    def test_rejects_unlisted_file_in_external_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text("{}", encoding="utf-8")
            payload = root / "shared" / "t2s.json"
            payload.parent.mkdir()
            payload.write_bytes(b"expected")
            (root / "build-machine-path.txt").write_text("must not ship", encoding="utf-8")

            with self.assertRaises(preparer.PreparationError):
                preparer.ensure_bundle_has_no_unlisted_files(root.resolve(), {"shared/t2s.json": payload.resolve()})

    def test_sanitized_provenance_drops_external_builder_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            build_inputs = root / "build-inputs.json"
            build_inputs.write_text(
                json.dumps(
                    {
                        "toolchain": {"ndkRevision": "28.0.13004108", "ndkArchiveSha256": "a" * 64},
                        "bridge": {
                            "schema": 1, "kind": "frostkeys-owned-rime-jni-bridge",
                            "target": "tools/cjk/rime_bridge:frostkeys_rime",
                            "inputs": [{"name": "bridge.cc", "sha256": "b" * 64, "localPath": r"D:\\private-builder"}],
                        },
                        "native": {"path": "native/libfrostkeys_rime.so", "sha256": "j" * 64},
                        "nativeDependencies": [{"role": "librime-core", "path": "native/librime.so", "sha256": "c" * 64}],
                        "rimeBuild": {
                            "schema": 1, "boost": {"version": "1.89.0", "archiveSha256": "d" * 64},
                            "toolHashes": {"build-rime-arm64.sh": "e" * 64, "engine-sources.json": "f" * 64},
                            "sourceVerification": {
                                "origin": "https://github.com/rime/librime.git", "tagObject": "g" * 40,
                                "peeledCommit": "h" * 40, "submodules": [{"commit": "i" * 40, "path": "deps/opencc"}],
                                "checkout": r"D:\\private-builder\\rime",
                            },
                        },
                        "runtimeData": {
                            "schema": 1, "sharedDataPath": "data/shared",
                            "pinyin": {"schemaId": "luna_pinyin", "schemaPath": "data/shared/schema", "dictionaryPath": "data/shared/dict", "defaultConfigPath": "data/shared/default"},
                            "opencc": {"manifestPath": "data/metadata/opencc", "licensePath": "data/licenses/opencc", "simplifiedConfigPath": "data/shared/t2s", "traditionalConfigPath": "data/shared/t2tw"},
                        },
                        "localBuildDirectory": r"D:\\private-builder\\output",
                    }
                ),
                encoding="utf-8",
            )
            source = SimpleNamespace(
                license="BSD-3-Clause",
                checkout_commit="de4700e9f6b75b109910613df907965e3cbe0567",
                commit="5d7467d037938a17abb394f560f016adc9f76e14",
                manifest_source="https://github.com/rime/librime",
            )
            provenance = preparer.create_sanitized_provenance(
                {"engine": "rime", "files": [], "untrustedBuilderPath": r"C:\\users\\builder"},
                source,
                {preparer.BUILD_INPUTS_PATH: build_inputs},
            )

        encoded = json.dumps(provenance, sort_keys=True)
        self.assertNotIn("private-builder", encoded)
        self.assertNotIn("users\\\\builder", encoded)
        self.assertEqual("none", provenance["network"])
        self.assertEqual("arm64-v8a", provenance["runtime"]["abi"])
        self.assertEqual("rime", provenance["engine"])


if __name__ == "__main__":
    unittest.main()
