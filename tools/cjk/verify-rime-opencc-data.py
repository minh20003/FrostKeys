#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify the small promoted OpenCC runtime tree emitted by the Rime builder.

This verifier intentionally accepts only the reviewed output layout, rather
than treating a successful host compilation as enough evidence.  In
particular, it rejects an output that accidentally retained a full source tree
or build directory, because those files are not immutable runtime data and
must never be confused with bundle payload.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
from typing import Any


EXPECTED_COMPONENT = "opencc-runtime-data"
EXPECTED_ENGINE = "rime"
EXPECTED_SCHEMA = 1
EXPECTED_OCD2_COUNT = 16
EXPECTED_CONFIG_COUNT = 14
ALLOWED_TOP_LEVEL = {"runtime", "metadata", "licenses"}


class VerificationError(RuntimeError):
    pass


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_regular(path: Path, label: str) -> None:
    if path.is_symlink() or not path.is_file():
        raise VerificationError(f"{label} must be a regular file: {path}")


def verify(bundle: Path) -> dict[str, Any]:
    bundle = bundle.resolve()
    if bundle.is_symlink() or not bundle.is_dir():
        raise VerificationError(f"OpenCC bundle must be a regular directory: {bundle}")
    top_level = {entry.name for entry in bundle.iterdir()}
    if top_level != ALLOWED_TOP_LEVEL:
        raise VerificationError(
            f"OpenCC bundle has unexpected top-level entries: {sorted(top_level - ALLOWED_TOP_LEVEL)}"
        )
    for name in ALLOWED_TOP_LEVEL:
        directory = bundle / name
        if directory.is_symlink() or not directory.is_dir():
            raise VerificationError(f"OpenCC bundle directory is missing or unsafe: {directory}")

    manifest_path = bundle / "metadata" / "opencc-build-inputs.json"
    require_regular(manifest_path, "OpenCC manifest")
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise VerificationError(f"Could not parse OpenCC manifest: {error}") from error
    if not isinstance(manifest, dict):
        raise VerificationError("OpenCC manifest root must be an object")
    if manifest.get("schema") != EXPECTED_SCHEMA:
        raise VerificationError("OpenCC manifest schema is unsupported")
    if manifest.get("component") != EXPECTED_COMPONENT or manifest.get("engine") != EXPECTED_ENGINE:
        raise VerificationError("OpenCC manifest does not identify the locked Rime runtime data")

    source = manifest.get("inputs", {}).get("rimeSourceVerification", {})
    if not isinstance(source, dict):
        raise VerificationError("OpenCC manifest has no Rime source evidence")
    if source.get("origin") != "https://github.com/rime/librime.git":
        raise VerificationError("OpenCC manifest Rime origin is not locked")
    if source.get("tagObject") != "5d7467d037938a17abb394f560f016adc9f76e14":
        raise VerificationError("OpenCC manifest Rime tag object is not locked")
    if source.get("peeledCommit") != "de4700e9f6b75b109910613df907965e3cbe0567":
        raise VerificationError("OpenCC manifest Rime checkout commit is not locked")
    submodules = source.get("submodules")
    if not isinstance(submodules, list) or not any(
        entry.get("path") == "deps/opencc" and entry.get("commit") == "556ed22496d650bd0b13b6c163be9814637970ae"
        for entry in submodules
        if isinstance(entry, dict)
    ):
        raise VerificationError("OpenCC manifest does not lock the Rime OpenCC submodule")

    declared: dict[str, tuple[int, str]] = {}
    files = manifest.get("files")
    if not isinstance(files, list):
        raise VerificationError("OpenCC manifest has no file list")
    for entry in files:
        if not isinstance(entry, dict):
            raise VerificationError("OpenCC manifest contains an invalid file entry")
        raw_path = entry.get("path")
        size = entry.get("bytes")
        digest = entry.get("sha256")
        if not isinstance(raw_path, str) or not raw_path.startswith("runtime/"):
            raise VerificationError("OpenCC manifest path escapes the runtime directory")
        if raw_path in declared or not isinstance(size, int) or size <= 0:
            raise VerificationError("OpenCC manifest repeats a file or has an invalid size")
        if not isinstance(digest, str) or len(digest) != 64 or any(char not in "0123456789abcdef" for char in digest):
            raise VerificationError("OpenCC manifest has an invalid SHA-256")
        declared[raw_path] = (size, digest)

    runtime = bundle / "runtime"
    actual: dict[str, Path] = {}
    for path in sorted(runtime.rglob("*")):
        if path.is_symlink():
            raise VerificationError(f"OpenCC runtime contains a symbolic link: {path}")
        if not path.is_file():
            continue
        relative = path.relative_to(bundle).as_posix()
        if path.suffix not in {".json", ".ocd2"}:
            raise VerificationError(f"OpenCC runtime contains an unsupported file: {relative}")
        actual[relative] = path
    if set(declared) != set(actual):
        raise VerificationError("OpenCC manifest file list does not exactly match runtime files")
    for relative, path in actual.items():
        expected_size, expected_hash = declared[relative]
        if path.stat().st_size != expected_size or sha256(path) != expected_hash:
            raise VerificationError(f"OpenCC runtime hash mismatch: {relative}")

    ocd2_files = [path for path in actual.values() if path.suffix == ".ocd2"]
    config_files = [path for path in actual.values() if path.suffix == ".json"]
    if len(ocd2_files) != EXPECTED_OCD2_COUNT or len(config_files) != EXPECTED_CONFIG_COUNT:
        raise VerificationError("OpenCC runtime file counts do not match the reviewed generator output")
    for required in ("t2s.json", "s2t.json", "t2tw.json"):
        if f"runtime/{required}" not in actual:
            raise VerificationError(f"OpenCC runtime is missing {required}")
    checks = manifest.get("checks")
    if checks != {
        "t2s": {"input": "繁體中文", "output": "繁体中文"},
        "s2t": {"input": "汉字", "output": "漢字"},
    }:
        raise VerificationError("OpenCC manifest conversion checks are missing or changed")
    license_path = bundle / "licenses" / "opencc-LICENSE.txt"
    require_regular(license_path, "OpenCC license")
    if not license_path.read_text(encoding="utf-8").strip():
        raise VerificationError("OpenCC license is empty")
    extra_metadata = {entry.name for entry in (bundle / "metadata").iterdir()} - {
        "opencc-build-inputs.json",
        "submodules.tsv",
    }
    if extra_metadata:
        raise VerificationError(f"OpenCC metadata has unexpected entries: {sorted(extra_metadata)}")
    submodule_report = bundle / "metadata" / "submodules.tsv"
    require_regular(submodule_report, "OpenCC submodule report")
    return {
        "bundle": str(bundle),
        "runtimeFiles": len(actual),
        "ocd2Files": len(ocd2_files),
        "configFiles": len(config_files),
        "manifestSha256": sha256(manifest_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = verify(args.bundle)
    except (OSError, VerificationError) as error:
        print(f"Rime OpenCC verification failed: {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
