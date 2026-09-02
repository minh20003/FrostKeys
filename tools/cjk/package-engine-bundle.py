#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Package a *real*, verified ARM64 CJK build into FrostKeys asset layout.

The script is deliberately unable to create a working Rime or Mozc build from
source.  It only accepts an already-built library and already-generated offline
data, proves the source checkout that produced them, validates the ELF ABI and
16 KiB load alignment, and writes a hash-complete asset tree plus the exact
manifest format consumed by ``EngineBundleInstaller``.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import sys
import tempfile
from typing import Any, Iterable

from cjk_source_lock import EngineSource, SourceLockError, find_checkout, load_source_lock, verify_checkout


SCRIPT_VERSION = 1
MAX_FILE_BYTES = 128 * 1024 * 1024
MAX_BUNDLE_BYTES = 256 * 1024 * 1024
MAX_BUNDLE_FILES = 2_048
PAGE_SIZE_16K = 16 * 1024
ELF_MACHINE_AARCH64 = 183
PT_LOAD = 1
PT_DYNAMIC = 2
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
_SEGMENT_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}")
MOZC_OWNED_LIBRARY_NAME = "libfrostkeys_mozc.so"
MOZC_OWNED_BRIDGE_KIND = "frostkeys-owned-jni-bridge"
MOZC_OWNED_BRIDGE_TARGET = "//android/jni:frostkeys_mozc.arm64"
MOZC_REQUIRED_TARGETS = {
    MOZC_OWNED_BRIDGE_TARGET,
    "//data_manager/oss:mozc_dataset_for_oss",
}
MOZC_BRIDGE_INPUT_NAMES = (
    "frostkeys_mozc_jni.cc",
    "BUILD.bazel.template",
)
DEFAULT_MOZC_BRIDGE_DIR = Path(__file__).with_name("mozc_bridge")

# Rime is deliberately stricter than the old generic ``librime`` candidate.
# The library linked into an APK must be FrostKeys' narrow JNI front end and
# must dynamically depend on a separately hash-bound ``librime.so`` core.  A
# matching source checkout plus a file merely named "rime" is not sufficient:
# it could still be an arbitrary JNI surface or a core which was never linked
# to the reviewed bridge.  Keep the source file list explicit so a bridge edit
# requires a reproducible rebuild rather than silently changing native code.
RIME_OWNED_LIBRARY_NAME = "libfrostkeys_rime.so"
RIME_CORE_LIBRARY_NAME = "librime.so"
RIME_OWNED_BRIDGE_KIND = "frostkeys-owned-rime-jni-bridge"
RIME_OWNED_BRIDGE_TARGET = "tools/cjk/rime_bridge:frostkeys_rime"
RIME_BRIDGE_INPUT_NAMES = (
    "frostkeys_rime_jni.cc",
    "CMakeLists.txt",
)
RIME_OPENCC_SUBMODULE_COMMIT = "556ed22496d650bd0b13b6c163be9814637970ae"
RIME_OPENCC_RUNTIME_FILE_COUNT = 30
RIME_OPENCC_OCD2_COUNT = 16
RIME_OPENCC_CONFIG_COUNT = 14
DEFAULT_RIME_BRIDGE_DIR = Path(__file__).with_name("rime_bridge")


class BundleError(RuntimeError):
    """Raised when a candidate bundle cannot meet the runtime safety contract."""


@dataclass(frozen=True)
class NativeElfReport:
    path: str
    machine: int
    load_alignments: tuple[int, ...]


@dataclass(frozen=True)
class BuildInputsReport:
    """Immutable build evidence copied into a packaged APK asset bundle."""

    path: Path
    sha256: str
    data_file_count: int


