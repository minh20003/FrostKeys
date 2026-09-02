/*
 * Copyright (C) 2012 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.define

object ProductionFlags {
    // FrostKeys needs the same Telex/VNI composition pipeline for USB/Bluetooth keyboards as
    // for the on-screen keyboard. The former upstream disablement bypassed InputLogic entirely,
    // so physical input could never receive Vietnamese combining rules. The runtime path is
    // guarded by InputMethodService and covered by the internal device smoke editor; do not turn
    // this off without replacing that path with an equivalent hardware composition route.
    const val IS_HARDWARE_KEYBOARD_SUPPORTED = true

    /**
     * Include all suggestions from all dictionaries in
     * [helium314.keyboard.latin.SuggestedWords.mRawSuggestions].
     */
    const val INCLUDE_RAW_SUGGESTIONS = false
}
