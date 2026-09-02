/*
 * Copyright (C) 2021 Patrick Goldinger
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.keyboard.internal.keyboard_parser.floris

import android.icu.lang.UCharacter
import android.icu.lang.UCharacterCategory

// taken from FlorisBoard
    // Unused FlorisBoard parts removed.
object Unicode {
    fun isNonSpacingMark(code: Int): Boolean {
        return UCharacter.getType(code).toByte() == UCharacterCategory.NON_SPACING_MARK
    }
}
