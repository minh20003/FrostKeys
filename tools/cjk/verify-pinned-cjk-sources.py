#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Read-only proof that local CJK source worktrees match FrostKeys' lock file."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from cjk_source_lock import SourceLockError, find_checkout, load_source_lock, verify_checkout


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--source-root",
        required=True,
        type=Path,
        help="directory containing rime/ and mozc/, or one engine's checkout",
    )
    parser.add_argument(
        "--engine",
        choices=("rime", "mozc"),
        action="append",
        help="engine to verify; repeatable; defaults to both engines",
    )
    parser.add_argument(
        "--lock",
        type=Path,
        default=Path(__file__).with_name("engine-sources.json"),
        help="immutable CJK source lock JSON",
    )
    parser.add_argument("--json", action="store_true", help="print machine-readable evidence")
    args = parser.parse_args()

    try:
        lock = load_source_lock(args.lock)
        requested = args.engine or sorted(lock)
        reports = []
        for engine_id in requested:
            source = lock[engine_id]
            checkout = find_checkout(args.source_root, source)
            reports.append(verify_checkout(source, checkout))
    except SourceLockError as error:
        print(f"CJK source verification failed: {error}", file=sys.stderr)
        return 1

    if args.json:
        print(json.dumps({"schema": 1, "verified": reports}, indent=2, sort_keys=True))
    else:
        for report in reports:
            source = report["source"]
            suffix = ""
            if "tagObject" in report:
                suffix = f"; annotated tag={report['tagObject']}"
            print(
                f"Verified {source['project']} {source['id']} at {report['head']} "
                f"in {report['checkout']}{suffix}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
