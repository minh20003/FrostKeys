#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Shared immutable-source verification for FrostKeys' optional CJK engines.

This module is intentionally build tooling only.  It never downloads runtime
data and it never changes an app checkout.  A native/data bundle is allowed to
claim Rime or Mozc provenance only after this module has verified the exact
source tree used to make it.
"""

from __future__ import annotations

from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
import subprocess
from typing import Any


_COMMIT_RE = re.compile(r"[0-9a-f]{40}")
_ENGINE_RE = re.compile(r"[a-z][a-z0-9_-]{0,79}")
_HTTPS_GITHUB_RE = re.compile(r"https://github\.com/[^/]+/[^/]+(?:\.git)?")


class SourceLockError(RuntimeError):
    """Raised when a checkout cannot prove the source lock it claims."""


@dataclass(frozen=True)
class EngineSource:
    """One immutable engine entry from ``engine-sources.json``."""

    id: str
    project: str
    version: str
    commit: str
    checkout_commit: str
    fetch_ref: str
    checkout_source: str
    manifest_source: str
    license: str
    status: str

    def as_manifest_provenance(self) -> dict[str, str]:
        """Fields mirrored in the Android runtime bundle manifest."""
        return {
            "engine": self.id,
            "version": self.version,
            "commit": self.commit,
            "checkoutCommit": self.checkout_commit,
            "source": self.manifest_source,
            "license": self.license,
        }


def load_source_lock(path: Path) -> dict[str, EngineSource]:
    """Loads and strictly validates the checked-in source acquisition lock."""
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SourceLockError(f"Could not read source lock {path}: {error}") from error

    if not isinstance(raw, dict) or raw.get("schema") != 1:
        raise SourceLockError("Unsupported CJK source lock schema")
    engines = raw.get("engines")
    if not isinstance(engines, list) or not engines:
        raise SourceLockError("CJK source lock has no engines")

    result: dict[str, EngineSource] = {}
    required = {
        "id",
        "project",
        "version",
        "commit",
        "checkoutCommit",
        "fetchRef",
        "checkoutSource",
        "manifestSource",
        "license",
        "status",
    }
    for raw_engine in engines:
        if not isinstance(raw_engine, dict) or not required.issubset(raw_engine):
            raise SourceLockError("CJK source lock contains an incomplete engine entry")
        values = {name: raw_engine[name] for name in required}
        if not all(isinstance(value, str) and value for value in values.values()):
            raise SourceLockError("CJK source lock contains an empty or non-string field")
        engine = EngineSource(
            id=values["id"],
            project=values["project"],
            version=values["version"],
            commit=values["commit"].lower(),
            checkout_commit=values["checkoutCommit"].lower(),
            fetch_ref=values["fetchRef"],
            checkout_source=values["checkoutSource"],
            manifest_source=values["manifestSource"],
            license=values["license"],
            status=values["status"],
        )
        if not _ENGINE_RE.fullmatch(engine.id):
            raise SourceLockError(f"Unsafe engine id in source lock: {engine.id!r}")
        if engine.id in result:
            raise SourceLockError(f"Duplicate engine id in source lock: {engine.id}")
        if not _COMMIT_RE.fullmatch(engine.commit) or not _COMMIT_RE.fullmatch(engine.checkout_commit):
            raise SourceLockError(f"{engine.id} does not use full immutable commit IDs")
        if not _HTTPS_GITHUB_RE.fullmatch(engine.checkout_source):
            raise SourceLockError(f"Unsupported checkout URL for {engine.id}")
        if not _HTTPS_GITHUB_RE.fullmatch(engine.manifest_source):
            raise SourceLockError(f"Unsupported manifest URL for {engine.id}")
        if engine.status != "source-pinned-not-bundled":
            raise SourceLockError(f"Unexpected source-lock status for {engine.id}: {engine.status}")
        result[engine.id] = engine

    if set(result) != {"rime", "mozc"}:
        raise SourceLockError("CJK source lock must contain exactly rime and mozc")
    return result


def find_checkout(source_root: Path, engine: EngineSource) -> Path:
    """Accepts either a root containing engine directories or that engine's root."""
    source_root = source_root.resolve()
    direct = source_root
    nested = source_root / engine.id
    if _is_git_worktree(direct):
        return direct
    if _is_git_worktree(nested):
        return nested
    raise SourceLockError(
        f"Could not find a Git checkout for {engine.id} at {direct} or {nested}"
    )


