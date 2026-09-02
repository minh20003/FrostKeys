// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

/**
 * The deliberately small set of reviewed Mozc input modes exposed by FrostKeys.
 *
 * [stableId] is passed through the composition-controller boundary, never a translated UI
 * string. The wire codec maps this closed enum to the matching `commands.CompositionMode`, so a
 * view cannot manufacture an arbitrary native command by changing a label or menu item.
 */
enum class MozcInputMode(
    val stableId: String,
    val displayGlyph: String,
) {
    HIRAGANA("hiragana", "あ"),
    KATAKANA("katakana", "ア"),
    LATIN("latin", "A"),
    ;

    companion object {
        fun fromStableId(value: String): MozcInputMode? = entries.firstOrNull {
            it.stableId == value
        }
    }
}
