#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Materialize Boost's modular header layout without using b2.

The official Boost release archive stores headers below each component's
``libs/<component>/include/boost`` directory.  b2 normally makes one aggregate
``boost/`` include tree from those directories.  The FrostKeys Rime builder
needs only Boost.Regex as a compiled library, but the Rime headers transitively
use several other Boost components.  Copying the aggregate tree is portable to
Windows hosts where creating b2-style symlinks requires elevated privileges.

The output is deliberately refused when it already exists.  A byte-for-byte
collision is harmless and skipped; a differing collision is a failed build
input rather than an arbitrary last-writer-wins header selection.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import shutil
import sys
import tempfile


class HeaderLayoutError(RuntimeError):
    """Raised for an unsafe or ambiguous Boost header aggregation."""


BOOST_INCLUDE = re.compile(r'^\s*#\s*include\s*[<"](boost/[^">]+)[">]', re.MULTILINE)


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def is_within(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _read_boost_includes(path: Path) -> set[str]:
    try:
        text = path.read_text(encoding="utf-8", errors="ignore")
    except OSError as error:
        raise HeaderLayoutError(f"Could not read source while resolving Boost headers: {path}: {error}") from error
    return set(BOOST_INCLUDE.findall(text))


def _collect_seeds(seed_roots: list[Path]) -> set[str]:
    result: set[str] = set()
    for root in seed_roots:
        if root.is_symlink() or not root.is_dir():
            raise HeaderLayoutError(f"Boost seed root must be a regular directory: {root}")
        for candidate in sorted(root.resolve().rglob("*")):
            if candidate.is_symlink():
                raise HeaderLayoutError(f"Boost seed root contains a symbolic link: {candidate}")
            if candidate.is_file() and candidate.suffix.lower() in {".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp"}:
                result.update(_read_boost_includes(candidate))
    return result


def materialize(source: Path, output: Path, seed_roots: list[Path]) -> int:
    if source.is_symlink() or not source.is_dir():
        raise HeaderLayoutError(f"Boost source must be a regular directory: {source}")
    source = source.resolve()
    if output.exists():
        raise HeaderLayoutError(f"Refusing to overwrite an existing header tree: {output}")
    if not output.parent.is_dir():
        raise HeaderLayoutError(f"Header output parent does not exist: {output.parent}")

    # A few Boost components live in nested paths (for example
    # libs/numeric/conversion), so this must not assume a one-level layout.
    roots = sorted((source / "libs").rglob("include/boost"))
    roots = [root for root in roots if root.is_dir() and not root.is_symlink()]
    if not roots:
        raise HeaderLayoutError("Boost archive has no modular include/boost trees")

    providers: dict[str, Path] = {}
    for root in roots:
        for input_path in sorted(root.rglob("*")):
            if input_path.is_symlink():
                raise HeaderLayoutError(f"Boost headers contain a symbolic link: {input_path}")
            if not input_path.is_file():
                continue
            relative = f"boost/{input_path.relative_to(root).as_posix()}"
            previous = providers.get(relative)
            if previous is None:
                providers[relative] = input_path
            elif digest(previous) != digest(input_path):
                raise HeaderLayoutError(
                    f"Conflicting Boost header supplied by multiple components: {relative}"
                )

    requested = _collect_seeds(seed_roots)
    if requested:
        # CMake's FindBoost reads this even if no Rime source explicitly does.
        requested.add("boost/version.hpp")
        initial_requested = set(requested)
        pending = sorted(requested)
        selected: set[str] = set()
        skipped_conditional = 0
        while pending:
            relative = pending.pop()
            if relative in selected:
                continue
            input_path = providers.get(relative)
            if input_path is None:
                # Boost headers list legacy platform/compiler branches that are
                # intentionally absent from a modern modular release.  They
                # cannot be selected by the Android Clang build.  Direct Rime
                # includes, however, are never optional and must resolve.
                if relative in initial_requested:
                    raise HeaderLayoutError(f"Boost header requested by Rime is unavailable: {relative}")
                skipped_conditional += 1
                continue
            selected.add(relative)
            for dependency in _read_boost_includes(input_path):
                if dependency not in selected:
                    pending.append(dependency)
        # Several Boost components select an implementation by macro rather
        # than by a literal #include (MPL's ``preprocessed/gcc`` headers are a
        # notable example).  Once the Rime closure touches one, preserve that
        # component's complete include tree.  This remains much smaller than
        # materialising all of Boost while avoiding host-specific b2 symlinks.
        macro_configured_components = (
            "config",
            "interprocess",
            "mpl",
            "preprocessor",
            "regex",
            "signals2",
            "typeof",
            "unordered",
            "uuid",
            "variant",
        )
        changed = True
        while changed:
            changed = False
            for component in macro_configured_components:
                component_root = (source / "libs" / component / "include" / "boost").resolve()
                if not component_root.is_dir():
                    continue
                if not any(is_within(providers[path].resolve(), component_root) for path in selected):
                    continue
                component_paths = {
                    path for path, provider in providers.items() if is_within(provider.resolve(), component_root)
                }
                if not component_paths.issubset(selected):
                    selected.update(component_paths)
                    changed = True
    else:
        selected = set(providers)
        skipped_conditional = 0

    stage = Path(tempfile.mkdtemp(prefix=".boost-headers-", dir=output.parent))
    try:
        destination_root = stage
        copied = 0
        for relative_text in sorted(selected):
            input_path = providers[relative_text]
            relative = Path(relative_text)
            destination = destination_root / relative
            if not is_within(destination.resolve(strict=False), destination_root.resolve(strict=False)):
                raise HeaderLayoutError(f"Boost header escapes output: {input_path}")
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(input_path, destination)
            copied += 1

        if not (destination_root / "boost" / "version.hpp").is_file():
            raise HeaderLayoutError("Boost header aggregation did not produce boost/version.hpp")
        stage.rename(output)
    except BaseException:
        shutil.rmtree(stage, ignore_errors=True)
        raise

    scope = "Rime closure" if requested else "entire Boost tree"
    print(
        f"Materialized {copied} Boost headers for {scope} "
        f"({skipped_conditional} unavailable conditional branches skipped): {output}"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="extracted Boost release tree")
    parser.add_argument("--output", type=Path, required=True, help="new aggregate include-root directory")
    parser.add_argument(
        "--seed-root",
        type=Path,
        action="append",
        default=[],
        help="source tree whose Boost includes define the required closure (repeatable)",
    )
    args = parser.parse_args()
    try:
        return materialize(args.source, args.output, args.seed_root)
    except (HeaderLayoutError, OSError) as error:
        print(f"Boost header preparation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
