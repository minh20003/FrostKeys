#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify a complete Rime package and derive privacy-safe APK inputs atomically.

The external Rime package has already passed ``package-engine-bundle.py``. This second verifier
is deliberately independent and applies the same source/toolchain/bridge/data contract immediately
before Gradle can put any native/data file into the APK. It puts native libraries only in Android's
signed JNI directory and copies only lazy-installed data into assets, then rewrites provenance
without local build paths.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
from typing import Any


TOOLS_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS_DIR))
_PACKAGER_SPEC = importlib.util.spec_from_file_location(
    "frostkeys_cjk_packager", TOOLS_DIR / "package-engine-bundle.py"
)
assert _PACKAGER_SPEC and _PACKAGER_SPEC.loader
packager = importlib.util.module_from_spec(_PACKAGER_SPEC)
sys.modules[_PACKAGER_SPEC.name] = packager
_PACKAGER_SPEC.loader.exec_module(packager)

from cjk_source_lock import load_source_lock  # noqa: E402


ASSET_PREFIX = "cjk/rime/1.16.1"
BRIDGE_PATH = "lib/arm64-v8a/libfrostkeys_rime.so"
CORE_PATH = "lib/arm64-v8a/librime.so"
BUILD_INPUTS_PATH = "metadata/build-inputs.json"
PROVENANCE_PATH = "metadata/build-provenance.json"
REQUIRED_PATHS = {
    BRIDGE_PATH,
    CORE_PATH,
    BUILD_INPUTS_PATH,
    "shared/default.yaml",
    "shared/luna_pinyin.schema.yaml",
    "shared/luna_pinyin.dict.yaml",
    "shared/t2s.json",
    "shared/t2tw.json",
    "shared/STPhrases.ocd2",
    "shared/TSCharacters.ocd2",
    "metadata/opencc-build-inputs.json",
    "licenses/librime-LICENSE.txt",
    "licenses/opencc-LICENSE.txt",
    "licenses/boost-LICENSE_1_0.txt",
    PROVENANCE_PATH,
}
# Rime's bridge and core are verified source-bundle inputs and are staged once in `jni/`. They
# must never be duplicated into the first-use asset tree: the runtime loads Android's signed
# `lib/arm64-v8a` entries, not copies below `files/cjk`.
APK_ASSET_EXCLUDED_PATHS = {BRIDGE_PATH, CORE_PATH, PROVENANCE_PATH}
_SAFE_SEGMENT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}")