@dataclass(frozen=True)
class BundleFile:
    source: Path
    relative_path: str


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", required=True, choices=("rime", "mozc"))
    parser.add_argument("--source-root", required=True, type=Path)
    parser.add_argument("--native-lib", required=True, type=Path, help="built ARM64 shared library")
    parser.add_argument("--data-root", required=True, type=Path, help="generated, offline engine data")
    parser.add_argument(
        "--output",
        required=True,
        type=Path,
        help="new output directory; it must not exist",
    )
    parser.add_argument(
        "--asset-prefix",
        help="APK asset path that will contain this directory, e.g. cjk/mozc/commit-851c3fe",
    )
    parser.add_argument(
        "--bundle-version",
        help="manifest version; defaults to the exact lock version",
    )
    parser.add_argument(
        "--notice",
        type=Path,
        action="append",
        default=[],
        help="license/source-attribution file to include and hash (repeatable)",
    )
    parser.add_argument(
        "--build-inputs",
        type=Path,
        help=(
            "BUILD_INPUTS.json emitted by the native build; required so packaging cannot "
            "claim arbitrary binaries as a reviewed CJK engine"
        ),
    )
    parser.add_argument(
        "--toolchain-lock",
        type=Path,
        default=Path(__file__).with_name("toolchains.json"),
        help="immutable NDK/Bazel input lock used to validate BUILD_INPUTS.json",
    )
    parser.add_argument(
        "--mozc-bridge-dir",
        type=Path,
        default=DEFAULT_MOZC_BRIDGE_DIR,
        help=(
            "reviewed FrostKeys-owned Mozc JNI bridge sources whose exact hashes must be "
            "recorded in BUILD_INPUTS.json"
        ),
    )
    parser.add_argument(
        "--rime-core-lib",
        type=Path,
        help=(
            "ARM64 librime.so matched to --native-lib; required for --engine rime so a "
            "FrostKeys bridge cannot be packaged without its reviewed Rime core"
        ),
    )
    parser.add_argument(
        "--rime-bridge-dir",
        type=Path,
        default=DEFAULT_RIME_BRIDGE_DIR,
        help=(
            "reviewed FrostKeys-owned Rime JNI bridge sources whose exact hashes must be "
            "recorded in BUILD_INPUTS.json"
        ),
    )
    parser.add_argument(
        "--lock",
        type=Path,
        default=Path(__file__).with_name("engine-sources.json"),
    )
    args = parser.parse_args()

    try:
        lock = load_source_lock(args.lock)
        source = lock[args.engine]
        checkout = find_checkout(args.source_root, source)
        source_report = verify_checkout(source, checkout)
        bundle_version = args.bundle_version or source.version
        _require_safe_relative_path(bundle_version, "bundle version")
        asset_prefix = args.asset_prefix or f"cjk/{source.id}/{bundle_version}"
        _require_safe_relative_path(asset_prefix, "asset prefix")
        output = args.output.resolve()
        if output.exists():
            raise BundleError(f"Refusing to overwrite existing output: {output}")
        if not output.parent.is_dir():
            raise BundleError(f"Output parent does not exist: {output.parent}")

        if source.id == "rime" and args.rime_core_lib is None:
            raise BundleError("--rime-core-lib is required when packaging the Rime bridge")
        if source.id != "rime" and args.rime_core_lib is not None:
            raise BundleError("--rime-core-lib is valid only when --engine rime")

        native_report = validate_arm64_16k_elf(args.native_lib)
        rime_core_report = None
        if source.id == "rime":
            assert args.rime_core_lib is not None
            rime_core_report = validate_arm64_16k_elf(args.rime_core_lib)
            require_elf_needed_library(args.native_lib, RIME_CORE_LIBRARY_NAME)
        build_inputs = verify_build_inputs(
            source=source,
            native_lib=args.native_lib,
            rime_core_lib=args.rime_core_lib,
            data_root=args.data_root,
            build_inputs_path=args.build_inputs,
            toolchain_lock_path=args.toolchain_lock,
            mozc_bridge_dir=args.mozc_bridge_dir,
            rime_bridge_dir=args.rime_bridge_dir,
        )
        bundle_files = collect_bundle_files(
            source,
            args.native_lib,
            args.data_root,
            args.notice,
            rime_core_lib=args.rime_core_lib,
        )
        # The exact evidence used to bind this output to its toolchain and data
        # is itself hash-listed in the runtime manifest.  It is never parsed by
        # the app, but remains available for release auditing.
        bundle_files.append(BundleFile(build_inputs.path, "metadata/build-inputs.json"))
        write_bundle(
            output=output,
            source=source,
            source_report=source_report,
            native_report=native_report,
            rime_core_report=rime_core_report,
            bundle_version=bundle_version,
            asset_prefix=asset_prefix,
            bundle_files=bundle_files,
            build_inputs=build_inputs,
        )
    except (BundleError, SourceLockError, OSError) as error:
        print(f"CJK bundle packaging failed: {error}", file=sys.stderr)
        return 1

    print(f"Wrote verified {args.engine} engine asset bundle: {output}")
    return 0


