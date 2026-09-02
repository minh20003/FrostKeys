// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import android.view.inputmethod.InputMethodSubtype
import helium314.keyboard.latin.BuildConfig

/** Stable identity shared by generated IME metadata and the optional Mozc runtime. */
object MozcJapaneseSubtype {
    const val SUBTYPE_ID = 0x7c16f003
    const val BUNDLE_ID = "mozc-japanese"
    const val MANIFEST_ASSET_PATH = "cjk/mozc/commit-851c3fe/manifest.json"

    /**
     * A stale system subtype must never cause the engine to load after a bundle-less update.
     *
     * `@xml/method` is generated from the same Gradle input, so this is a defence-in-depth check
     * rather than a substitute for Android's static subtype metadata.
     */
    /** Identifies the fixed metadata subtype even when a prior install left it in system state. */
    @JvmStatic
    fun hasMozcSubtypeId(subtype: InputMethodSubtype?): Boolean = subtype?.hashCode() == SUBTYPE_ID

    @JvmStatic
    fun isSelected(subtype: InputMethodSubtype?): Boolean = BuildConfig.FROSTKEYS_MOZC_BUNDLE_ENABLED
        && hasMozcSubtypeId(subtype)
}