class PreparationError(RuntimeError):
    pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--assets-output", required=True, type=Path)
    parser.add_argument("--jni-output", required=True, type=Path)
    parser.add_argument("--lock", type=Path, default=TOOLS_DIR / "engine-sources.json")
    parser.add_argument("--toolchain-lock", type=Path, default=TOOLS_DIR / "toolchains.json")
    parser.add_argument("--bridge-dir", type=Path, default=TOOLS_DIR / "rime_bridge")
    args = parser.parse_args()
    try:
        bundle = require_regular_directory(args.bundle, "Rime bundle")
        source = load_source_lock(args.lock)["rime"]
        manifest = load_json(bundle / "manifest.json", "Rime bundle manifest")
        files = verify_source_bundle(
            bundle=bundle,
            manifest=manifest,
            source=source,
            toolchain_lock=args.toolchain_lock,
            bridge_dir=args.bridge_dir,
        )
        write_apk_inputs(
            manifest=manifest,
            source=source,
            files=files,
            assets_output=args.assets_output,
            jni_output=args.jni_output,
        )
    except (PreparationError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Rime APK-input preparation failed: {error}", file=sys.stderr)
        return 1
    print(f"Prepared verified Rime APK inputs from {bundle}")
    return 0


def verify_source_bundle(
    *,
    bundle: Path,
    manifest: dict[str, Any],
    source: Any,
    toolchain_lock: Path,
    bridge_dir: Path,
) -> dict[str, Path]:
    require_equal(manifest.get("schema"), 1, "Rime bundle manifest schema")
    require_equal(manifest.get("engine"), "rime", "Rime bundle engine")
    require_equal(manifest.get("version"), source.version, "Rime bundle version")
    require_equal(manifest.get("commit"), source.commit, "Rime bundle source commit")
    require_equal(manifest.get("checkoutCommit"), source.checkout_commit, "Rime bundle checkout commit")
    require_equal(manifest.get("source"), source.manifest_source, "Rime bundle source URL")
    require_equal(manifest.get("license"), source.license, "Rime bundle license")
    require_equal(manifest.get("abi"), "arm64-v8a", "Rime bundle ABI")

    entries = manifest.get("files")
    if not isinstance(entries, list) or not 1 <= len(entries) <= packager.MAX_BUNDLE_FILES:
        raise PreparationError("Rime bundle manifest has an invalid file list")
    files: dict[str, Path] = {}
    total = 0
    for entry in entries:
        if not isinstance(entry, dict):
            raise PreparationError("Rime bundle manifest has a non-object file entry")
        relative = require_safe_relative_path(entry.get("path"), "Rime bundle file path")
        if relative in files:
            raise PreparationError(f"Rime bundle manifest repeats file path: {relative}")
        if entry.get("asset") != f"{ASSET_PREFIX}/{relative}":
            raise PreparationError(f"Rime bundle asset path is not canonical: {relative}")
        expected_size = entry.get("bytes")
        if not isinstance(expected_size, int) or not 1 <= expected_size <= packager.MAX_FILE_BYTES:
            raise PreparationError(f"Rime bundle has invalid byte count for {relative}")
        expected_hash = require_sha256(entry.get("sha256"), f"Rime bundle hash for {relative}")
        path = contained_regular_file(bundle, relative)
        if path.stat().st_size != expected_size or digest_file(path) != expected_hash:
            raise PreparationError(f"Rime bundle file does not match its manifest: {relative}")
        files[relative] = path
        total += expected_size
        if total > packager.MAX_BUNDLE_BYTES:
            raise PreparationError("Rime bundle exceeds the maximum size")
    if not REQUIRED_PATHS.issubset(files):
        missing = sorted(REQUIRED_PATHS - set(files))
        raise PreparationError(f"Rime bundle is missing required payload: {missing}")
    require_equal(manifest.get("totalBytes"), total, "Rime bundle total byte count")
    ensure_bundle_has_no_unlisted_files(bundle, files)

    packager.validate_arm64_16k_elf(files[BRIDGE_PATH])
    packager.validate_arm64_16k_elf(files[CORE_PATH])
    packager.require_elf_needed_library(files[BRIDGE_PATH], "librime.so")
    verify_build_inputs(
        build_inputs=load_json(files[BUILD_INPUTS_PATH], "Rime build inputs"),
        source=source,
        toolchain_lock=toolchain_lock,
        bridge_dir=bridge_dir,
        files=files,
    )
    return files


def verify_build_inputs(
    *,
    build_inputs: dict[str, Any],
    source: Any,
    toolchain_lock: Path,
    bridge_dir: Path,
    files: dict[str, Path],
) -> None:
    require_equal(build_inputs.get("schema"), 1, "Rime build-input schema")
    require_equal(build_inputs.get("engine"), "rime", "Rime build-input engine")
    require_equal(build_inputs.get("sourceCommit"), source.checkout_commit, "Rime build-input source commit")
    policy = packager._load_toolchain_policy(toolchain_lock)
    toolchain = build_inputs.get("toolchain")
    if not isinstance(toolchain, dict):
        raise PreparationError("Rime build inputs have no toolchain record")
    for key in ("ndkRevision", "ndkArchiveSha256"):
        require_equal(toolchain.get(key), policy[key], f"Rime build-input toolchain {key}")
    packager._verify_rime_bridge_evidence(build_inputs, bridge_dir)

    native = build_inputs.get("native")
    if not isinstance(native, dict):
        raise PreparationError("Rime build inputs have no owned bridge record")
    require_equal(native.get("path"), "native/libfrostkeys_rime.so", "Rime bridge output path")
    require_equal(
        require_sha256(native.get("sha256"), "Rime bridge output hash"),
        digest_file(files[BRIDGE_PATH]),
        "Rime bridge library hash",
    )
    dependencies = build_inputs.get("nativeDependencies")
    if not isinstance(dependencies, list) or len(dependencies) != 1 or not isinstance(dependencies[0], dict):
        raise PreparationError("Rime build inputs must contain exactly one core dependency")
    core = dependencies[0]
    require_equal(core.get("role"), "librime-core", "Rime core dependency role")
    require_equal(core.get("path"), "native/librime.so", "Rime core dependency path")
    require_equal(
        require_sha256(core.get("sha256"), "Rime core output hash"),
        digest_file(files[CORE_PATH]),
        "Rime core library hash",
    )

    data_entries = build_inputs.get("data")
    if not isinstance(data_entries, list) or not data_entries:
        raise PreparationError("Rime build inputs have no offline data list")
    declared_data: dict[str, Path] = {}
    for entry in data_entries:
        if not isinstance(entry, dict):
            raise PreparationError("Rime build inputs have an invalid data entry")
        raw_path = entry.get("path")
        if not isinstance(raw_path, str) or not raw_path.startswith("data/"):
            raise PreparationError("Rime build-input data path escapes data root")
        relative = raw_path.removeprefix("data/")
        path = files.get(relative)
        if path is None:
            raise PreparationError(f"Rime build-input data file is missing from bundle: {raw_path}")
        if raw_path in declared_data:
            raise PreparationError(f"Rime build inputs repeat a data entry: {raw_path}")
        require_equal(
            require_sha256(entry.get("sha256"), f"Rime data hash {raw_path}"),
            digest_file(path),
            f"Rime data hash {raw_path}",
        )
        declared_data[raw_path] = path
    package_data_paths = {
        f"data/{relative}": path
        for relative, path in files.items()
        if relative not in {BRIDGE_PATH, CORE_PATH, BUILD_INPUTS_PATH, PROVENANCE_PATH}
    }
    if set(declared_data) != set(package_data_paths):
        raise PreparationError("Rime package data files differ from the exhaustive build-input list")
    packager._verify_rime_offline_data_evidence(build_inputs, declared_data)

    rime_build = build_inputs.get("rimeBuild")
    if not isinstance(rime_build, dict) or rime_build.get("schema") != 1:
        raise PreparationError("Rime build inputs have no reproducible builder evidence")
    boost = rime_build.get("boost")
    if not isinstance(boost, dict) or boost.get("version") != "1.89.0":
        raise PreparationError("Rime build inputs have invalid Boost evidence")
    require_sha256(boost.get("archiveSha256"), "Rime Boost archive hash")
    tool_hashes = rime_build.get("toolHashes")
    if not isinstance(tool_hashes, dict):
        raise PreparationError("Rime build inputs have no tool hashes")
    expected_hashes = {
        "build-rime-arm64.sh": digest_file(TOOLS_DIR / "build-rime-arm64.sh"),
        "engine-sources.json": digest_file(TOOLS_DIR / "engine-sources.json"),
    }
    for name, expected in expected_hashes.items():
        require_equal(tool_hashes.get(name), expected, f"Rime builder hash {name}")
    source_evidence = rime_build.get("sourceVerification")
    if not isinstance(source_evidence, dict):
        raise PreparationError("Rime build inputs have no source verification evidence")
    require_equal(source_evidence.get("origin"), source.checkout_source, "Rime builder source origin")
    require_equal(source_evidence.get("tagObject"), source.commit, "Rime builder tag object")
    require_equal(source_evidence.get("peeledCommit"), source.checkout_commit, "Rime builder checkout commit")


def write_apk_inputs(
    *,
    manifest: dict[str, Any],
    source: Any,
    files: dict[str, Path],
    assets_output: Path,
    jni_output: Path,
) -> None:
    assets_output = safe_output_directory(assets_output, "assets output")
    jni_output = safe_output_directory(jni_output, "JNI output")
    if assets_output == jni_output:
        raise PreparationError("Rime assets and JNI output directories must be distinct")
    assets_stage = make_stage_directory(assets_output)
    jni_stage = make_stage_directory(jni_output)
    try:
        asset_root = assets_stage / ASSET_PREFIX
        # Never copy original provenance (it may contain absolute local paths), nor native ELF
        # files (Android loads the signed JNI entries). Every lazy-installed data file is copied
        # byte-for-byte and checked once more after copying.
        for relative, source_file in files.items():
            if relative in APK_ASSET_EXCLUDED_PATHS:
                continue
            destination = asset_root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source_file, destination)
            if digest_file(destination) != digest_file(source_file):
                raise PreparationError(f"Copied Rime asset did not retain its reviewed hash: {relative}")

        provenance = create_sanitized_provenance(manifest, source, files)
        provenance_destination = asset_root / PROVENANCE_PATH
        provenance_destination.parent.mkdir(parents=True, exist_ok=True)
        write_canonical_json(provenance_destination, provenance)

        derived_files = []
        for path in sorted(asset_root.rglob("*")):
            if path.is_symlink() or not path.is_file():
                continue
            relative = path.relative_to(asset_root).as_posix()
            derived_files.append(
                {
                    "asset": f"{ASSET_PREFIX}/{relative}",
                    "bytes": path.stat().st_size,
                    "path": relative,
                    "sha256": digest_file(path),
                }
            )
        derived_manifest = {
            "abi": "arm64-v8a",
            "checkoutCommit": source.checkout_commit,
            "commit": source.commit,
            "engine": "rime",
            "files": derived_files,
            "license": source.license,
            "schema": 1,
            "source": source.manifest_source,
            "totalBytes": sum(entry["bytes"] for entry in derived_files),
            "version": source.version,
        }
        write_canonical_json(asset_root / "manifest.json", derived_manifest)

        for name, source_path in (
            ("libfrostkeys_rime.so", files[BRIDGE_PATH]),
            ("librime.so", files[CORE_PATH]),
        ):
            destination = jni_stage / "arm64-v8a" / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source_path, destination)
            if digest_file(destination) != digest_file(source_path):
                raise PreparationError(f"Copied Rime JNI library did not retain its reviewed hash: {name}")
            packager.validate_arm64_16k_elf(destination)
        packager.require_elf_needed_library(
            jni_stage / "arm64-v8a" / "libfrostkeys_rime.so", "librime.so"
        )

        replace_output_directory(assets_output, assets_stage)
        assets_stage = None
        replace_output_directory(jni_output, jni_stage)
        jni_stage = None
    finally:
        for stage in (assets_stage, jni_stage):
            if stage is not None and stage.exists():
                shutil.rmtree(stage)


