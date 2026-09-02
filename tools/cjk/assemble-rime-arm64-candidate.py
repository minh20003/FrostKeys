#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Assemble a reviewable ARM64 librime candidate outside the Android app.

This is intentionally *not* an APK packager.  It accepts a real ARM64
``librime.so`` already built from the locked source checkout, verifies its
16-KiB ELF layout, and makes an atomically-created candidate tree containing
the Rime minimal Pinyin schemas and an exhaustive evidence manifest.

Without precompiled OpenCC ``.ocd2`` data, the output is explicitly marked
``local-candidate-not-packageable``.  It must not be confused with a complete
Simplified/Traditional engine bundle or with an app-integrated input method.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
from typing import Any, Iterable

from cjk_source_lock import SourceLockError, find_checkout, load_source_lock, verify_checkout


SCRIPT_VERSION = 1
BOOST_1_89_0_ARCHIVE_SHA256 = "67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74"
NDK_REVISION = "28.0.13004108"
LOCKED_LINUX_NDK_ARCHIVE_SHA256 = "a186b67e8810cb949514925e4f7a2255548fb55f5e9b0824a6430d012c1b695b"
MAX_FILE_BYTES = 128 * 1024 * 1024
MAX_TOTAL_BYTES = 256 * 1024 * 1024
PAGE_SIZE_16K = 16 * 1024
ELF_MACHINE_AARCH64 = 183
PT_LOAD = 1


class CandidateError(RuntimeError):
    """Raised when a native/data candidate is unsafe or lacks required evidence."""


@dataclass(frozen=True)
class ElfReport:
    machine: int
    load_alignments: tuple[int, ...]


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def require_within(path: Path, root: Path, label: str) -> None:
    try:
        path.relative_to(root)
    except ValueError as error:
        raise CandidateError(f"{label} escapes its required root: {path}") from error


def validate_arm64_16k_elf(path: Path) -> ElfReport:
    import struct

    if path.is_symlink() or not path.is_file():
        raise CandidateError(f"Native library must be a regular file: {path}")
    with path.open("rb") as stream:
        header = stream.read(64)
        if len(header) != 64 or header[:4] != b"\x7fELF":
            raise CandidateError(f"Native library is not ELF: {path}")
        if header[4] != 2 or header[5] != 1:
            raise CandidateError(f"Native library is not 64-bit little-endian ELF: {path}")
        unpacked = struct.unpack("<16sHHIQQQIHHHHHH", header)
        elf_type, machine = unpacked[1], unpacked[2]
        program_offset, program_entry_size, program_count = unpacked[5], unpacked[9], unpacked[10]
        if elf_type != 3 or machine != ELF_MACHINE_AARCH64:
            raise CandidateError(f"Native library is not an ARM64 shared object: type={elf_type} machine={machine}")
        if program_entry_size != 56 or not program_count:
            raise CandidateError("Native library has invalid program headers")
        alignments: list[int] = []
        for index in range(program_count):
            stream.seek(program_offset + index * program_entry_size)
            entry = stream.read(program_entry_size)
            if len(entry) != program_entry_size:
                raise CandidateError("Native library program header is truncated")
            p_type, _flags, offset, vaddr, _paddr, _filesz, _memsz, align = struct.unpack(
                "<IIQQQQQQ", entry
            )
            if p_type != PT_LOAD:
                continue
            if align < PAGE_SIZE_16K or align % PAGE_SIZE_16K or align & (align - 1):
                raise CandidateError(f"Native library PT_LOAD is not 16-KiB aligned: 0x{align:x}")
            if offset % align != vaddr % align:
                raise CandidateError("Native library has a non-congruent PT_LOAD segment")
            alignments.append(align)
    if not alignments:
        raise CandidateError("Native library has no PT_LOAD segments")
    return ElfReport(machine=machine, load_alignments=tuple(alignments))


def read_ndk_revision(ndk_root: Path) -> tuple[str, str]:
    source_properties = ndk_root / "source.properties"
    if source_properties.is_symlink() or not source_properties.is_file():
        raise CandidateError(f"NDK source.properties is missing: {source_properties}")
    raw = source_properties.read_bytes()
    revision = None
    for line in raw.decode("utf-8", errors="strict").splitlines():
        if line.startswith("Pkg.Revision"):
            _key, value = line.split("=", 1)
            revision = value.strip()
            break
    if revision != NDK_REVISION:
        raise CandidateError(f"Expected NDK {NDK_REVISION}; found {revision!r}")
    return revision, hashlib.sha256(raw).hexdigest()