def verify_checkout(engine: EngineSource, checkout: Path) -> dict[str, Any]:
    """Proves that ``checkout`` is clean and exactly equals the approved source tree."""
    checkout = checkout.resolve()
    if not _is_git_worktree(checkout):
        raise SourceLockError(f"Not a Git worktree: {checkout}")

    origin = _git(checkout, "remote", "get-url", "origin")
    if origin != engine.checkout_source:
        raise SourceLockError(
            f"{engine.id} origin does not match source lock: {origin!r} != {engine.checkout_source!r}"
        )
    head = _git(checkout, "rev-parse", "HEAD").lower()
    if head != engine.checkout_commit:
        raise SourceLockError(
            f"{engine.id} HEAD does not match source lock: {head} != {engine.checkout_commit}"
        )
    if _git(checkout, "status", "--porcelain"):
        raise SourceLockError(f"{engine.id} source checkout is dirty: {checkout}")

    # ``librime`` delegates several core dependencies to Git submodules.  The
    # top-level commit fixes the expected gitlinks, while this check proves the
    # actual build directory has initialized every one at exactly those links.
    # A leading '-' means uninitialized and '+' means a checked-out revision
    # differs from the superproject; neither can produce a reviewable build.
    submodules = _verify_submodules(checkout)

    # Rime 1.16.1 is an annotated tag.  Both the tag object and its peeled
    # commit are material provenance and must be proven separately.
    tag_object: str | None = None
    if engine.fetch_ref.startswith("refs/tags/"):
        ref = engine.fetch_ref
        ref_type = _git(checkout, "cat-file", "-t", ref)
        if ref_type != "tag":
            raise SourceLockError(f"{engine.id} {ref} is not an annotated tag")
        tag_object = _git(checkout, "rev-parse", ref).lower()
        if tag_object != engine.commit:
            raise SourceLockError(
                f"{engine.id} tag object does not match source lock: "
                f"{tag_object} != {engine.commit}"
            )
        peeled = _git(checkout, "rev-parse", f"{ref}^{{}}").lower()
        if peeled != engine.checkout_commit:
            raise SourceLockError(
                f"{engine.id} peeled tag does not match source lock: "
                f"{peeled} != {engine.checkout_commit}"
            )
    else:
        # Mozc is locked directly to a commit.  Prove that object is locally
        # available as a commit; merely comparing a text label is not enough.
        object_type = _git(checkout, "cat-file", "-t", engine.commit)
        if object_type != "commit":
            raise SourceLockError(f"{engine.id} locked revision is not a commit")

    report: dict[str, Any] = {
        "checkout": str(checkout),
        "origin": origin,
        "head": head,
        "source": asdict(engine),
        "submodules": submodules,
    }
    if tag_object is not None:
        report["tagObject"] = tag_object
        report["peeledCommit"] = engine.checkout_commit
    return report


def _verify_submodules(checkout: Path) -> list[dict[str, str]]:
    raw = _git(checkout, "submodule", "status", "--recursive")
    if not raw:
        return []
    result: list[dict[str, str]] = []
    for line in raw.splitlines():
        if len(line) < 43 or line[0] != " ":
            raise SourceLockError(f"Submodule is missing or not at its locked commit: {line}")
        fields = line[1:].split()
        if len(fields) < 2 or not _COMMIT_RE.fullmatch(fields[0]):
            raise SourceLockError(f"Could not parse locked submodule status: {line}")
        result.append({"commit": fields[0].lower(), "path": fields[1]})
    return result


def _is_git_worktree(path: Path) -> bool:
    if not path.is_dir():
        return False
    try:
        return _git(path, "rev-parse", "--is-inside-work-tree") == "true"
    except SourceLockError:
        return False


def _git(checkout: Path, *args: str) -> str:
    try:
        completed = subprocess.run(
            ["git", "-C", str(checkout), *args],
            check=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="strict",
        )
    except (OSError, subprocess.CalledProcessError) as error:
        stderr = getattr(error, "stderr", "") or ""
        raise SourceLockError(
            f"Git verification failed for {checkout}: {' '.join(args)}: {stderr.strip()}"
        ) from error
    # Preserve the first column of ``git submodule status``: it carries the
    # initialized/modified status.  Callers that compare revision IDs receive
    # ordinary non-whitespace output, while status porcelain remains truthy.
    return completed.stdout.rstrip()
