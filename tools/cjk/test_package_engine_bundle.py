#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Focused host-side tests for the CJK asset packager safety gate."""

from __future__ import annotations

import importlib.util
import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest


TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))
_spec = importlib.util.spec_from_file_location(
    "package_engine_bundle", TOOLS_DIR / "package-engine-bundle.py"
)
assert _spec and _spec.loader
bundle = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = bundle
_spec.loader.exec_module(bundle)

from cjk_source_lock import EngineSource  # noqa: E402


def write_arm64_elf(path: Path, alignment: int) -> None:
    """Writes the smallest ELF shape needed to exercise the parser's ABI gate."""
    header = struct.pack(
        "<16sHHIQQQIHHHHHH",
        b"\x7fELF\x02\x01\x01" + b"\0" * 9,
        3,  # ET_DYN
        183,  # EM_AARCH64
        1,
        0,
        64,
        0,
        0,
        64,
        56,
        1,
        0,
        0,
        0,
    )
    program_header = struct.pack(
        "<IIQQQQQQ",
        1,  # PT_LOAD
        5,
        0,
        0,
        0,
        120,
        120,
        alignment,
    )
    path.write_bytes(header + program_header)


class PackageEngineBundleTest(unittest.TestCase):
    def test_accepts_arm64_16k_shared_elf(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / "librime_frostkeys.so"
            write_arm64_elf(library, 16 * 1024)
            report = bundle.validate_arm64_16k_elf(library)
        self.assertEqual(183, report.machine)
        self.assertEqual((16 * 1024,), report.load_alignments)

    def test_rejects_4k_elf(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            library = Path(directory) / "librime_frostkeys.so"
            write_arm64_elf(library, 4 * 1024)
            with self.assertRaises(bundle.BundleError):
                bundle.validate_arm64_16k_elf(library)

    def test_writes_runtime_compatible_hashed_manifest(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            library = root / "libfrostkeys_rime.so"
            core_library = root / "librime.so"
            write_arm64_elf(library, 16 * 1024)
            write_arm64_elf(core_library, 16 * 1024)
            data_root = root / "rime-data"
            (data_root / "shared").mkdir(parents=True)
            (data_root / "shared" / "luna_pinyin.schema.yaml").write_text(
                "schema: luna_pinyin\n", encoding="utf-8"
            )
            source = EngineSource(
                id="rime",
                project="librime",
                version="1.16.1",
                commit="5d7467d037938a17abb394f560f016adc9f76e14",
                checkout_commit="de4700e9f6b75b109910613df907965e3cbe0567",
                fetch_ref="refs/tags/1.16.1",
                checkout_source="https://github.com/rime/librime.git",
                manifest_source="https://github.com/rime/librime",
                license="BSD-3-Clause",
                status="source-pinned-not-bundled",
            )
            inputs = bundle.collect_bundle_files(
                source,
                library,
                data_root,
                [],
                rime_core_lib=core_library,
            )
            output = root / "rime-assets"
            native_report = bundle.validate_arm64_16k_elf(library)
            bundle.write_bundle(
                output=output,
                source=source,
                source_report={"head": source.checkout_commit},
                native_report=native_report,
                rime_core_report=bundle.validate_arm64_16k_elf(core_library),
                bundle_version=source.version,
                asset_prefix="cjk/rime/1.16.1",
                bundle_files=inputs,
            )
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))

        self.assertEqual("rime", manifest["engine"])
        self.assertEqual("arm64-v8a", manifest["abi"])
        self.assertEqual(4, len(manifest["files"]))
        self.assertEqual(manifest["totalBytes"], sum(file["bytes"] for file in manifest["files"]))
        self.assertTrue(all(len(file["sha256"]) == 64 for file in manifest["files"]))
        self.assertIn("lib/arm64-v8a/libfrostkeys_rime.so", {file["path"] for file in manifest["files"]})
        self.assertIn("lib/arm64-v8a/librime.so", {file["path"] for file in manifest["files"]})
        self.assertIn("shared/luna_pinyin.schema.yaml", {file["path"] for file in manifest["files"]})

    def test_rime_core_is_required_for_bundle_file_collection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bridge = root / "libfrostkeys_rime.so"
            write_arm64_elf(bridge, 16 * 1024)
            data_root = root / "data"
            data_root.mkdir()
            (data_root / "placeholder").write_text("offline", encoding="utf-8")

            with self.assertRaises(bundle.BundleError):
                bundle.collect_bundle_files(self._rime_source(), bridge, data_root, [])

    def test_build_inputs_bind_mozc_library_and_all_data(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "mozc-output"
            native = output / "native" / "libfrostkeys_mozc.so"
            native.parent.mkdir(parents=True)
            write_arm64_elf(native, 16 * 1024)
            data = output / "data" / "mozc.data"
            data.parent.mkdir(parents=True)
            data.write_bytes(b"real offline data")
            source = self._mozc_source()
            lock = root / "toolchains.json"
            lock.write_text(json.dumps(self._toolchain_lock()), encoding="utf-8")
            inputs = output / "BUILD_INPUTS.json"
            inputs.write_text(
                json.dumps(
                    {
                        "schema": 1,
                        "engine": "mozc",
                        "sourceCommit": source.checkout_commit,
                        "toolchain": {
                            "ndkRevision": "28.0.13004108",
                            "ndkArchiveSha256": "a" * 64,
                            "bazelVersion": "9.0.2",
                            "bazelSha256": "b" * 64,
                        },
                        "targets": [
                            "//android/jni:frostkeys_mozc.arm64",
                            "//data_manager/oss:mozc_dataset_for_oss",
                        ],
                        "native": {
                            "path": "native/libfrostkeys_mozc.so",
                            "sha256": self._sha256(native),
                        },
                        "bridge": self._bridge_evidence(),
                        "data": [{"path": "data/mozc.data", "sha256": self._sha256(data)}],
                    }
                ),
                encoding="utf-8",
            )

            report = bundle.verify_build_inputs(
                source=source,
                native_lib=native,
                data_root=data.parent,
                build_inputs_path=inputs,
                toolchain_lock_path=lock,
            )
            package_files = bundle.collect_bundle_files(source, native, data.parent, [])
            package_files.append(bundle.BundleFile(report.path, "metadata/build-inputs.json"))
            assets = root / "assets"
            native_report = bundle.validate_arm64_16k_elf(native)
            bundle.write_bundle(
                output=assets,
                source=source,
                source_report={"head": source.checkout_commit},
                native_report=native_report,
                bundle_version=source.version,
                asset_prefix="cjk/mozc/commit-851c3fe",
                bundle_files=package_files,
                build_inputs=report,
            )
            manifest = json.loads((assets / "manifest.json").read_text(encoding="utf-8"))
            provenance = json.loads(
                (assets / "metadata" / "build-provenance.json").read_text(encoding="utf-8")
            )

        self.assertEqual(1, report.data_file_count)
        self.assertEqual(inputs.resolve(), report.path)
        self.assertIn("metadata/build-inputs.json", {entry["path"] for entry in manifest["files"]})
        self.assertEqual(report.sha256, provenance["buildInputs"]["sha256"])

    def test_build_inputs_rejects_unlisted_data_file(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "mozc-output"
            native = output / "native" / "libfrostkeys_mozc.so"
            native.parent.mkdir(parents=True)
            write_arm64_elf(native, 16 * 1024)
            data = output / "data" / "mozc.data"
            data.parent.mkdir(parents=True)
            data.write_bytes(b"offline data")
            (data.parent / "unexpected.data").write_bytes(b"not declared")
            source = self._mozc_source()
            lock = root / "toolchains.json"
            lock.write_text(json.dumps(self._toolchain_lock()), encoding="utf-8")
            inputs = output / "BUILD_INPUTS.json"
            inputs.write_text(
                json.dumps(
                    {
                        "schema": 1,
                        "engine": "mozc",
                        "sourceCommit": source.checkout_commit,
                        "toolchain": {
                            "ndkRevision": "28.0.13004108",
                            "ndkArchiveSha256": "a" * 64,
                            "bazelVersion": "9.0.2",
                            "bazelSha256": "b" * 64,
                        },
                        "targets": [
                            "//android/jni:frostkeys_mozc.arm64",
                            "//data_manager/oss:mozc_dataset_for_oss",
                        ],
                        "native": {
                            "path": "native/libfrostkeys_mozc.so",
                            "sha256": self._sha256(native),
                        },
                        "bridge": self._bridge_evidence(),
                        "data": [{"path": "data/mozc.data", "sha256": self._sha256(data)}],
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaises(bundle.BundleError):
                bundle.verify_build_inputs(
                    source=source,
                    native_lib=native,
                    data_root=data.parent,
                    build_inputs_path=inputs,
                    toolchain_lock_path=lock,
                )

    @staticmethod
    def _sha256(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    @staticmethod
    def _toolchain_lock() -> dict[str, object]:
        return {
            "schema": 1,
            "androidNdk": {
                "revision": "28.0.13004108",
                "archive": {"sha256": "a" * 64},
            },
            "bazel": {"version": "9.0.2", "sha256": "b" * 64},
        }

    @staticmethod
    def _bridge_evidence() -> dict[str, object]:
        bridge_dir = TOOLS_DIR / "mozc_bridge"
        return {
            "schema": 1,
            "kind": "frostkeys-owned-jni-bridge",
            "target": "//android/jni:frostkeys_mozc.arm64",
            "inputs": [
                {
                    "name": name,
                    "sha256": hashlib.sha256((bridge_dir / name).read_bytes()).hexdigest(),
                }
                for name in ("frostkeys_mozc_jni.cc", "BUILD.bazel.template")
            ],
        }

    @staticmethod
    def _mozc_source() -> EngineSource:
        return EngineSource(
            id="mozc",
            project="Mozc",
            version="commit-851c3fe",
            commit="851c3fe33060d2a6090363e4d7ec44fafde2c03d",
            checkout_commit="851c3fe33060d2a6090363e4d7ec44fafde2c03d",
            fetch_ref="851c3fe33060d2a6090363e4d7ec44fafde2c03d",
            checkout_source="https://github.com/google/mozc.git",
            manifest_source="https://github.com/google/mozc",
            license="BSD-3-Clause",
            status="source-pinned-not-bundled",
        )

    @staticmethod
    def _rime_source() -> EngineSource:
        return EngineSource(
            id="rime",
            project="librime",
            version="1.16.1",
            commit="5d7467d037938a17abb394f560f016adc9f76e14",
            checkout_commit="de4700e9f6b75b109910613df907965e3cbe0567",
            fetch_ref="refs/tags/1.16.1",
            checkout_source="https://github.com/rime/librime.git",
            manifest_source="https://github.com/rime/librime",
            license="BSD-3-Clause",
            status="source-pinned-not-bundled",
        )


if __name__ == "__main__":
    unittest.main()
