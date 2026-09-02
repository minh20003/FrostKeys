#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Verify the exact Linux NDK input used by the native CJK build helpers."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys


class ToolchainError(RuntimeError):
    pass


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", required=True, type=Path, help="official downloaded NDK ZIP")
    parser.add_argument("--ndk-root", required=True, type=Path, help="extracted Linux NDK directory")
    parser.add_argument("--bazel", type=Path, help="official Bazel binary used for the Mozc build")
    parser.add_argument(
        "--lock", type=Path, default=Path(__file__).with_name("toolchains.json"), help="toolchain lock"
    )
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    try:
        lock = load_lock(args.lock)
        archive_report = verify_archive(args.archive, lock)
        ndk_report = verify_ndk_root(args.ndk_root, lock)
        bazel_report = verify_bazel(args.bazel, lock) if args.bazel else None
    except (ToolchainError, OSError, json.JSONDecodeError) as error:
        print(f"CJK toolchain verification failed: {error}", file=sys.stderr)
        return 1
    report = {"schema": 1, "archive": archive_report, "ndk": ndk_report}
    if bazel_report is not None:
        report["bazel"] = bazel_report
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print(
            f"Verified Android NDK {ndk_report['revision']} ({ndk_report['host']}) "
            f"from {archive_report['path']}"
        )
    return 0


def load_lock(path: Path) -> dict[str, object]:
    data = json.loads(path.read_text(encoding="utf-8"))
    try:
        ndk = data["androidNdk"]
        archive = ndk["archive"]
        bazel = data["bazel"]
        revision = ndk["revision"]
        host = ndk["host"]
        if data["schema"] != 1 or not all(isinstance(value, str) and value for value in (revision, host)):
            raise KeyError
        if not isinstance(archive["bytes"], int) or archive["bytes"] <= 0:
            raise KeyError
        for key, length in (("sha1", 40), ("sha256", 64)):
            value = archive[key]
            if not isinstance(value, str) or len(value) != length:
                raise KeyError
        for key in ("url", "metadata"):
            value = archive[key]
            if not isinstance(value, str) or not value.startswith("https://"):
                raise KeyError
        if not all(isinstance(bazel[key], str) and bazel[key] for key in ("version", "host", "url", "sha256")):
            raise KeyError
        if not bazel["url"].startswith("https://") or len(bazel["sha256"]) != 64:
            raise KeyError
    except (KeyError, TypeError):
        raise ToolchainError(f"Malformed toolchain lock: {path}")
    return data


def verify_archive(path: Path, lock: dict[str, object]) -> dict[str, object]:
    archive = lock["androidNdk"]["archive"]
    if path.is_symlink():
        raise ToolchainError(f"NDK archive may not be a symbolic link: {path}")
    path = path.resolve()
    if not path.is_file():
        raise ToolchainError(f"NDK archive must be a regular file: {path}")
    if path.stat().st_size != archive["bytes"]:
        raise ToolchainError(
            f"NDK archive size mismatch: {path.stat().st_size} != {archive['bytes']}"
        )
    sha1 = digest_file(path, "sha1")
    sha256 = digest_file(path, "sha256")
    if sha1 != archive["sha1"] or sha256 != archive["sha256"]:
        raise ToolchainError("NDK archive checksum does not match toolchain lock")
    return {"path": str(path), "bytes": path.stat().st_size, "sha1": sha1, "sha256": sha256}


def verify_ndk_root(path: Path, lock: dict[str, object]) -> dict[str, object]:
    ndk = lock["androidNdk"]
    if path.is_symlink():
        raise ToolchainError(f"NDK root may not be a symbolic link: {path}")
    path = path.resolve()
    if not path.is_dir():
        raise ToolchainError(f"NDK root must be a regular directory: {path}")
    properties = path / "source.properties"
    if not properties.is_file():
        raise ToolchainError(f"NDK source.properties is missing: {properties}")
    entries = {}
    for line in properties.read_text(encoding="utf-8").splitlines():
        if " = " in line:
            key, value = line.split(" = ", 1)
            entries[key] = value
    if entries.get("Pkg.Revision") != ndk["revision"]:
        raise ToolchainError(
            f"NDK revision mismatch: {entries.get('Pkg.Revision')!r} != {ndk['revision']!r}"
        )
    compiler = path / "toolchains" / "llvm" / "prebuilt" / "linux-x86_64" / "bin" / "clang"
    if not compiler.is_file() or (compiler.stat().st_mode & 0o111) == 0:
        raise ToolchainError(f"NDK is not an executable Linux x86_64 toolchain: {compiler}")
    return {"root": str(path), "revision": entries["Pkg.Revision"], "host": ndk["host"]}


def verify_bazel(path: Path, lock: dict[str, object]) -> dict[str, object]:
    import subprocess

    bazel = lock["bazel"]
    if path.is_symlink():
        raise ToolchainError(f"Bazel binary may not be a symbolic link: {path}")
    path = path.resolve()
    if not path.is_file() or (path.stat().st_mode & 0o111) == 0:
        raise ToolchainError(f"Bazel binary is not executable: {path}")
    sha256 = digest_file(path, "sha256")
    if sha256 != bazel["sha256"]:
        raise ToolchainError("Bazel binary checksum does not match toolchain lock")
    try:
        output = subprocess.run(
            [str(path), "--version"], check=True, capture_output=True, text=True, encoding="utf-8"
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as error:
        raise ToolchainError(f"Could not execute pinned Bazel binary: {error}") from error
    expected = f"bazel {bazel['version']}"
    if output != expected:
        raise ToolchainError(f"Bazel version mismatch: {output!r} != {expected!r}")
    return {"path": str(path), "version": bazel["version"], "host": bazel["host"], "sha256": sha256}


def digest_file(path: Path, algorithm: str) -> str:
    digest = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


if __name__ == "__main__":
    raise SystemExit(main())
