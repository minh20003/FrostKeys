#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Source-level regression checks for the intentionally narrow Rime bridge.

These checks are not a substitute for device golden tests.  They make it much
harder for a future edit to accidentally reintroduce a raw-command JNI escape
hatch, drop 16 KiB linker flags, or let the Kotlin adapter send arbitrary Rime
schema/options while the bridge is still under external validation.
"""

from __future__ import annotations

from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
BRIDGE = ROOT / "tools" / "cjk" / "rime_bridge" / "frostkeys_rime_jni.cc"
CMAKE = ROOT / "tools" / "cjk" / "rime_bridge" / "CMakeLists.txt"
KOTLIN = ROOT / "app" / "src" / "main" / "java" / "helium314" / "keyboard" / "latin" / "cjk" / "RimeCompositionEngine.kt"
CODEC = ROOT / "app" / "src" / "main" / "java" / "helium314" / "keyboard" / "latin" / "cjk" / "RimeWireCodec.kt"


def require(source: str, marker: str, label: str) -> None:
    if marker not in source:
        raise AssertionError(f"missing {label}: {marker}")


def forbid(source: str, marker: str, label: str) -> None:
    if marker in source:
        raise AssertionError(f"forbidden {label}: {marker}")


def main() -> int:
    try:
        native = BRIDGE.read_text(encoding="utf-8")
        cmake = CMAKE.read_text(encoding="utf-8")
        kotlin = KOTLIN.read_text(encoding="utf-8")
        codec = CODEC.read_text(encoding="utf-8")

        for marker in (
            "nativeProcessPinyinKey",
            "nativeBackspace",
            "nativeSelectCandidate",
            "nativeChangePage",
            "nativeCommit",
            "nativeReset",
            "nativeSetSimplifiedOutput",
            "nativeClose",
            "kSchemaId[] = \"luna_pinyin\"",
            "key_code >= 'a' && key_code <= 'z'",
            "select_candidate_on_current_page",
            "api.clear_composition",
            "api->set_option(session_id, \"zh_simp\"",
            "RIME_API_AVAILABLE(api, setup)",
            "std::lock_guard<std::mutex> runtime_lock",
            "kMaxPacketBytes",
        ):
            require(native, marker, "narrow native Rime contract")
        for forbidden_marker in ("nativeExecute", "nativeCommand", "simulate_key_sequence", "select_schema(session_id, schema", "set_option(session_id, option"):
            forbid(native, forbidden_marker, "raw native command surface")

        require(cmake, "-Wl,-z,max-page-size=16384", "16 KiB bridge linker gate")
        require(cmake, "RIME_LIBRARY", "explicit locked librime input")
        require(cmake, "target_link_libraries(frostkeys_rime", "bridge-to-librime link")

        for marker in (
            "interface RimeNativeSession",
            "fun processPinyinKey(keyCode: Int)",
            "fun setSimplifiedOutput(simplified: Boolean)",
            "RimeWireCodec.normalizedPinyinKey",
            "RimePinyinOutputMode.fromStableId",
            "Verified Rime OpenCC dictionaries are missing",
            "System.loadLibrary(\"frostkeys_rime\")",
        ):
            require(kotlin, marker, "narrow Kotlin Rime contract")
        for forbidden_marker in ("fun evaluate(", "executeCommand", "schemaId: String", "optionName: String"):
            forbid(kotlin, forbidden_marker, "raw Kotlin command surface")

        for marker in ("MAX_PACKET_BYTES", "MAX_CANDIDATES", "unknown flags", "trailing bytes", "normalizedPinyinKey"):
            require(codec, marker, "bounded Rime packet validation")
    except (AssertionError, OSError) as error:
        print(f"Rime bridge contract test failed: {error}", file=sys.stderr)
        return 1
    print("Rime bridge contract: 4 source surfaces verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