def verify_build_inputs(
    *,
    source: EngineSource,
    native_lib: Path,
    data_root: Path,
    build_inputs_path: Path | None,
    toolchain_lock_path: Path,
    rime_core_lib: Path | None = None,
    mozc_bridge_dir: Path = DEFAULT_MOZC_BRIDGE_DIR,
    rime_bridge_dir: Path = DEFAULT_RIME_BRIDGE_DIR,
) -> BuildInputsReport:
    """Binds package inputs to the exact native build evidence.

    Source checkout verification proves that the *source directory* is reviewed, but without this
    additional gate a caller could pair that checkout with an unrelated ``.so`` and data directory.
    The native build helper emits this file only after producing the ARM64 library and its offline
    data.  Packaging requires exact paths and hashes, then carries the proof into the APK bundle.
    """
    if build_inputs_path is None:
        raise BundleError("--build-inputs is required for every native CJK engine bundle")
    if build_inputs_path.is_symlink():
        raise BundleError(f"BUILD_INPUTS.json may not be a symbolic link: {build_inputs_path}")
    build_inputs_path = build_inputs_path.resolve()
    if not build_inputs_path.is_file():
        raise BundleError(f"BUILD_INPUTS.json must be a regular file: {build_inputs_path}")
    if not 1 <= build_inputs_path.stat().st_size <= MAX_FILE_BYTES:
        raise BundleError("BUILD_INPUTS.json has an invalid size")
    try:
        raw_bytes = build_inputs_path.read_bytes()
        raw = json.loads(raw_bytes.decode("utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BundleError(f"Could not read BUILD_INPUTS.json: {error}") from error
    if not isinstance(raw, dict) or raw.get("schema") != 1:
        raise BundleError("BUILD_INPUTS.json has an unsupported schema")
    if raw.get("engine") != source.id:
        raise BundleError("BUILD_INPUTS.json engine does not match requested engine")
    if raw.get("sourceCommit", "").lower() != source.checkout_commit:
        raise BundleError("BUILD_INPUTS.json source commit does not match the source lock")

    expected_toolchain = _load_toolchain_policy(toolchain_lock_path)
    toolchain = raw.get("toolchain")
    if not isinstance(toolchain, dict):
        raise BundleError("BUILD_INPUTS.json has no toolchain evidence")
    if toolchain.get("ndkRevision") != expected_toolchain["ndkRevision"]:
        raise BundleError("BUILD_INPUTS.json NDK revision does not match toolchain lock")
    if _require_sha256(toolchain.get("ndkArchiveSha256"), "NDK archive SHA-256") != expected_toolchain[
        "ndkArchiveSha256"
    ]:
        raise BundleError("BUILD_INPUTS.json NDK archive hash does not match toolchain lock")
    if source.id == "mozc":
        if toolchain.get("bazelVersion") != expected_toolchain["bazelVersion"]:
            raise BundleError("BUILD_INPUTS.json Bazel version does not match toolchain lock")
        if _require_sha256(toolchain.get("bazelSha256"), "Bazel SHA-256") != expected_toolchain[
            "bazelSha256"
        ]:
            raise BundleError("BUILD_INPUTS.json Bazel hash does not match toolchain lock")
        targets = raw.get("targets")
        if not isinstance(targets, list) or set(targets) != MOZC_REQUIRED_TARGETS or len(targets) != len(MOZC_REQUIRED_TARGETS):
            raise BundleError("BUILD_INPUTS.json does not prove the required FrostKeys-owned Mozc build targets")
        _verify_mozc_bridge_evidence(raw, mozc_bridge_dir)
    elif source.id == "rime":
        if rime_core_lib is None:
            raise BundleError("Rime BUILD_INPUTS.json requires a supplied librime core library")
        _verify_rime_bridge_evidence(raw, rime_bridge_dir)

    build_root = build_inputs_path.parent.resolve()
    if native_lib.is_symlink():
        raise BundleError(f"Native library may not be a symbolic link: {native_lib}")
    native_lib = native_lib.resolve()
    if source.id == "mozc" and native_lib.name != MOZC_OWNED_LIBRARY_NAME:
        raise BundleError(
            "Mozc bundle must package the FrostKeys-owned JNI bridge, not upstream libmozc.so"
        )
    if source.id == "rime" and native_lib.name != RIME_OWNED_LIBRARY_NAME:
        raise BundleError(
            "Rime bundle must package libfrostkeys_rime.so, not an arbitrary or upstream JNI library"
        )
    native = raw.get("native")
    if not isinstance(native, dict):
        raise BundleError("BUILD_INPUTS.json has no native-library evidence")
    native_path = _build_output_file(build_root, native.get("path"), "native library")
    if native_path != native_lib:
        raise BundleError("Native library does not match BUILD_INPUTS.json output path")
    _require_matching_file_hash(native_path, native.get("sha256"), "native library")

    if source.id == "rime":
        assert rime_core_lib is not None
        _verify_rime_core_evidence(raw, rime_core_lib, build_root)

    if data_root.is_symlink():
        raise BundleError(f"Data root may not be a symbolic link: {data_root}")
    data_root = data_root.resolve()
    expected_data_root = _build_output_file(build_root, "data", "data directory")
    if data_root != expected_data_root or not data_root.is_dir():
        raise BundleError("Data root does not match BUILD_INPUTS.json output directory")
    data_entries = raw.get("data")
    if not isinstance(data_entries, list) or not data_entries:
        raise BundleError("BUILD_INPUTS.json has no offline data evidence")

    declared_data: dict[str, Path] = {}
    for entry in data_entries:
        if not isinstance(entry, dict):
            raise BundleError("BUILD_INPUTS.json has an invalid data entry")
        relative = entry.get("path")
        if not isinstance(relative, str) or not relative.startswith("data/"):
            raise BundleError("BUILD_INPUTS.json data path must be below data/")
        data_path = _build_output_file(build_root, relative, "data file")
        if not data_path.is_file() or data_path.is_symlink():
            raise BundleError(f"BUILD_INPUTS.json data file is missing or unsafe: {relative}")
        if not _is_within(data_path, data_root):
            raise BundleError(f"BUILD_INPUTS.json data file escapes data root: {relative}")
        if relative in declared_data:
            raise BundleError(f"BUILD_INPUTS.json repeats data file: {relative}")
        _require_matching_file_hash(data_path, entry.get("sha256"), f"data file {relative}")
        declared_data[relative] = data_path

    actual_data: dict[str, Path] = {}
    for candidate in sorted(data_root.rglob("*")):
        if candidate.is_symlink():
            raise BundleError(f"Data root contains a symbolic link: {candidate}")
        if candidate.is_file():
            relative = f"data/{candidate.relative_to(data_root).as_posix()}"
            actual_data[relative] = candidate.resolve()
    if set(declared_data) != set(actual_data):
        raise BundleError("BUILD_INPUTS.json data list does not exactly match packaged data root")
    if any(declared_data[path] != actual_data[path] for path in declared_data):
        raise BundleError("BUILD_INPUTS.json data path does not match packaged data root")
    if source.id == "rime":
        _verify_rime_offline_data_evidence(raw, declared_data)

    return BuildInputsReport(
        path=build_inputs_path,
        sha256=hashlib.sha256(raw_bytes).hexdigest(),
        data_file_count=len(declared_data),
    )


def _load_toolchain_policy(path: Path) -> dict[str, str]:
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
        ndk = raw["androidNdk"]
        archive = ndk["archive"]
        bazel = raw["bazel"]
        result = {
            "ndkRevision": ndk["revision"],
            "ndkArchiveSha256": archive["sha256"].lower(),
            "bazelVersion": bazel["version"],
            "bazelSha256": bazel["sha256"].lower(),
        }
    except (OSError, KeyError, TypeError, json.JSONDecodeError) as error:
        raise BundleError(f"Could not read CJK toolchain lock: {error}") from error
    if not all(isinstance(value, str) and value for value in result.values()):
        raise BundleError("CJK toolchain lock contains an empty value")
    _require_sha256(result["ndkArchiveSha256"], "toolchain-lock NDK SHA-256")
    _require_sha256(result["bazelSha256"], "toolchain-lock Bazel SHA-256")
    return result


def _verify_mozc_bridge_evidence(raw: dict[str, Any], bridge_dir: Path) -> None:
    """Proves that the built library came from the reviewed owned JNI boundary.

    Mozc's upstream ``libmozc.so`` exports Google's JNI registration and owns a
    process-global session.  A matching source checkout and ABI are therefore
    insufficient: this build evidence must bind the artifact to the exact
    FrostKeys bridge source and append fragment that the build helper copied
    into its temporary snapshot.
    """
    bridge = raw.get("bridge")
    if not isinstance(bridge, dict):
        raise BundleError("BUILD_INPUTS.json has no FrostKeys Mozc bridge evidence")
    if bridge.get("schema") != 1 or bridge.get("kind") != MOZC_OWNED_BRIDGE_KIND:
        raise BundleError("BUILD_INPUTS.json has an unsupported FrostKeys Mozc bridge identity")
    if bridge.get("target") != MOZC_OWNED_BRIDGE_TARGET:
        raise BundleError("BUILD_INPUTS.json Mozc bridge target does not match the owned bridge")
    entries = bridge.get("inputs")
    if not isinstance(entries, list) or len(entries) != len(MOZC_BRIDGE_INPUT_NAMES):
        raise BundleError("BUILD_INPUTS.json has an invalid FrostKeys Mozc bridge input list")

    if bridge_dir.is_symlink() or not bridge_dir.is_dir():
        raise BundleError(f"Reviewed FrostKeys Mozc bridge directory is missing or unsafe: {bridge_dir}")
    bridge_dir = bridge_dir.resolve()
    declared: dict[str, str] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise BundleError("BUILD_INPUTS.json has an invalid FrostKeys Mozc bridge input")
        name = entry.get("name")
        if not isinstance(name, str) or name not in MOZC_BRIDGE_INPUT_NAMES or name in declared:
            raise BundleError("BUILD_INPUTS.json has an unexpected FrostKeys Mozc bridge input")
        declared[name] = _require_sha256(entry.get("sha256"), f"Mozc bridge input {name} SHA-256")
    if set(declared) != set(MOZC_BRIDGE_INPUT_NAMES):
        raise BundleError("BUILD_INPUTS.json does not list every reviewed FrostKeys Mozc bridge input")

    for name in MOZC_BRIDGE_INPUT_NAMES:
        source = bridge_dir / name
        if source.is_symlink() or not source.is_file():
            raise BundleError(f"Reviewed FrostKeys Mozc bridge input is missing or unsafe: {source}")
        if _digest_file(source) != declared[name]:
            raise BundleError(f"BUILD_INPUTS.json FrostKeys Mozc bridge hash does not match: {name}")


def _verify_rime_bridge_evidence(raw: dict[str, Any], bridge_dir: Path) -> None:
    """Bind a Rime package to the reviewed, narrow FrostKeys JNI boundary.

    ``librime.so`` itself is a core library, not an Android IME interface.  The
    bridge provenance is therefore mandatory even when a caller has supplied a
    correctly aligned core.  In particular, do not relax this to a name check:
    a binary called ``libfrostkeys_rime.so`` can still expose an arbitrary JNI
    command surface unless it was built from these reviewed source files.
    """
    bridge = raw.get("bridge")
    if not isinstance(bridge, dict):
        raise BundleError("BUILD_INPUTS.json has no FrostKeys Rime bridge evidence")
    if bridge.get("schema") != 1 or bridge.get("kind") != RIME_OWNED_BRIDGE_KIND:
        raise BundleError("BUILD_INPUTS.json has an unsupported FrostKeys Rime bridge identity")
    if bridge.get("target") != RIME_OWNED_BRIDGE_TARGET:
        raise BundleError("BUILD_INPUTS.json Rime bridge target does not match the owned bridge")
    entries = bridge.get("inputs")
    if not isinstance(entries, list) or len(entries) != len(RIME_BRIDGE_INPUT_NAMES):
        raise BundleError("BUILD_INPUTS.json has an invalid FrostKeys Rime bridge input list")

    if bridge_dir.is_symlink() or not bridge_dir.is_dir():
        raise BundleError(f"Reviewed FrostKeys Rime bridge directory is missing or unsafe: {bridge_dir}")
    bridge_dir = bridge_dir.resolve()
    declared: dict[str, str] = {}
    for entry in entries:
        if not isinstance(entry, dict):
            raise BundleError("BUILD_INPUTS.json has an invalid FrostKeys Rime bridge input")
        name = entry.get("name")
        if not isinstance(name, str) or name not in RIME_BRIDGE_INPUT_NAMES or name in declared:
            raise BundleError("BUILD_INPUTS.json has an unexpected FrostKeys Rime bridge input")
        declared[name] = _require_sha256(entry.get("sha256"), f"Rime bridge input {name} SHA-256")
    if set(declared) != set(RIME_BRIDGE_INPUT_NAMES):
        raise BundleError("BUILD_INPUTS.json does not list every reviewed FrostKeys Rime bridge input")

    for name in RIME_BRIDGE_INPUT_NAMES:
        source = bridge_dir / name
        if source.is_symlink() or not source.is_file():
            raise BundleError(f"Reviewed FrostKeys Rime bridge input is missing or unsafe: {source}")
        if _digest_file(source) != declared[name]:
            raise BundleError(f"BUILD_INPUTS.json FrostKeys Rime bridge hash does not match: {name}")


def _verify_rime_core_evidence(raw: dict[str, Any], rime_core_lib: Path, build_root: Path) -> None:
    """Require exactly one hash-bound core library beside the owned bridge."""
    if rime_core_lib.is_symlink():
        raise BundleError(f"Rime core library may not be a symbolic link: {rime_core_lib}")
    rime_core_lib = rime_core_lib.resolve()
    if rime_core_lib.name != RIME_CORE_LIBRARY_NAME:
        raise BundleError(f"Rime core library must be named {RIME_CORE_LIBRARY_NAME}")
    expected_core = _build_output_file(build_root, f"native/{RIME_CORE_LIBRARY_NAME}", "Rime core library")
    if expected_core != rime_core_lib:
        raise BundleError("Rime core library does not match the required BUILD_INPUTS.json output path")
    if not rime_core_lib.is_file():
        raise BundleError("Rime core library is missing")

    dependencies = raw.get("nativeDependencies")
    if not isinstance(dependencies, list) or len(dependencies) != 1 or not isinstance(dependencies[0], dict):
        raise BundleError("BUILD_INPUTS.json must contain exactly one Rime core-library dependency")
    dependency = dependencies[0]
    if dependency.get("role") != "librime-core":
        raise BundleError("BUILD_INPUTS.json Rime native dependency has an unsupported role")
    if dependency.get("path") != f"native/{RIME_CORE_LIBRARY_NAME}":
        raise BundleError("BUILD_INPUTS.json Rime core dependency path is not canonical")
    _require_matching_file_hash(rime_core_lib, dependency.get("sha256"), "Rime core library")


def _verify_rime_offline_data_evidence(raw: dict[str, Any], declared_data: dict[str, Path]) -> None:
    """Validate the minimum real Pinyin/OpenCC data contract for a Rime APK.

    The generic hash list proves bytes, but it cannot tell whether the bytes
    represent an actual Pinyin scheme or just a few unrelated Rime resources.
    Require an explicit runtime layout and verify the embedded OpenCC evidence
    against the exact copied config/dictionary files.  This blocks a future
    package from advertising Traditional mode based on raw text dictionaries or
    a host build tree rather than the compiled ``.ocd2`` runtime payload.
    """
    runtime_data = raw.get("runtimeData")
    if not isinstance(runtime_data, dict) or runtime_data.get("schema") != 1:
        raise BundleError("BUILD_INPUTS.json has no supported Rime runtime-data evidence")
    if runtime_data.get("sharedDataPath") != "data/shared":
        raise BundleError("Rime shared data path must be the canonical data/shared directory")

    pinyin = runtime_data.get("pinyin")
    if not isinstance(pinyin, dict):
        raise BundleError("BUILD_INPUTS.json has no Rime Pinyin evidence")
    expected_pinyin = {
        "schemaId": "luna_pinyin",
        "schemaPath": "data/shared/luna_pinyin.schema.yaml",
        "dictionaryPath": "data/shared/luna_pinyin.dict.yaml",
        "defaultConfigPath": "data/shared/default.yaml",
    }
    for key, expected in expected_pinyin.items():
        if pinyin.get(key) != expected:
            raise BundleError(f"BUILD_INPUTS.json Rime Pinyin {key} is not the reviewed value")
        if key != "schemaId" and expected not in declared_data:
            raise BundleError(f"Rime Pinyin data file is missing from the exhaustive hash list: {expected}")

    opencc = runtime_data.get("opencc")
    if not isinstance(opencc, dict):
        raise BundleError("BUILD_INPUTS.json has no Rime OpenCC evidence")
    expected_opencc = {
        "manifestPath": "data/metadata/opencc-build-inputs.json",
        "licensePath": "data/licenses/opencc-LICENSE.txt",
        "simplifiedConfigPath": "data/shared/t2s.json",
        "traditionalConfigPath": "data/shared/t2tw.json",
    }
    for key, expected in expected_opencc.items():
        if opencc.get(key) != expected:
            raise BundleError(f"BUILD_INPUTS.json Rime OpenCC {key} is not the reviewed value")
        if expected not in declared_data:
            raise BundleError(f"Rime OpenCC data file is missing from the exhaustive hash list: {expected}")

    try:
        manifest = json.loads(declared_data[expected_opencc["manifestPath"]].read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise BundleError(f"Could not parse embedded Rime OpenCC manifest: {error}") from error
    _verify_embedded_rime_opencc_manifest(manifest, declared_data)


def _verify_embedded_rime_opencc_manifest(manifest: object, declared_data: dict[str, Path]) -> None:
    if not isinstance(manifest, dict):
        raise BundleError("Embedded Rime OpenCC manifest must be an object")
    if manifest.get("schema") != 1 or manifest.get("component") != "opencc-runtime-data":
        raise BundleError("Embedded Rime OpenCC manifest has an unsupported identity")
    if manifest.get("engine") != "rime":
        raise BundleError("Embedded Rime OpenCC manifest is not bound to Rime")
    checks = manifest.get("checks")
    if checks != {
        "t2s": {"input": "繁體中文", "output": "繁体中文"},
        "s2t": {"input": "汉字", "output": "漢字"},
    }:
        raise BundleError("Embedded Rime OpenCC manifest lacks the reviewed conversion checks")

    inputs = manifest.get("inputs")
    source = inputs.get("rimeSourceVerification") if isinstance(inputs, dict) else None
    if not isinstance(source, dict):
        raise BundleError("Embedded Rime OpenCC manifest has no source verification")
    if source.get("origin") != "https://github.com/rime/librime.git":
        raise BundleError("Embedded Rime OpenCC manifest source origin is not locked")
    if source.get("tagObject") != "5d7467d037938a17abb394f560f016adc9f76e14":
        raise BundleError("Embedded Rime OpenCC manifest tag object is not locked")
    if source.get("peeledCommit") != "de4700e9f6b75b109910613df907965e3cbe0567":
        raise BundleError("Embedded Rime OpenCC manifest checkout commit is not locked")
    submodules = source.get("submodules")
    if not isinstance(submodules, list) or not any(
        isinstance(entry, dict)
        and entry.get("path") == "deps/opencc"
        and entry.get("commit") == RIME_OPENCC_SUBMODULE_COMMIT
        for entry in submodules
    ):
        raise BundleError("Embedded Rime OpenCC manifest does not lock the OpenCC submodule")

    entries = manifest.get("files")
    if not isinstance(entries, list) or len(entries) != RIME_OPENCC_RUNTIME_FILE_COUNT:
        raise BundleError("Embedded Rime OpenCC manifest has an unexpected runtime file count")
    runtime_paths: set[str] = set()
    ocd2_count = 0
    config_count = 0
    for entry in entries:
        if not isinstance(entry, dict):
            raise BundleError("Embedded Rime OpenCC manifest contains an invalid file entry")
        runtime_path = entry.get("path")
        if not isinstance(runtime_path, str) or not runtime_path.startswith("runtime/"):
            raise BundleError("Embedded Rime OpenCC manifest path escapes its runtime directory")
        name = runtime_path.removeprefix("runtime/")
        if not _SEGMENT_RE.fullmatch(name) or runtime_path in runtime_paths:
            raise BundleError("Embedded Rime OpenCC manifest has an unsafe or repeated runtime path")
        runtime_paths.add(runtime_path)
        expected_data_path = f"data/shared/{name}"
        data_path = declared_data.get(expected_data_path)
        if data_path is None:
            raise BundleError(f"Embedded Rime OpenCC runtime file is missing: {expected_data_path}")
        expected_bytes = entry.get("bytes")
        if not isinstance(expected_bytes, int) or expected_bytes <= 0 or data_path.stat().st_size != expected_bytes:
            raise BundleError(f"Embedded Rime OpenCC byte count does not match: {runtime_path}")
        _require_matching_file_hash(data_path, entry.get("sha256"), f"OpenCC runtime file {runtime_path}")
        if name.endswith(".ocd2"):
            ocd2_count += 1
        elif name.endswith(".json"):
            config_count += 1
        else:
            raise BundleError("Embedded Rime OpenCC runtime has an unsupported file type")
    if ocd2_count != RIME_OPENCC_OCD2_COUNT or config_count != RIME_OPENCC_CONFIG_COUNT:
        raise BundleError("Embedded Rime OpenCC runtime does not contain the reviewed .ocd2/config counts")
    for required_name in ("t2s.json", "s2t.json", "t2tw.json", "STPhrases.ocd2", "TSCharacters.ocd2"):
        if f"runtime/{required_name}" not in runtime_paths:
            raise BundleError(f"Embedded Rime OpenCC runtime is missing {required_name}")


def _build_output_file(build_root: Path, raw_path: object, label: str) -> Path:
    if not isinstance(raw_path, str):
        raise BundleError(f"BUILD_INPUTS.json has invalid {label} path")
    _require_safe_relative_path(raw_path, f"BUILD_INPUTS.json {label} path")
    candidate = (build_root / raw_path).resolve()
    if not _is_within(candidate, build_root):
        raise BundleError(f"BUILD_INPUTS.json {label} path escapes build output")
    return candidate


def _is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _require_sha256(value: object, label: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", value):
        raise BundleError(f"BUILD_INPUTS.json has invalid {label}")
    return value.lower()


def _require_matching_file_hash(path: Path, expected: object, label: str) -> None:
    expected_hash = _require_sha256(expected, f"{label} SHA-256")
    actual_hash = _digest_file(path)
    if actual_hash != expected_hash:
        raise BundleError(f"BUILD_INPUTS.json {label} hash does not match output")


def _digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def collect_bundle_files(
    source: EngineSource,
    native_lib: Path,
    data_root: Path,
    notices: Iterable[Path],
    *,
    rime_core_lib: Path | None = None,
) -> list[BundleFile]:
    if native_lib.is_symlink():
        raise BundleError(f"Native library may not be a symbolic link: {native_lib}")
    if data_root.is_symlink():
        raise BundleError(f"Data root may not be a symbolic link: {data_root}")
    native_lib = native_lib.resolve()
    data_root = data_root.resolve()
    if not native_lib.is_file():
        raise BundleError(f"Native library must be a regular file: {native_lib}")
    if not data_root.is_dir():
        raise BundleError(f"Data root must be a regular directory: {data_root}")
    native_name = native_lib.name
    if not re.fullmatch(r"lib[A-Za-z0-9._-]{1,75}\.so", native_name):
        raise BundleError(f"Native library has an unsafe name: {native_name}")
    if source.id == "mozc" and native_name != MOZC_OWNED_LIBRARY_NAME:
        raise BundleError("Mozc bundle must package the FrostKeys-owned JNI bridge output")
    if source.id == "rime" and native_name != RIME_OWNED_LIBRARY_NAME:
        raise BundleError("Rime bundle must package the FrostKeys-owned JNI bridge output")

    files = [BundleFile(native_lib, f"lib/arm64-v8a/{native_name}")]
    if source.id == "rime":
        if rime_core_lib is None:
            raise BundleError("Rime package requires the matched librime core library")
        if rime_core_lib.is_symlink() or not rime_core_lib.is_file():
            raise BundleError(f"Rime core library must be a regular file: {rime_core_lib}")
        rime_core_lib = rime_core_lib.resolve()
        if rime_core_lib.name != RIME_CORE_LIBRARY_NAME:
            raise BundleError(f"Rime core library must be named {RIME_CORE_LIBRARY_NAME}")
        files.append(BundleFile(rime_core_lib, f"lib/arm64-v8a/{RIME_CORE_LIBRARY_NAME}"))
    for path in sorted(data_root.rglob("*")):
        if path.is_symlink():
            raise BundleError(f"Data root contains a symbolic link: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(data_root).as_posix()
        _require_safe_relative_path(relative, "data path")
        # Rime's native bridge receives the installed engine root and expects
        # `shared/` and `shared/opencc/` directly below it.  Do not retain the
        # old generic `data/` wrapper here: that output was an uncallable Rime
        # bundle even though every file hash happened to be valid.
        destination = relative if source.id == "rime" else f"data/{relative}"
        files.append(BundleFile(path, destination))
    if len(files) == (2 if source.id == "rime" else 1):
        raise BundleError("Offline engine data directory is empty")

    for notice in notices:
        if notice.is_symlink():
            raise BundleError(f"Notice may not be a symbolic link: {notice}")
        notice = notice.resolve()
        if not notice.is_file():
            raise BundleError(f"Notice must be a regular file: {notice}")
        _require_safe_relative_path(notice.name, "notice filename")
        files.append(BundleFile(notice, f"licenses/{notice.name}"))

    relative_paths = [item.relative_path for item in files]
    if len(set(relative_paths)) != len(relative_paths):
        raise BundleError("Bundle input paths collide after normalization")
    if len(files) >= MAX_BUNDLE_FILES:
        raise BundleError(f"Bundle has too many files ({len(files)} >= {MAX_BUNDLE_FILES})")
    return files


def write_bundle(
    *,
    output: Path,
    source: EngineSource,
    source_report: dict[str, Any],
    native_report: NativeElfReport,
    rime_core_report: NativeElfReport | None = None,
    bundle_version: str,
    asset_prefix: str,
    bundle_files: list[BundleFile],
    build_inputs: BuildInputsReport | None = None,
) -> None:
    stage = Path(tempfile.mkdtemp(prefix=f".{source.id}-bundle-", dir=output.parent))
    try:
        manifest_files: list[dict[str, Any]] = []
        total_bytes = 0
        for bundle_file in bundle_files:
            target = stage / bundle_file.relative_path
            size, digest = _copy_regular_file(bundle_file.source, target)
            _check_runtime_file_limit(bundle_file.relative_path, size)
            total_bytes = _safe_add_total(total_bytes, size)
            manifest_files.append(
                {
                    "asset": f"{asset_prefix}/{bundle_file.relative_path}",
                    "path": bundle_file.relative_path,
                    "bytes": size,
                    "sha256": digest,
                }
            )

        provenance_relative_path = "metadata/build-provenance.json"
        provenance = {
            "schema": 1,
            "tool": "tools/cjk/package-engine-bundle.py",
            "toolVersion": SCRIPT_VERSION,
            "sourceVerification": source_report,
            "nativeElf": {
                "path": native_report.path,
                "machine": native_report.machine,
                "loadAlignments": list(native_report.load_alignments),
                "requiredPageSize": PAGE_SIZE_16K,
            },
            "runtime": {
                "abi": "arm64-v8a",
                "network": "none",
                "assetPrefix": asset_prefix,
            },
        }
        if rime_core_report is not None:
            provenance["nativeElf"]["rimeCore"] = {
                "path": rime_core_report.path,
                "machine": rime_core_report.machine,
                "loadAlignments": list(rime_core_report.load_alignments),
                "requiredPageSize": PAGE_SIZE_16K,
            }
        if build_inputs is not None:
            provenance["buildInputs"] = {
                "path": "metadata/build-inputs.json",
                "sha256": build_inputs.sha256,
                "dataFileCount": build_inputs.data_file_count,
            }
        provenance_bytes = (json.dumps(provenance, indent=2, sort_keys=True) + "\n").encode("utf-8")
        provenance_target = stage / provenance_relative_path
        provenance_target.parent.mkdir(parents=True, exist_ok=True)
        provenance_target.write_bytes(provenance_bytes)
        _check_runtime_file_limit(provenance_relative_path, len(provenance_bytes))
        total_bytes = _safe_add_total(total_bytes, len(provenance_bytes))
        manifest_files.append(
            {
                "asset": f"{asset_prefix}/{provenance_relative_path}",
                "path": provenance_relative_path,
                "bytes": len(provenance_bytes),
                "sha256": hashlib.sha256(provenance_bytes).hexdigest(),
            }
        )
        if len(manifest_files) > MAX_BUNDLE_FILES:
            raise BundleError("Bundle exceeds runtime file count limit after metadata")

        manifest = {
            "schema": 1,
            "engine": source.id,
            "version": bundle_version,
            "commit": source.commit,
            "checkoutCommit": source.checkout_commit,
            "abi": "arm64-v8a",
            "source": source.manifest_source,
            "license": source.license,
            "totalBytes": total_bytes,
            "files": manifest_files,
        }
        (stage / "manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        # Every file that survives into the final directory was created in this
        # fresh staging directory.  ``output`` was checked absent before this
        # point, so rename is an all-or-nothing activation for build consumers.
        stage.rename(output)
    except BaseException:
        shutil.rmtree(stage, ignore_errors=True)
        raise


def validate_arm64_16k_elf(path: Path) -> NativeElfReport:
    """Parses ELF directly so the gate does not depend on host ``readelf`` tooling."""
    if path.is_symlink():
        raise BundleError(f"Native library may not be a symbolic link: {path}")
    path = path.resolve()
    try:
        with path.open("rb") as stream:
            header = stream.read(64)
            if len(header) != 64 or header[:4] != b"\x7fELF":
                raise BundleError(f"Native library is not an ELF file: {path}")
            if header[4] != 2 or header[5] != 1:
                raise BundleError(f"Native library is not a 64-bit little-endian ELF: {path}")
            unpacked = struct.unpack("<16sHHIQQQIHHHHHH", header)
            elf_type = unpacked[1]
            machine = unpacked[2]
            program_offset = unpacked[5]
            program_entry_size = unpacked[9]
            program_count = unpacked[10]
            if elf_type != 3:
                raise BundleError(f"Native library is not an ELF shared object: {path}")
            if machine != ELF_MACHINE_AARCH64:
                raise BundleError(f"Native library is not ARM64 (machine={machine}): {path}")
            if program_entry_size != 56 or program_count <= 0:
                raise BundleError(f"Native library has invalid ELF program headers: {path}")
            load_alignments: list[int] = []
            for index in range(program_count):
                stream.seek(program_offset + index * program_entry_size)
                entry = stream.read(program_entry_size)
                if len(entry) != program_entry_size:
                    raise BundleError(f"Native library has truncated program headers: {path}")
                p_type, _flags, p_offset, p_vaddr, _paddr, _filesz, _memsz, p_align = struct.unpack(
                    "<IIQQQQQQ", entry
                )
                if p_type != PT_LOAD:
                    continue
                if (
                    p_align < PAGE_SIZE_16K
                    or p_align % PAGE_SIZE_16K != 0
                    or (p_align & (p_align - 1)) != 0
                ):
                    raise BundleError(
                        f"Native library has non-16KiB PT_LOAD alignment 0x{p_align:x}: {path}"
                    )
                if p_offset % p_align != p_vaddr % p_align:
                    raise BundleError(f"Native library has invalid PT_LOAD congruence: {path}")
                load_alignments.append(p_align)
    except OSError as error:
        raise BundleError(f"Could not inspect native library {path}: {error}") from error
    if not load_alignments:
        raise BundleError(f"Native library has no PT_LOAD segments: {path}")
    return NativeElfReport(str(path), ELF_MACHINE_AARCH64, tuple(load_alignments))


def require_elf_needed_library(path: Path, required_library: str) -> None:
    """Prove that an ARM64 ELF bridge dynamically links the exact named core library.

    Rime's JNI wrapper is deliberately a separate shared object from ``librime.so``. A filename
    and matching hash manifest alone do not prove that the wrapper actually uses that core, so
    parse the ELF dynamic table directly rather than depending on a host ``readelf`` binary.
    Only the bounded ``DT_NEEDED``/``DT_STRTAB`` surface is read; malformed/missing tables fail
    closed before a bundle can be packaged.
    """
    if not re.fullmatch(r"lib[A-Za-z0-9._-]{1,75}\.so", required_library):
        raise BundleError(f"Required ELF dependency has an unsafe name: {required_library!r}")
    if path.is_symlink() or not path.is_file():
        raise BundleError(f"Native library is not a regular file: {path}")
    path = path.resolve()
    try:
        file_size = path.stat().st_size
        with path.open("rb") as stream:
            header = stream.read(64)
            if len(header) != 64 or header[:4] != b"\x7fELF" or header[4] != 2 or header[5] != 1:
                raise BundleError(f"Native library is not a 64-bit little-endian ELF: {path}")
            unpacked = struct.unpack("<16sHHIQQQIHHHHHH", header)
            program_offset = unpacked[5]
            program_entry_size = unpacked[9]
            program_count = unpacked[10]
            if program_entry_size != 56 or not 1 <= program_count <= 4096:
                raise BundleError(f"Native library has invalid ELF program headers: {path}")
            if program_offset < 64 or program_offset + program_entry_size * program_count > file_size:
                raise BundleError(f"Native library program headers escape the file: {path}")

            load_segments: list[tuple[int, int, int]] = []
            dynamic_segment: tuple[int, int] | None = None
            for index in range(program_count):
                stream.seek(program_offset + index * program_entry_size)
                entry = stream.read(program_entry_size)
                if len(entry) != program_entry_size:
                    raise BundleError(f"Native library has truncated program headers: {path}")
                p_type, _flags, p_offset, p_vaddr, _paddr, p_filesz, _p_memsz, _p_align = struct.unpack(
                    "<IIQQQQQQ", entry
                )
                if p_offset + p_filesz > file_size:
                    raise BundleError(f"Native library segment escapes the file: {path}")
                if p_type == PT_LOAD and p_filesz:
                    load_segments.append((p_offset, p_vaddr, p_filesz))
                elif p_type == PT_DYNAMIC:
                    if dynamic_segment is not None:
                        raise BundleError(f"Native library has multiple dynamic segments: {path}")
                    if p_filesz == 0 or p_filesz % 16 != 0 or p_filesz > 1024 * 1024:
                        raise BundleError(f"Native library has an invalid dynamic table size: {path}")
                    dynamic_segment = (p_offset, p_filesz)

            if dynamic_segment is None:
                raise BundleError(f"Native library has no dynamic dependency table: {path}")
            dynamic_offset, dynamic_size = dynamic_segment
            stream.seek(dynamic_offset)
            dynamic = stream.read(dynamic_size)
            if len(dynamic) != dynamic_size:
                raise BundleError(f"Native library dynamic table is truncated: {path}")
            string_table_address: int | None = None
            string_table_size: int | None = None
            needed_offsets: list[int] = []
            saw_null = False
            for offset in range(0, dynamic_size, 16):
                tag, value = struct.unpack_from("<qQ", dynamic, offset)
                if tag == DT_NULL:
                    saw_null = True
                    break
                if tag == DT_NEEDED:
                    needed_offsets.append(value)
                elif tag == DT_STRTAB:
                    string_table_address = value
                elif tag == DT_STRSZ:
                    string_table_size = value
            if not saw_null or string_table_address is None or string_table_size is None:
                raise BundleError(f"Native library has an incomplete dynamic table: {path}")
            if not 1 <= string_table_size <= 1024 * 1024:
                raise BundleError(f"Native library dynamic string table has invalid size: {path}")
            string_table_offset: int | None = None
            for file_offset, virtual_address, file_bytes in load_segments:
                if virtual_address <= string_table_address and (
                    string_table_address - virtual_address + string_table_size <= file_bytes
                ):
                    string_table_offset = file_offset + string_table_address - virtual_address
                    break
            if string_table_offset is None:
                raise BundleError(f"Native library dynamic string table is not file-backed: {path}")
            stream.seek(string_table_offset)
            string_table = stream.read(string_table_size)
            if len(string_table) != string_table_size:
                raise BundleError(f"Native library dynamic string table is truncated: {path}")
    except OSError as error:
        raise BundleError(f"Could not inspect native dependencies for {path}: {error}") from error

    needed: set[str] = set()
    for offset in needed_offsets:
        if offset >= len(string_table):
            raise BundleError(f"Native library has an out-of-range DT_NEEDED entry: {path}")
        end = string_table.find(b"\0", offset)
        if end < 0:
            raise BundleError(f"Native library has an unterminated DT_NEEDED entry: {path}")
        try:
            needed.add(string_table[offset:end].decode("ascii"))
        except UnicodeDecodeError as error:
            raise BundleError(f"Native library has a non-ASCII DT_NEEDED entry: {path}") from error
    if required_library not in needed:
        raise BundleError(
            f"Native library does not dynamically depend on required {required_library}: {path}"
        )


def _copy_regular_file(source: Path, target: Path) -> tuple[int, str]:
    if not source.is_file() or source.is_symlink():
        raise BundleError(f"Bundle input is not a regular file: {source}")
    target.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    copied = 0
    with source.open("rb") as input_stream, target.open("xb") as output_stream:
        while chunk := input_stream.read(1024 * 1024):
            copied += len(chunk)
            if copied > MAX_FILE_BYTES:
                raise BundleError(f"Bundle file exceeds runtime size limit: {source}")
            digest.update(chunk)
            output_stream.write(chunk)
    return copied, digest.hexdigest()


def _safe_add_total(total: int, item_size: int) -> int:
    updated = total + item_size
    if updated > MAX_BUNDLE_BYTES:
        raise BundleError(
            f"Bundle exceeds runtime size limit ({updated} > {MAX_BUNDLE_BYTES} bytes)"
        )
    return updated


def _check_runtime_file_limit(relative_path: str, size: int) -> None:
    _require_safe_relative_path(relative_path, "bundle path")
    if not 1 <= size <= MAX_FILE_BYTES:
        raise BundleError(f"Bundle file has invalid runtime size ({size}): {relative_path}")


def _require_safe_relative_path(value: str, label: str) -> None:
    if not value or value.startswith("/") or "\\" in value:
        raise BundleError(f"Unsafe {label}: {value!r}")
    segments = value.split("/")
    if any(not _SEGMENT_RE.fullmatch(segment) for segment in segments):
        raise BundleError(f"Unsafe {label}: {value!r}")


if __name__ == "__main__":
    raise SystemExit(main())
