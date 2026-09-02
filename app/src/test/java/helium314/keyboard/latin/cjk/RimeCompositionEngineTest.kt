// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RimeCompositionEngineTest {
    @Test
    fun pinyinKeyAndCandidateSelectionUseOnlyTheNarrowNativeMethods() {
        val native = FakeSession(
            RimePacket.state(preedit = "ni", candidates = listOf("你", "尼")),
            RimePacket.state(result = "你"),
        )
        val engine = RimeCompositionEngine.createForTesting(native)

        val composing = engine.processKey("N")
        val selected = engine.selectCandidate(0)

        assertEquals("ni", composing.preedit)
        assertEquals(listOf("你", "尼"), composing.candidates)
        assertEquals("你", selected.resultText)
        assertEquals(listOf("key:n", "candidate:0"), native.calls)
    }

    @Test
    fun invalidKeyAndCandidateNeverReachNativeRime() {
        val native = FakeSession(RimePacket.state(preedit = "ni", candidates = listOf("你")))
        val engine = RimeCompositionEngine.createForTesting(native)

        val initial = engine.processKey("ni")
        val composing = engine.processKey("n")
        val invalidSelection = engine.selectCandidate(9)

        assertEquals("", initial.preedit)
        assertEquals("ni", composing.preedit)
        assertEquals(composing, invalidSelection)
        assertEquals(listOf("key:n"), native.calls)
    }

    @Test
    fun pagingCommitAndResetAreExplicitAndBounded() {
        val native = FakeSession(
            RimePacket.state(preedit = "ni", candidates = listOf("你"), canPageForward = true),
            RimePacket.state(preedit = "ni", candidates = listOf("尼"), page = 1, canPageBackward = true),
            RimePacket.state(preedit = "ni", candidates = listOf("你"), canPageForward = true),
            RimePacket.state(result = "你"),
            // reset must force the editor-visible state empty even if a broken native library
            // returns an old snapshot.
            RimePacket.state(preedit = "stale", candidates = listOf("stale")),
        )
        val engine = RimeCompositionEngine.createForTesting(native)

        engine.processKey("n")
        val next = engine.nextCandidatePage()
        val previous = engine.previousCandidatePage()
        val committed = engine.commit()
        val reset = engine.reset()

        assertEquals(1, next.page)
        assertEquals(0, previous.page)
        assertEquals("你", committed.resultText)
        assertEquals("", reset.preedit)
        assertTrue(reset.candidates.isEmpty())
        assertNull(reset.resultText)
        assertEquals(listOf("key:n", "page:false", "page:true", "commit", "reset"), native.calls)
    }

    @Test
    fun outputModeClearsOldCompositionAndRejectsUnknownMode() {
        val native = FakeSession(
            RimePacket.state(preedit = "ni", candidates = listOf("你")),
            RimePacket.state(preedit = "stale", candidates = listOf("stale")),
        )
        val engine = RimeCompositionEngine.createForTesting(native)

        engine.processKey("n")
        val traditional = engine.switchInputMode(RimePinyinOutputMode.TRADITIONAL.stableId)
        val unchanged = engine.switchInputMode("arbitrary-config-key")

        assertEquals(RimePinyinOutputMode.TRADITIONAL.stableId, traditional.inputMode)
        assertEquals("", traditional.preedit)
        assertTrue(traditional.candidates.isEmpty())
        assertEquals(traditional, unchanged)
        assertEquals(listOf("key:n", "simplified:false"), native.calls)
    }

    @Test
    fun cancellationDropsLateNativeOutputAndClosesOnce() {
        val cancelled = AtomicBoolean(false)
        val native = FakeSession(RimePacket.state(preedit = "ni"))
        val engine = RimeCompositionEngine.createForTesting(
            nativeSession = native,
            cancellation = EngineBundleCancellation { cancelled.get() },
        )
        native.afterAction = { cancelled.set(true) }

        val cancelledState = engine.processKey("n")
        val repeated = engine.processKey("i")
        engine.close()

        assertEquals("rime-cancelled", cancelledState.error)
        assertEquals(cancelledState, repeated)
        assertEquals(1, native.closeCalls)
        assertEquals(listOf("key:n"), native.calls)
    }

    @Test
    fun malformedNativePacketClosesBeforeItEscapes() {
        val native = FakeSession(byteArrayOf(1, 0))
        val engine = RimeCompositionEngine.createForTesting(native)

        assertFailsWith<RimeWireFormatException> {
            engine.processKey("n")
        }
        assertEquals(1, native.closeCalls)
        assertEquals("rime-engine-failure", engine.state.error)
    }

    private class FakeSession(vararg responses: ByteArray) : RimeNativeSession {
        private val queuedResponses = ArrayDeque(responses.toList())
        val calls = mutableListOf<String>()
        var closeCalls = 0
        var afterAction: (() -> Unit)? = null

        override fun processPinyinKey(keyCode: Int): ByteArray = reply("key:${keyCode.toChar()}")

        override fun backspace(): ByteArray = reply("backspace")

        override fun selectCandidateOnCurrentPage(index: Int): ByteArray = reply("candidate:$index")

        override fun changePage(backward: Boolean): ByteArray = reply("page:$backward")

        override fun commit(): ByteArray = reply("commit")

        override fun reset(): ByteArray = reply("reset")

        override fun setSimplifiedOutput(simplified: Boolean): ByteArray = reply("simplified:$simplified")

        override fun version(): String = "rime-test-data"

        override fun close() {
            closeCalls++
        }

        private fun reply(call: String): ByteArray {
            calls += call
            val response = check(queuedResponses.isNotEmpty()) { "No fake Rime response was queued" }
            afterAction?.invoke()
            return queuedResponses.removeFirst()
        }
    }
}
