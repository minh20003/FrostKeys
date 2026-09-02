// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MozcCompositionEngineTest {
    @Test
    fun candidateSelectionUsesMozcsStableIdAndSurfacesItsResult() {
        val native = FakeSession(
            MozcTestProto.commandResponse(),
            MozcTestProto.commandResponse(
                preedit = "かんじ",
                candidates = listOf(
                    MozcTestProto.Candidate(id = 101, index = 0, value = "感じ"),
                    MozcTestProto.Candidate(id = 202, index = 1, value = "漢字"),
                ),
            ),
            MozcTestProto.commandResponse(result = "漢字"),
        )
        val engine = MozcCompositionEngine.createForTesting(native, inputMode = "kana")

        val composing = engine.processKey("kanji")
        val selected = engine.selectCandidate(1)

        assertEquals("かんじ", composing.preedit)
        assertEquals(listOf("感じ", "漢字"), composing.candidates)
        assertEquals("漢字", selected.resultText)
        assertEquals("漢字", engine.latestResultText)
        assertEquals(MozcWireCodec.activateHiragana().toList(), native.commands[0].toList())
        assertEquals(MozcWireCodec.sendKey("kanji").toList(), native.commands[1].toList())
        assertEquals(MozcWireCodec.selectCandidate(202).toList(), native.commands[2].toList())
    }

    @Test
    fun pageSubmitAndRevertUseOnlyTheReviewedCommands() {
        val native = FakeSession(
            MozcTestProto.commandResponse(),
            MozcTestProto.commandResponse(
                preedit = "かな",
                candidates = listOf(MozcTestProto.Candidate(id = 1, index = 0, value = "仮名")),
            ),
            MozcTestProto.commandResponse(
                preedit = "かな",
                candidates = listOf(MozcTestProto.Candidate(id = 10, index = 9, value = "カナ")),
                totalCandidateCount = 20,
            ),
            MozcTestProto.commandResponse(
                preedit = "かな",
                candidates = listOf(MozcTestProto.Candidate(id = 1, index = 0, value = "仮名")),
            ),
            MozcTestProto.commandResponse(result = "仮名"),
            // A buggy/native REVERT response must not leave visible composition behind.
            MozcTestProto.commandResponse(
                preedit = "ignored",
                candidates = listOf(MozcTestProto.Candidate(id = 8, index = 0, value = "ignored")),
            ),
        )
        val engine = MozcCompositionEngine.createForTesting(native)

        engine.processKey("kana")
        val next = engine.nextCandidatePage()
        val previous = engine.previousCandidatePage()
        val submitted = engine.commit()
        val reset = engine.reset()

        assertEquals(1, next.page)
        assertEquals(0, previous.page)
        assertEquals("仮名", submitted.resultText)
        assertEquals("", reset.preedit)
        assertTrue(reset.candidates.isEmpty())
        assertNull(reset.resultText)
        assertEquals(
            listOf(
                MozcWireCodec.activateHiragana(),
                MozcWireCodec.sendKey("kana"),
                MozcWireCodec.nextPage(),
                MozcWireCodec.previousPage(),
                MozcWireCodec.submit(),
                MozcWireCodec.revert(),
            ).map { it.toList() },
            native.commands.map { it.toList() },
        )
    }

    @Test
    fun cancellationDropsLateNativeOutputAndClosesExactlyOnce() {
        val cancelled = AtomicBoolean(false)
        val native = FakeSession(
            MozcTestProto.commandResponse(),
            MozcTestProto.commandResponse(preedit = "stale"),
        )
        val engine = MozcCompositionEngine.createForTesting(
            nativeSession = native,
            cancellation = EngineBundleCancellation { cancelled.get() },
        )
        native.afterEvaluate = { cancelled.set(true) }

        val cancelledState = engine.processKey("a")
        val commandCountAfterCancellation = native.commands.size
        val repeated = engine.processKey("b")
        engine.close()

        assertEquals("mozc-cancelled", cancelledState.error)
        assertEquals("", cancelledState.preedit)
        assertEquals(cancelledState, repeated)
        assertEquals(commandCountAfterCancellation, native.commands.size)
        assertEquals(1, native.closeCalls)
    }

    @Test
    fun malformedNativeResponseClosesTheSessionBeforeItEscapes() {
        val native = FakeSession(
            MozcTestProto.commandResponse(),
            byteArrayOf(0x0a, 0x00), // Command with input only; no Output.
        )
        val engine = MozcCompositionEngine.createForTesting(native)

        assertFailsWith<MozcWireFormatException> {
            engine.processKey("a")
        }
        assertEquals(1, native.closeCalls)
        assertEquals("mozc-engine-failure", engine.state.error)
    }

    @Test
    fun candidateWithoutNativeIdCannotBeSelectedByVisibleIndexGuessing() {
        val native = FakeSession(
            MozcTestProto.commandResponse(),
            MozcTestProto.commandResponse(
                candidates = listOf(MozcTestProto.Candidate(id = null, index = 0, value = "候補")),
            ),
        )
        val engine = MozcCompositionEngine.createForTesting(native)

        val beforeSelection = engine.processKey("a")
        val afterSelection = engine.selectCandidate(0)

        assertEquals(beforeSelection, afterSelection)
        assertEquals(2, native.commands.size)
    }

    @Test
    fun reviewedModeSwitchClearsConversionAndTracksPagedWindowBounds() {
        val native = FakeSession(
            MozcTestProto.commandResponse(),
            MozcTestProto.commandResponse(
                preedit = "かな",
                candidates = listOf(MozcTestProto.Candidate(id = 1, index = 0, value = "仮名")),
                totalCandidateCount = 20,
            ),
            MozcTestProto.commandResponse(), // Revert before a mode transition.
            MozcTestProto.commandResponse(), // TURN_ON_IME Katakana.
        )
        val engine = MozcCompositionEngine.createForTesting(native)

        val composing = engine.processKey("kana")
        val switched = engine.switchInputMode(MozcInputMode.KATAKANA.stableId)

        assertTrue(composing.canPageForward)
        assertTrue(!composing.canPageBackward)
        assertEquals(MozcInputMode.KATAKANA.stableId, switched.inputMode)
        assertEquals("", switched.preedit)
        assertTrue(switched.candidates.isEmpty())
        assertEquals(
            listOf(
                MozcWireCodec.activateHiragana(),
                MozcWireCodec.sendKey("kana"),
                MozcWireCodec.revert(),
                MozcWireCodec.activate(MozcInputMode.KATAKANA),
            ).map { it.toList() },
            native.commands.map { it.toList() },
        )
    }

    private class FakeSession(vararg responses: ByteArray) : MozcNativeSession {
        private val queuedResponses = ArrayDeque(responses.toList())
        val commands = mutableListOf<ByteArray>()
        var closeCalls = 0
        var afterEvaluate: (() -> Unit)? = null

        override fun evaluate(command: ByteArray): ByteArray {
            commands += command.copyOf()
            val response = check(queuedResponses.isNotEmpty()) { "No fake Mozc response was queued" }
            afterEvaluate?.invoke()
            return queuedResponses.removeFirst()
        }

        override fun dataVersion(): String = "fixture-data"

        override fun close() {
            closeCalls++
        }
    }
}
