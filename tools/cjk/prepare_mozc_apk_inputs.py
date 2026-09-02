#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify a built Mozc bundle and derive privacy-safe APK inputs atomically.

This is the only bridge from a locally produced, hash-complete CJK artifact to
the Android Gradle build.  It deliberately accepts no network input.  The
source bundle is first validated against the source/toolchain/bridge lock, then
copied into two generated trees:

* ``assets/cjk/mozc/commit-851c3fe`` for first-use hash verification and data
  extraction; and
* ``jni/arm64-v8a`` for Android's normal signed-native-library packaging.

The native library is staged only under ``jni/``. Android verifies the signed
APK entry before loading it; copying the same ELF into assets would add a large,
never-loaded duplicate and would not verify the final AGP-stripped ELF anyway.
The first-use manifest therefore covers only the files that are atomically
installed as runtime data. The output rewrites build provenance without local
absolute paths so the personal APK cannot leak the builder's filesystem layout.
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


ASSET_PREFIX = "cjk/mozc/commit-851c3fe"
NATIVE_RELATIVE_PATH = "lib/arm64-v8a/libfrostkeys_mozc.so"
DATA_RELATIVE_PATH = "data/mozc.data"
LICENSE_RELATIVE_PATH = "licenses/LICENSE"
BUILD_INPUTS_RELATIVE_PATH = "metadata/build-inputs.json"
PROVENANCE_RELATIVE_PATH = "metadata/build-provenance.json"
SOURCE_REQUIRED_FILES = {
    NATIVE_RELATIVE_PATH,
    DATA_RELATIVE_PATH,
    LICENSE_RELATIVE_PATH,
    BUILD_INPUTS_RELATIVE_PATH,
    PROVENANCE_RELATIVE_PATH,
}
# The external bundle manifest intentionally covers the reviewed native ELF too, but Android
# loads that exact payload from the signed `lib/` entry. Do not copy it into the lazy data tree.
APK_ASSET_PATHS = (
    DATA_RELATIVE_PATH,
    LICENSE_RELATIVE_PATH,
    BUILD_INPUTS_RELATIVE_PATH,
)
_SAFE_SEGMENT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,79}")