def create_sanitized_provenance(manifest: dict[str, Any], source: Any, files: dict[str, Path]) -> dict[str, Any]:
    build_inputs = load_json(files[BUILD_INPUTS_PATH], "Rime build inputs")
    bridge = build_inputs["bridge"]
    runtime_data = build_inputs["runtimeData"]
    rime_build = build_inputs["rimeBuild"]
    source_verification = rime_build["sourceVerification"]
    return {
        "bundleManifestSha256": canonical_json_sha256(manifest),
        "bridge": {
            "schema": bridge["schema"],
            "kind": bridge["kind"],
            "target": bridge["target"],
            "inputs": [
                {"name": entry["name"], "sha256": entry["sha256"]}
                for entry in bridge["inputs"]
            ],
        },
        "engine": "rime",
        "license": source.license,
        # Provenance for the reviewed bridge; its final loadable copy is protected by the APK
        # signature and staged in `lib/`, not duplicated into the extractable asset manifest.
        "native": {
            "apkPath": BRIDGE_PATH,
            "sha256": build_inputs["native"]["sha256"],
        },
        "nativeDependencies": [
            {"role": entry["role"], "path": entry["path"], "sha256": entry["sha256"]}
            for entry in build_inputs["nativeDependencies"]
        ],
        "network": "none",
        "rimeBuild": {
            "schema": rime_build["schema"],
            "boost": {
                "version": rime_build["boost"]["version"],
                "archiveSha256": rime_build["boost"]["archiveSha256"],
            },
            "toolHashes": {
                "build-rime-arm64.sh": rime_build["toolHashes"]["build-rime-arm64.sh"],
                "engine-sources.json": rime_build["toolHashes"]["engine-sources.json"],
            },
            "sourceVerification": {
                "origin": source_verification["origin"],
                "tagObject": source_verification["tagObject"],
                "peeledCommit": source_verification["peeledCommit"],
                "submodules": [
                    {"commit": entry["commit"], "path": entry["path"]}
                    for entry in source_verification["submodules"]
                ],
            },
        },
        "runtime": {"abi": "arm64-v8a", "assetPrefix": ASSET_PREFIX},
        "runtimeData": {
            "schema": runtime_data["schema"],
            "sharedDataPath": runtime_data["sharedDataPath"],
            "pinyin": {
                "schemaId": runtime_data["pinyin"]["schemaId"],
                "schemaPath": runtime_data["pinyin"]["schemaPath"],
                "dictionaryPath": runtime_data["pinyin"]["dictionaryPath"],
                "defaultConfigPath": runtime_data["pinyin"]["defaultConfigPath"],
            },
            "opencc": {
                "manifestPath": runtime_data["opencc"]["manifestPath"],
                "licensePath": runtime_data["opencc"]["licensePath"],
                "simplifiedConfigPath": runtime_data["opencc"]["simplifiedConfigPath"],
                "traditionalConfigPath": runtime_data["opencc"]["traditionalConfigPath"],
            },
        },
        "schema": 1,
        "source": {
            "checkoutCommit": source.checkout_commit,
            "commit": source.commit,
            "url": source.manifest_source,
        },
        "toolchain": {
            "ndkRevision": build_inputs["toolchain"]["ndkRevision"],
            "ndkArchiveSha256": build_inputs["toolchain"]["ndkArchiveSha256"],
        },
    }