def copy_regular(source: Path, destination: Path) -> tuple[int, str]:
    if source.is_symlink() or not source.is_file():
        raise CandidateError(f"Candidate input must be a regular file: {source}")
    size = source.stat().st_size
    if not 1 <= size <= MAX_FILE_BYTES:
        raise CandidateError(f"Candidate file has invalid size {size}: {source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    result = hashlib.sha256()
    copied = 0
    with source.open("rb") as input_stream, destination.open("xb") as output_stream:
        for chunk in iter(lambda: input_stream.read(1024 * 1024), b""):
            copied += len(chunk)
            result.update(chunk)
            output_stream.write(chunk)
    return copied, result.hexdigest()


def copy_tree(source: Path, destination: Path, relative_prefix: str) -> list[dict[str, Any]]:
    if source.is_symlink() or not source.is_dir():
        raise CandidateError(f"Data source must be a regular directory: {source}")
    source = source.resolve()
    entries: list[dict[str, Any]] = []
    for candidate in sorted(source.rglob("*")):
        if candidate.is_symlink():
            raise CandidateError(f"Data source contains symbolic link: {candidate}")
        if not candidate.is_file():
            continue
        relative = candidate.relative_to(source)
        if any(part in (".", "..") for part in relative.parts):
            raise CandidateError(f"Unsafe data path: {candidate}")
        target = destination / relative
        bytes_count, sha256 = copy_regular(candidate, target)
        entries.append({"path": f"{relative_prefix}/{relative.as_posix()}", "bytes": bytes_count, "sha256": sha256})
    if not entries:
        raise CandidateError(f"Data source is empty: {source}")
    return entries


def require_opencc_data(source: Path) -> None:
    if source.is_symlink() or not source.is_dir():
        raise CandidateError(f"OpenCC data root must be a regular directory: {source}")
    required = ("t2s.json", "t2tw.json")
    missing = [name for name in required if not (source / name).is_file()]
    ocd2_count = sum(1 for path in source.rglob("*.ocd2") if path.is_file() and not path.is_symlink())
    if missing or not ocd2_count:
        reason = ", ".join([*(f"missing {name}" for name in missing), "no .ocd2 dictionary" if not ocd2_count else ""])
        raise CandidateError(f"OpenCC data root is not usable for Rime Traditional conversion: {reason}")


def verify_boost_archive(archive: Path) -> dict[str, str]:
    if archive.is_symlink() or not archive.is_file():
        raise CandidateError(f"Boost archive must be a regular file: {archive}")
    actual = digest(archive)
    if actual != BOOST_1_89_0_ARCHIVE_SHA256:
        raise CandidateError("Boost archive hash does not match the reviewed 1.89.0 input")
    return {"version": "1.89.0", "archiveSha256": actual}


def copy_notices(stage: Path, notices: Iterable[tuple[str, Path]]) -> list[dict[str, Any]]:
    result = []
    for name, source in notices:
        destination = stage / "licenses" / name
        bytes_count, sha256 = copy_regular(source, destination)
        result.append({"path": f"licenses/{name}", "bytes": bytes_count, "sha256": sha256})
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True, help="parent directory containing the locked rime checkout")
    parser.add_argument("--native-lib", type=Path, required=True, help="already-built ARM64 librime.so")
    parser.add_argument("--ndk-root", type=Path, required=True)
    parser.add_argument("--boost-archive", type=Path, required=True)
    parser.add_argument("--boost-source-root", type=Path, required=True, help="Boost tree extracted from --boost-archive")
    parser.add_argument("--output", type=Path, required=True, help="new candidate directory; must not exist")
    parser.add_argument(
        "--opencc-data-root",
        type=Path,
        help="directory of host-generated OpenCC .json/.ocd2 files; omit only for an explicitly incomplete Pinyin candidate",
    )
    parser.add_argument(
        "--allow-pinyin-only",
        action="store_true",
        help="allow a local Pinyin-only candidate when no usable OpenCC data is available",
    )
    parser.add_argument("--lock", type=Path, default=Path(__file__).with_name("engine-sources.json"))
    args = parser.parse_args()

    try:
        output = args.output.resolve()
        if output.exists():
            raise CandidateError(f"Refusing to overwrite existing candidate output: {output}")
        if not output.parent.is_dir():
            raise CandidateError(f"Candidate output parent does not exist: {output.parent}")

        lock = load_source_lock(args.lock)
        source = lock["rime"]
        checkout = find_checkout(args.source_root, source)
        source_report = verify_checkout(source, checkout)
        elf_report = validate_arm64_16k_elf(args.native_lib)
        ndk_revision, ndk_source_properties_sha256 = read_ndk_revision(args.ndk_root.resolve())
        boost_report = verify_boost_archive(args.boost_archive)
        if args.boost_source_root.is_symlink() or not (args.boost_source_root / "LICENSE_1_0.txt").is_file():
            raise CandidateError("Boost source root is missing LICENSE_1_0.txt")

        has_opencc = args.opencc_data_root is not None
        if has_opencc:
            require_opencc_data(args.opencc_data_root)
        elif not args.allow_pinyin_only:
            raise CandidateError("OpenCC data is required unless --allow-pinyin-only is explicitly selected")

        stage = Path(tempfile.mkdtemp(prefix=".rime-arm64-candidate-", dir=output.parent))
        try:
            native_target = stage / "native" / "librime.so"
            native_bytes, native_sha256 = copy_regular(args.native_lib, native_target)
            data_entries = copy_tree(checkout / "data" / "minimal", stage / "data" / "shared", "data/shared")
            if has_opencc:
                data_entries.extend(copy_tree(args.opencc_data_root, stage / "data" / "shared" / "opencc", "data/shared/opencc"))

            notices = copy_notices(
                stage,
                (
                    ("librime-LICENSE.txt", checkout / "LICENSE"),
                    ("opencc-LICENSE.txt", checkout / "deps" / "opencc" / "LICENSE"),
                    ("boost-LICENSE_1_0.txt", args.boost_source_root / "LICENSE_1_0.txt"),
                ),
            )
            total = native_bytes + sum(entry["bytes"] for entry in data_entries) + sum(entry["bytes"] for entry in notices)
            if total > MAX_TOTAL_BYTES:
                raise CandidateError(f"Candidate exceeds maximum review size ({total} > {MAX_TOTAL_BYTES})")

            report = {
                "schema": 1,
                "tool": "tools/cjk/assemble-rime-arm64-candidate.py",
                "toolVersion": SCRIPT_VERSION,
                "kind": "reproducible-candidate" if has_opencc else "local-candidate-not-packageable",
                "engine": "rime",
                "sourceVerification": source_report,
                "native": {
                    "path": "native/librime.so",
                    "bytes": native_bytes,
                    "sha256": native_sha256,
                    "machine": elf_report.machine,
                    "loadAlignments": list(elf_report.load_alignments),
                    "requiredPageSize": PAGE_SIZE_16K,
                },
                "toolchain": {
                    "ndkRevision": ndk_revision,
                    "ndkSourcePropertiesSha256": ndk_source_properties_sha256,
                    "lockedLinuxArchiveSha256": LOCKED_LINUX_NDK_ARCHIVE_SHA256,
                    "archiveVerified": False,
                    "note": "This local Windows NDK installation proves its revision only; use the locked Linux archive builder before release packaging.",
                    "boost": boost_report,
                },
                "data": data_entries,
                "notices": notices,
                "capabilities": {
                    "offline": True,
                    "pinyinMinimalSchema": True,
                    "openccTraditional": has_opencc,
                    "appJniBridge": False,
                    "appSubtypeRegistered": False,
                },
                "releaseBlockers": [
                    *([] if has_opencc else ["No host-generated OpenCC .ocd2 data; Traditional conversion is unavailable."]),
                    "No FrostKeys-owned Rime JNI bridge or Android lifecycle integration.",
                    "No device golden tests for Pinyin candidates, paging, cancellation, or local learning.",
                    "The local Windows NDK installation was revision-checked but its download archive was not independently verified.",
                ],
            }
            metadata_target = stage / "metadata" / "candidate-report.json"
            metadata_target.parent.mkdir(parents=True, exist_ok=True)
            metadata_target.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
            stage.rename(output)
        except BaseException:
            shutil.rmtree(stage, ignore_errors=True)
            raise
    except (CandidateError, SourceLockError, OSError) as error:
        print(f"Rime candidate assembly failed: {error}", file=sys.stderr)
        return 1

    print(f"Wrote reviewable Rime candidate: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
