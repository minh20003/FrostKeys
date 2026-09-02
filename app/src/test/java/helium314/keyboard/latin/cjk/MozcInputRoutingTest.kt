// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MozcInputRoutingTest {
    @Test
    fun printableRomajiAlwaysRoutesToMozc() {
        assertTrue(MozcInputRouting.shouldRouteTextToMozc(
            text = "Kanji-123",
            currentState = CompositionState(inputMode = MozcInputMode.HIRAGANA.stableId),
            hasBufferedComposition = false,
        ))
    }

    @Test
    fun spaceStartsConversionOnlyForLiveOrBufferedComposition() {
        val empty = CompositionState(inputMode = MozcInputMode.HIRAGANA.stableId)
        val composing = empty.copy(preedit = "かんじ", candidates = listOf("感じ"))

        assertFalse(MozcInputRouting.shouldRouteTextToMozc(" ", empty, false))
        assertTrue(MozcInputRouting.shouldRouteTextToMozc(" ", composing, false))
        // First-run extraction can still be running when the user types Romaji then Space. The
        // space must be replayed as a Mozc key so it begins conversion, not as a literal suffix.
        assertTrue(MozcInputRouting.shouldRouteTextToMozc(" ", empty, true))
    }

    @Test
    fun textOutsideTheReviewedAsciiPathStaysWithLatinIme() {
        val state = CompositionState(preedit = "かな", inputMode = MozcInputMode.HIRAGANA.stableId)

        assertFalse(MozcInputRouting.shouldRouteTextToMozc("\n", state, false))
        assertFalse(MozcInputRouting.shouldRouteTextToMozc("😀", state, false))
        assertFalse(MozcInputRouting.shouldRouteTextToMozc("日本", state, false))
    }
}