def ensure_bundle_has_no_unlisted_files(bundle: Path, files: dict[str, Path]) -> None:
    allowed = {bundle / "manifest.json", *files.values()}
    for candidate in bundle.rglob("*"):
        if candidate.is_symlink():
            raise PreparationError(f"Rime bundle contains a symbolic link: {candidate}")
        if candidate.is_file() and candidate.resolve() not in allowed:
            raise PreparationError(f"Rime bundle contains an unlisted file: {candidate.relative_to(bundle)}")


def load_json(path: Path, label: str) -> dict[str, Any]:
    if path.is_symlink() or not path.is_file():
        raise PreparationError(f"{label} is missing or unsafe: {path}")
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise PreparationError(f"Could not read {label}: {error}") from error
    if not isinstance(value, dict):
        raise PreparationError(f"{label} must be a JSON object")
    return value


def require_regular_directory(path: Path, label: str) -> Path:
    if path.is_symlink() or not path.is_dir():
        raise PreparationError(f"{label} must be a non-symlink directory: {path}")
    return path.resolve()


def safe_output_directory(path: Path, label: str) -> Path:
    path = path.absolute()
    if path.parent == path or not path.name or path.name in {".", ".."}:
        raise PreparationError(f"Refusing unsafe {label}: {path}")
    if not path.parent.is_dir():
        raise PreparationError(f"Parent directory for {label} does not exist: {path.parent}")
    return path


