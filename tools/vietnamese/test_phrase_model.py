#!/usr/bin/env python3
"""Host-side regression checks for the exact Vietnamese phrase asset shipped in the APK."""

from __future__ import annotations

import json
import sys
from pathlib import Path

from build_phrase_model import expected_outputs, parse_source


ROOT = Path(__file__).parents[2]
SOURCE = ROOT / "tools/vietnamese/seed_vi_phrases_v1.tsv"
ARTIFACT = ROOT / "app/src/main/assets/dicts/vi_phrase_model_v1.tsv"
MANIFEST = ROOT / "app/src/main/assets/manifests/phrase_model_vi.json"


def predictions(entries, words: tuple[str, ...]) -> list[str]:
    normalized_words = tuple(word.casefold() for word in words)
    trigrams = [
        entry for entry in entries
        if entry.order == 3 and entry.normalized_context == normalized_words[-2:]
    ] if len(normalized_words) >= 2 else []
    bigrams = [
        entry for entry in entries
        if entry.order == 2 and entry.normalized_context == normalized_words[-1:]
    ] if normalized_words else []
    ordered = sorted(trigrams, key=lambda entry: (-entry.score, entry.normalized_candidate))
    ordered += sorted(bigrams, key=lambda entry: (-entry.score, entry.normalized_candidate))
    result: list[str] = []
    seen: set[str] = set()
    for entry in ordered:
        if entry.normalized_candidate not in seen:
            seen.add(entry.normalized_candidate)
            result.append(entry.candidate)
    return result


def main() -> int:
    expected_artifact, expected_manifest = expected_outputs(SOURCE)
    actual_artifact = ARTIFACT.read_bytes()
    actual_manifest = MANIFEST.read_bytes()
    assert actual_artifact == expected_artifact, "APK phrase artifact is not reproducible from its source"
    assert actual_manifest == expected_manifest, "APK phrase manifest is not reproducible from its source"

    manifest = json.loads(actual_manifest)
    assert manifest["locale"] == "vi"
    assert manifest["entryCount"] <= manifest["maxEntries"] <= 256
    assert manifest["maxCandidatesPerContext"] <= 4
    entries = parse_source(actual_artifact)
    assert predictions(entries, ("học",))[0] == "sinh"
    assert predictions(entries, ("công",))[0] == "nghệ"
    assert predictions(entries, ("Việt",))[0] == "Nam"
    # A two-word context must win over the fallback bigram rather than merely coexist with it.
    assert predictions(entries, ("học", "sinh"))[0] == "giỏi"
    print("Vietnamese phrase model: 66 reproducible entries; mandated predictions verified")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, ValueError) as error:
        print(f"Vietnamese phrase model test failed: {error}", file=sys.stderr)
        raise SystemExit(1)
