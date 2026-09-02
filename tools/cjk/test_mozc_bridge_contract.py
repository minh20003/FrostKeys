#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Structural regression checks for the source-only FrostKeys Mozc bridge."""

from __future__ import annotations

from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parent / "mozc_bridge"
SOURCE = ROOT / "frostkeys_mozc_jni.cc"
BUILD_TEMPLATE = ROOT / "BUILD.bazel.template"


class MozcBridgeContractTest(unittest.TestCase):
    def test_bridge_uses_owned_context_not_google_jni_or_global_handler(self) -> None:
        source = SOURCE.read_text(encoding="utf-8")

        self.assertIn("class FrostKeysMozcContext", source)
        self.assertIn("std::unique_ptr<mozc::SessionHandler> handler_", source)
        self.assertNotIn("g_session_handler", source)
        self.assertNotIn("Java_com_google", source)
        self.assertNotIn("com/google/android/apps/inputmethod", source)
        self.assertIn(
            "Java_helium314_keyboard_latin_cjk_MozcNativeBridge_nativeCreate",
            source,
        )

    def test_bridge_uses_real_data_backed_public_mozc_apis(self) -> None:
        source = SOURCE.read_text(encoding="utf-8")

        self.assertIn("mozc::DataManager::CreateFromFile", source)
        self.assertIn("mozc::Engine::CreateEngine", source)
        self.assertIn("handler_->EvalCommand", source)
        self.assertIn("mozc::commands::Input::CREATE_SESSION", source)
        self.assertIn("mozc::commands::Input::DELETE_SESSION", source)
        self.assertIn("mozc::commands::Input::SEND_KEY", source)
        self.assertIn("mozc::commands::Input::SEND_COMMAND", source)
        self.assertIn("command.mutable_input()->set_id(session_id_)", source)
        self.assertIn("Never fall back", source)
        self.assertNotIn("return mozc::Engine::CreateEngine();", source)

    def test_bridge_reconstructs_a_small_command_allowlist(self) -> None:
        source = SOURCE.read_text(encoding="utf-8")

        self.assertIn("bool CopyAllowedInput", source)
        self.assertIn("CopyAllowedInput(parsed.input(), session_id_, &command)", source)
        self.assertIn("SessionCommand::SELECT_CANDIDATE", source)
        self.assertIn("SessionCommand::CONVERT_PREV_PAGE", source)
        self.assertIn("SessionCommand::CONVERT_NEXT_PAGE", source)
        self.assertIn("SessionCommand::TURN_ON_IME", source)
        self.assertIn("bool IsAllowedCompositionMode", source)
        self.assertIn("case mozc::commands::HIRAGANA", source)
        self.assertIn("case mozc::commands::DIRECT", source)
        self.assertNotIn("*command.mutable_input() = parsed.input()", source)

    def test_build_target_excludes_upstream_google_jni_and_keeps_16k_linking(self) -> None:
        build = BUILD_TEMPLATE.read_text(encoding="utf-8")

        self.assertIn('name = "frostkeys_mozc"', build)
        self.assertIn('name = "frostkeys_mozc.arm64"', build)
        self.assertIn("-Wl,-z,max-page-size=16384", build)
        self.assertNotIn('deps = [":mozcjni"', build)
        self.assertNotIn("mozcjni.cc", build)


if __name__ == "__main__":
    unittest.main()
