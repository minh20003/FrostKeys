// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.event

import android.view.KeyEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class VietnameseCombinerTest {
    @Test
    fun telexBuildsVietnameseLettersAndTones() {
        val combiner = VietnameseTelexCombiner()

        assertEquals("tiếng", compose(combiner, "tieengs"))
        assertEquals("Việt", compose(combiner, "Vieetj"))
        assertEquals("đăng", compose(combiner, "ddawng"))
        assertEquals("ương", compose(combiner, "uowng"))
    }

    @Test
    fun telexUsesCommonTonePlacementAndRelocatesWhenACodaIsTyped() {
        val combiner = VietnameseTelexCombiner()

        assertEquals("hòa", compose(combiner, "hoaf"))
        assertEquals("thúy", compose(combiner, "thuys"))
        assertEquals("thủy", compose(combiner, "thuyr"))
        assertEquals("hoán", compose(combiner, "hoasn"))
        assertEquals("quyến", compose(combiner, "quyeens"))
        assertEquals("giờ", compose(combiner, "giowf"))
    }

    @Test
    fun telexAllowsShapeMarkersAfterTheCodaAndAfterTone() {
        // Free/end-of-word marking is the familiar Vietnamese workflow: the shape marker is
        // allowed after the final consonant rather than only immediately after its vowel.
        assertEquals("đằng", compose(VietnameseTelexCombiner(), "ddangwf"))
        assertEquals("đằng", compose(VietnameseTelexCombiner(), "ddangfw"))
        assertEquals("tân", compose(VietnameseTelexCombiner(), "tana"))
        assertEquals("tiếng", compose(VietnameseTelexCombiner(), "tienges"))
        assertEquals("tiếng", compose(VietnameseTelexCombiner(), "tiengse"))

        // A delayed w advances through the remaining raw vowels from right to left. The normal
        // immediate `uow` shortcut remains covered by the repeated-key escape test below.
        val delayedHorn = VietnameseTelexCombiner()
        type(delayedHorn, "duongw")
        assertEquals("duơng", delayedHorn.combiningStateFeedback.toString())
        type(delayedHorn, "wfd")
        assertEquals("đường", delayedHorn.combiningStateFeedback.toString())
        assertTrue(Normalizer.isNormalized(delayedHorn.combiningStateFeedback, Normalizer.Form.NFC))

        assertEquals("ĐẰNG", compose(VietnameseTelexCombiner(), "DDANGWF"))
    }

    @Test
    fun telexSupportsAlternateTonePlacementAndZClear() {
        val alternate = VietnameseTelexCombiner { VietnameseTonePlacement.ALTERNATE }
        assertEquals("hoà", compose(alternate, "hoaf"))
        assertEquals("thuý", compose(alternate, "thuys"))

        val common = VietnameseTelexCombiner()
        type(common, "ddawngz")
        assertEquals("dang", common.combiningStateFeedback.toString())
    }

    @Test
    fun telexRepeatedTriggersEscapeOnlyGeneratedTransforms() {
        val combiner = VietnameseTelexCombiner()

        assertEquals("aa", compose(combiner, "aaa"))
        assertEquals("dd", compose(VietnameseTelexCombiner(), "ddd"))
        assertEquals("as", compose(VietnameseTelexCombiner(), "ass"))
        assertEquals("uow", compose(VietnameseTelexCombiner(), "uoww"))
        assertEquals("AA", compose(VietnameseTelexCombiner(), "AAA"))

        // A letter entered directly (for example through a long-press popup) was not produced
        // by this combiner, so the next a remains literal rather than undoing it.
        assertEquals("âa", compose(VietnameseTelexCombiner(), "âa"))
    }

    @Test
    fun toneStaysOnTheVietnameseNucleusWhenShapeComesBeforeOrAfterIt() {
        val telex = VietnameseTelexCombiner()
        type(telex, "ties")
        assertEquals("tíe", telex.combiningStateFeedback.toString())
        assertTrue(press(telex, 'e').isConsumed)
        assertEquals("tiế", telex.combiningStateFeedback.toString())

        val vni = VietnameseVniCombiner()
        type(vni, "tie16")
        assertEquals("tiế", vni.combiningStateFeedback.toString())

        val horn = VietnameseTelexCombiner()
        type(horn, "uowsng")
        assertEquals("ướng", horn.combiningStateFeedback.toString())
    }

    @Test
    fun telexBackspaceRestoresThePreviousVisualState() {
        val combiner = VietnameseTelexCombiner()
        type(combiner, "ddawng")
        assertEquals("đăng", combiner.combiningStateFeedback.toString())

        assertTrue(backspace(combiner).isConsumed)
        assertEquals("đăn", combiner.combiningStateFeedback.toString())
        assertTrue(backspace(combiner).isConsumed)
        assertEquals("đă", combiner.combiningStateFeedback.toString())
        assertTrue(backspace(combiner).isConsumed)
        assertEquals("đa", combiner.combiningStateFeedback.toString())
    }

    @Test
    fun delayedTelexShapeMarkersRemainIndividuallyUndoable() {
        val combiner = VietnameseTelexCombiner()
        type(combiner, "ddangwf")
        assertEquals("đằng", combiner.combiningStateFeedback.toString())

        assertTrue(backspace(combiner).isConsumed)
        assertEquals("đăng", combiner.combiningStateFeedback.toString())
        assertTrue(backspace(combiner).isConsumed)
        assertEquals("đang", combiner.combiningStateFeedback.toString())
        assertTrue(backspace(combiner).isConsumed)
        assertEquals("đan", combiner.combiningStateFeedback.toString())
    }

    @Test
    fun backspaceRestoresEscapedAndClearedVietnameseStatesOneKeyAtATime() {
        val telex = VietnameseTelexCombiner()
        type(telex, "aaa")
        assertEquals("aa", telex.combiningStateFeedback.toString())
        assertTrue(backspace(telex).isConsumed)
        assertEquals("â", telex.combiningStateFeedback.toString())
        assertTrue(backspace(telex).isConsumed)
        assertEquals("a", telex.combiningStateFeedback.toString())

        // Start an independent syllable. With free end-of-word marking enabled, appending a
        // second artificial test word without a separator would intentionally remain one Telex
        // composition and may reshape the earlier `a`.
        telex.reset()
        type(telex, "ddawngz")
        assertEquals("dang", telex.combiningStateFeedback.toString())
        assertTrue(backspace(telex).isConsumed)
        assertEquals("đăng", telex.combiningStateFeedback.toString())

        val vni = VietnameseVniCombiner()
        type(vni, "tie6ng10")
        assertEquals("tieng", vni.combiningStateFeedback.toString())
        assertTrue(backspace(vni).isConsumed)
        assertEquals("tiếng", vni.combiningStateFeedback.toString())
    }

    @Test
    fun vniBuildsTonesAndLeavesUnusableDigitsLiteral() {
        val combiner = VietnameseVniCombiner()

        assertEquals("tiếng", compose(combiner, "tie6ng1"))
        assertEquals("ường", compose(combiner, "u7o7ng2"))

        val literal = VietnameseVniCombiner()
        type(literal, "b")
        val commit = press(literal, '1')
        assertFalse(commit.isConsumed)
        assertEquals("b", commit.textToCommit.toString())
        assertEquals('1'.code, commit.nextEvent?.codePoint)
        assertEquals("", literal.combiningStateFeedback.toString())
    }

    @Test
    fun vniAllowsShapeMarkersAfterTheCoda() {
        assertEquals("đằng", compose(VietnameseVniCombiner(), "dang982"))
        assertEquals("đằng", compose(VietnameseVniCombiner(), "dang298"))
        assertEquals("tiếng", compose(VietnameseVniCombiner(), "tieng61"))
        // The two delayed 7 markers must each reach one raw vowel before the final tone.
        assertEquals("đường", compose(VietnameseVniCombiner(), "duong9772"))
    }

    @Test
    fun vniRepeatedTriggersEscapeAndZeroClearsMarks() {
        assertEquals("a6", compose(VietnameseVniCombiner(), "a66"))
        assertEquals("a1", compose(VietnameseVniCombiner(), "a11"))
        assertEquals("d9", compose(VietnameseVniCombiner(), "d99"))

        val clear = VietnameseVniCombiner()
        type(clear, "d9a8ng0")
        assertEquals("dang", clear.combiningStateFeedback.toString())
        val literalZero = press(clear, '0')
        assertFalse(literalZero.isConsumed)
        assertEquals("dang", literalZero.textToCommit.toString())
        assertEquals('0'.code, literalZero.nextEvent?.codePoint)
    }

    @Test
    fun vietnameseOutputIsNfcAndHardwareShiftRemainsComposable() {
        val telex = VietnameseTelexCombiner()
        val uppercase = compose(telex, "TIEENGS")
        assertEquals("TIẾNG", uppercase)
        assertTrue(Normalizer.isNormalized(uppercase, Normalizer.Form.NFC))

        val hardware = VietnameseTelexCombiner()
        type(hardware, "tieeng")
        val shiftedTone = hardware.processEvent(
            null,
            Event.createHardwareKeypressEvent(
                'S'.code,
                KeyEvent.KEYCODE_S,
                KeyEvent.META_SHIFT_ON,
                null,
                false,
            ),
        )
        assertTrue(shiftedTone.isConsumed)
        assertEquals("tiếng", hardware.combiningStateFeedback.toString())

        // Ctrl/Alt/Meta keys remain editor commands. They commit the outstanding composition
        // and are not reinterpreted as a Telex trigger on a physical keyboard.
        val ctrl = hardware.processEvent(
            null,
            Event.createHardwareKeypressEvent(
                's'.code,
                KeyEvent.KEYCODE_S,
                KeyEvent.META_CTRL_ON,
                null,
                false,
            ),
        )
        assertFalse(ctrl.isConsumed)
        assertEquals("tiếng", ctrl.textToCommit.toString())
        assertEquals('s'.code, ctrl.nextEvent?.codePoint)
    }

    @Test
    fun separatorsCommitTheComposedWordBeforeTheirOriginalEvent() {
        val combiner = VietnameseTelexCombiner()
        type(combiner, "tieengs")

        val commit = press(combiner, ' ')
        assertEquals("tiếng", commit.textToCommit.toString())
        assertEquals(KeyCode.MULTIPLE_CODE_POINTS, commit.keyCode)
        assertEquals(' '.code, commit.nextEvent?.codePoint)
        assertEquals("", combiner.combiningStateFeedback.toString())
    }

    @Test
    fun shiftedHardwareLettersRemainVietnameseInputInsteadOfFunctionalCommands() {
        val combiner = VietnameseTelexCombiner()
        val shiftedD = combiner.processEvent(
            null,
            Event.createHardwareKeypressEvent(
                'D'.code,
                KeyEvent.KEYCODE_D,
                KeyEvent.META_SHIFT_ON,
                null,
                false,
            ),
        )
        assertTrue(shiftedD.isConsumed)
        type(combiner, "dawng")
        assertEquals("Đăng", combiner.combiningStateFeedback.toString())
    }

    private fun type(combiner: Combiner, text: String) {
        text.forEach { character ->
            val event = press(combiner, character)
            assertTrue("$character should be consumed while composing", event.isConsumed)
        }
    }

    private fun compose(combiner: Combiner, text: String): String {
        type(combiner, text)
        val commit = press(combiner, ' ')
        assertFalse("A separator must be forwarded after committing the composition", commit.isConsumed)
        return commit.textToCommit.toString()
    }

    private fun press(combiner: Combiner, character: Char): Event = combiner.processEvent(
        null,
        Event.createEventForCodePointFromUnknownSource(character.code)
    )

    private fun backspace(combiner: Combiner): Event = combiner.processEvent(
        null,
        Event.createSoftwareKeypressEvent(KeyCode.DELETE, 0, 0, 0, false)
    )
}
