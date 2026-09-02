// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import android.view.inputmethod.InputMethodSubtype
import helium314.keyboard.latin.BuildConfig

/** Stable identity for the optional offline Simplified/Traditional Pinyin subtype. */
object RimeChineseSubtype {
    const val SUBTYPE_ID = 0x7c16f004
    const val BUNDLE_ID = "rime-pinyin"
    const val MANIFEST_ASSET_PATH = "cjk/rime/1.16.1/manifest.json"

    /** A system-retained subtype from an older bundle-less build must never load native Rime. */
    @JvmStatic
    fun hasRimeSubtypeId(subtype: InputMethodSubtype?): Boolean = subtype?.hashCode() == SUBTYPE_ID

    @JvmStatic
    fun isSelected(subtype: InputMethodSubtype?): Boolean = BuildConfig.FROSTKEYS_RIME_BUNDLE_ENABLED
        && hasRimeSubtypeId(subtype)
}