def make_stage_directory(target: Path) -> Path:
    return Path(tempfile.mkdtemp(prefix=f".{target.name}.", suffix=".staging", dir=target.parent))


def replace_output_directory(target: Path, staging: Path) -> None:
    backup = target.with_name(f".{target.name}.{os.getpid()}.backup")
    if backup.exists():
        raise PreparationError(f"Refusing to reuse stale output backup: {backup}")
    had_target = target.exists()
    if had_target:
        target.rename(backup)
    try:
        staging.rename(target)
    except BaseException:
        if had_target and not target.exists() and backup.exists():
            backup.rename(target)
        raise
    if backup.exists():
        shutil.rmtree(backup)


def contained_regular_file(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    try:
        path.relative_to(root)
    except ValueError as error:
        raise PreparationError(f"Rime bundle file escapes bundle root: {relative}") from error
    if path.is_symlink() or not path.is_file():
        raise PreparationError(f"Rime bundle file is missing or unsafe: {relative}")
    return path


def require_safe_relative_path(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or value.startswith("/") or "\\" in value:
        raise PreparationError(f"{label} is not a safe relative path")
    if any(not _SAFE_SEGMENT.fullmatch(part) for part in value.split("/")):
        raise PreparationError(f"{label} is not a safe relative path")
    return value


def require_sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-fA-F]{64}", value):
        raise PreparationError(f"{label} is not a SHA-256 digest")
    return value.lower()


def require_equal(actual: Any, expected: Any, label: str) -> None:
    if actual != expected:
        raise PreparationError(f"{label} does not match the reviewed value")


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_json_sha256(value: dict[str, Any]) -> str:
    return hashlib.sha256(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    ).hexdigest()


def write_canonical_json(path: Path, value: dict[str, Any]) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
        newline="\n",
    )


if __name__ == "__main__":
    raise SystemExit(main())