class PreparationError(RuntimeError):
    """The external CJK candidate must not be included in an APK."""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--assets-output", required=True, type=Path)
    parser.add_argument("--jni-output", required=True, type=Path)
    parser.add_argument("--lock", type=Path, default=TOOLS_DIR / "engine-sources.json")
    parser.add_argument("--toolchain-lock", type=Path, default=TOOLS_DIR / "toolchains.json")
    parser.add_argument("--bridge-dir", type=Path, default=TOOLS_DIR / "mozc_bridge")
    args = parser.parse_args()

    try:
        bundle = require_regular_directory(args.bundle, "Mozc bundle")
        source = load_source_lock(args.lock)["mozc"]
        manifest = load_json(bundle / "manifest.json", "Mozc bundle manifest")
        files = verify_source_bundle(
            bundle=bundle,
            manifest=manifest,
            source=source,
            toolchain_lock=args.toolchain_lock,
            bridge_dir=args.bridge_dir,
        )
        write_apk_inputs(
            bundle=bundle,
            manifest=manifest,
            source=source,
            files=files,
            assets_output=args.assets_output,
            jni_output=args.jni_output,
        )
    except (PreparationError, OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Mozc APK-input preparation failed: {error}", file=sys.stderr)
        return 1

    print(f"Prepared verified Mozc APK inputs from {bundle}")
    return 0


def verify_source_bundle(
    *,
    bundle: Path,
    manifest: dict[str, Any],
    source: Any,
    toolchain_lock: Path,
    bridge_dir: Path,
) -> dict[str, Path]:
    require_equal(manifest.get("schema"), 1, "Mozc bundle manifest schema")
    require_equal(manifest.get("engine"), "mozc", "Mozc bundle engine")
    require_equal(manifest.get("version"), source.version, "Mozc bundle version")
    require_equal(manifest.get("commit"), source.commit, "Mozc bundle source commit")
    require_equal(manifest.get("checkoutCommit"), source.checkout_commit, "Mozc bundle checkout commit")
    require_equal(manifest.get("source"), source.manifest_source, "Mozc bundle source URL")
    require_equal(manifest.get("license"), source.license, "Mozc bundle license")
    require_equal(manifest.get("abi"), "arm64-v8a", "Mozc bundle ABI")

    entries = manifest.get("files")
    if not isinstance(entries, list) or len(entries) != len(SOURCE_REQUIRED_FILES):
        raise PreparationError("Mozc bundle manifest must contain the reviewed five-file payload")
    files: dict[str, Path] = {}
    total = 0
    for entry in entries:
        if not isinstance(entry, dict):
            raise PreparationError("Mozc bundle manifest has a non-object file entry")
        relative = require_safe_relative_path(entry.get("path"), "Mozc bundle file path")
        if relative in files:
            raise PreparationError(f"Mozc bundle manifest repeats file path: {relative}")
        if entry.get("asset") != f"{ASSET_PREFIX}/{relative}":
            raise PreparationError(f"Mozc bundle asset path is not canonical: {relative}")
        expected_size = entry.get("bytes")
        if not isinstance(expected_size, int) or expected_size <= 0:
            raise PreparationError(f"Mozc bundle has invalid byte count for {relative}")
        expected_hash = require_sha256(entry.get("sha256"), f"Mozc bundle hash for {relative}")
        path = contained_regular_file(bundle, relative)
        if path.stat().st_size != expected_size or digest_file(path) != expected_hash:
            raise PreparationError(f"Mozc bundle file does not match its manifest: {relative}")
        files[relative] = path
        total += expected_size
    if set(files) != SOURCE_REQUIRED_FILES:
        raise PreparationError("Mozc bundle files differ from the reviewed payload allowlist")
    require_equal(manifest.get("totalBytes"), total, "Mozc bundle total byte count")
    ensure_bundle_has_no_unlisted_files(bundle, files)

    # A malformed native asset must never make it as far as Android packaging.
    packager.validate_arm64_16k_elf(files[NATIVE_RELATIVE_PATH])
    verify_build_inputs(
        build_inputs=load_json(files[BUILD_INPUTS_RELATIVE_PATH], "Mozc build inputs"),
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
    require_equal(build_inputs.get("schema"), 1, "Mozc build-input schema")
    require_equal(build_inputs.get("engine"), "mozc", "Mozc build-input engine")
    require_equal(build_inputs.get("sourceCommit"), source.checkout_commit, "Mozc build-input source commit")
    policy = packager._load_toolchain_policy(toolchain_lock)
    toolchain = build_inputs.get("toolchain")
    if not isinstance(toolchain, dict):
        raise PreparationError("Mozc build inputs have no toolchain record")
    for key in ("ndkRevision", "ndkArchiveSha256", "bazelVersion", "bazelSha256"):
        require_equal(toolchain.get(key), policy[key], f"Mozc build-input toolchain {key}")
    targets = build_inputs.get("targets")
    if not isinstance(targets, list) or set(targets) != packager.MOZC_REQUIRED_TARGETS:
        raise PreparationError("Mozc build inputs do not prove the owned native and data targets")
    packager._verify_mozc_bridge_evidence(build_inputs, bridge_dir)

    native = build_inputs.get("native")
    if not isinstance(native, dict):
        raise PreparationError("Mozc build inputs have no native library record")
    require_equal(native.get("path"), "native/libfrostkeys_mozc.so", "Mozc native output path")
    require_equal(
        require_sha256(native.get("sha256"), "Mozc native output hash"),
        digest_file(files[NATIVE_RELATIVE_PATH]),
        "Mozc native library hash",
    )
    data = build_inputs.get("data")
    if not isinstance(data, list) or len(data) != 1 or not isinstance(data[0], dict):
        raise PreparationError("Mozc build inputs must contain exactly one offline data file")
    require_equal(data[0].get("path"), "data/mozc.data", "Mozc data output path")
    require_equal(
        require_sha256(data[0].get("sha256"), "Mozc data output hash"),
        digest_file(files[DATA_RELATIVE_PATH]),
        "Mozc data file hash",
    )


def write_apk_inputs(
    *,
    bundle: Path,
    manifest: dict[str, Any],
    source: Any,
    files: dict[str, Path],
    assets_output: Path,
    jni_output: Path,
) -> None:
    assets_output = safe_output_directory(assets_output, "assets output")
    jni_output = safe_output_directory(jni_output, "JNI output")
    if assets_output == jni_output:
        raise PreparationError("Mozc assets and JNI output directories must be distinct")

    assets_stage = make_stage_directory(assets_output)
    jni_stage = make_stage_directory(jni_output)
    try:
        asset_root = assets_stage / ASSET_PREFIX
        for relative in APK_ASSET_PATHS:
            destination = asset_root / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(files[relative], destination)

        sanitized_provenance = create_sanitized_provenance(manifest, source, files)
        provenance_destination = asset_root / PROVENANCE_RELATIVE_PATH
        provenance_destination.parent.mkdir(parents=True, exist_ok=True)
        write_canonical_json(provenance_destination, sanitized_provenance)

        derived_files = []
        for relative in (*APK_ASSET_PATHS, PROVENANCE_RELATIVE_PATH):
            path = asset_root / relative
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
            "engine": "mozc",
            "files": derived_files,
            "license": source.license,
            "schema": 1,
            "source": source.manifest_source,
            "totalBytes": sum(entry["bytes"] for entry in derived_files),
            "version": source.version,
        }
        write_canonical_json(asset_root / "manifest.json", derived_manifest)

        jni_library = jni_stage / "arm64-v8a" / "libfrostkeys_mozc.so"
        jni_library.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(files[NATIVE_RELATIVE_PATH], jni_library)
        if digest_file(jni_library) != digest_file(files[NATIVE_RELATIVE_PATH]):
            raise PreparationError("Copied JNI library did not retain its reviewed hash")
        packager.validate_arm64_16k_elf(jni_library)

        replace_output_directory(assets_output, assets_stage)
        assets_stage = None
        replace_output_directory(jni_output, jni_stage)
        jni_stage = None
    finally:
        for stage in (assets_stage, jni_stage):
            if stage is not None and stage.exists():
                shutil.rmtree(stage)


def create_sanitized_provenance(
    manifest: dict[str, Any], source: Any, files: dict[str, Path]
) -> dict[str, Any]:
    build_inputs = load_json(files[BUILD_INPUTS_RELATIVE_PATH], "Mozc build inputs")
    return {
        "bundleManifestSha256": canonical_json_sha256(manifest),
        "engine": "mozc",
        "license": source.license,
        "network": "none",
        # This is provenance for the reviewed JNI input. It is not listed in the first-use
        # manifest because Android loads the final, APK-signature-protected library from `lib/`.
        "native": {
            "apkPath": NATIVE_RELATIVE_PATH,
            "sha256": build_inputs["native"]["sha256"],
        },
        "runtime": {"abi": "arm64-v8a", "assetPrefix": ASSET_PREFIX},
        "schema": 1,
        "source": {
            "checkoutCommit": source.checkout_commit,
            "commit": source.commit,
            "url": source.manifest_source,
        },
        "toolchain": build_inputs["toolchain"],
        "bridge": build_inputs["bridge"],
    }


def ensure_bundle_has_no_unlisted_files(bundle: Path, files: dict[str, Path]) -> None:
    allowed = {bundle / "manifest.json", *files.values()}
    for candidate in bundle.rglob("*"):
        if candidate.is_symlink():
            raise PreparationError(f"Mozc bundle contains a symbolic link: {candidate}")
        if candidate.is_file() and candidate.resolve() not in allowed:
            raise PreparationError(f"Mozc bundle contains an unlisted file: {candidate.relative_to(bundle)}")


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
        raise PreparationError(f"Mozc bundle file escapes bundle root: {relative}") from error
    if path.is_symlink() or not path.is_file():
        raise PreparationError(f"Mozc bundle file is missing or unsafe: {relative}")
    return path


def require_safe_relative_path(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value or value.startswith("/") or "\\" in value:
        raise PreparationError(f"{label} is not a safe relative path")
    parts = value.split("/")
    if any(not _SAFE_SEGMENT.fullmatch(part) for part in parts):
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
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def write_canonical_json(path: Path, value: dict[str, Any]) -> None:
    contents = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
    path.write_text(contents, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    raise SystemExit(main())
