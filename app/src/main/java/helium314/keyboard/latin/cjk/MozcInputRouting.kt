// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

/**
 * Small, pure input-routing policy shared by soft and decoded hardware events.
 *
 * Mozc receives printable Romaji directly, but a space has special Japanese-IME semantics: when
 * there is a live or buffered preedit it starts/cycles conversion instead of being committed as a
 * literal space by LatinIME. With no composition it deliberately stays on the ordinary input path
 * so the user can still type spaces, editor actions, emoji, and pasted text normally.
 */
internal object MozcInputRouting {
    fun shouldRouteTextToMozc(
        text: String,
        currentState: CompositionState,
        hasBufferedComposition: Boolean,
    ): Boolean {
        if (text.isEmpty()) return false
        if (text == " ") return currentState.hasComposition || hasBufferedComposition
        return text.all { character -> character.code in PRINTABLE_ASCII_RANGE }
    }

    private val PRINTABLE_ASCII_RANGE = 0x21..0x7e
}
