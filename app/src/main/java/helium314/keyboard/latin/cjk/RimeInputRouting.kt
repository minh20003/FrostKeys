// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

/** Input policy for the narrow offline Pinyin bridge. */
internal object RimeInputRouting {
    /** Rime accepts only individual normalized Pinyin letters or apostrophes as raw keys. */
    fun isPinyinText(text: String): Boolean = RimeWireCodec.normalizedPinyinKey(text) != null

    /** A space selects the first visible candidate instead of becoming a literal space. */
    fun shouldSelectFirstOnSpace(state: CompositionState, hasBufferedComposition: Boolean): Boolean =
        state.hasComposition || hasBufferedComposition
}
