// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CompositionSessionControllerTest {
    @Test
    fun candidateSelectionCommitsTheCurrentCandidateAndClearsComposition() {
        val target = RecordingTarget()
        val published = mutableListOf<CompositionState>()
        val controller = CompositionSessionController(target, CompositionStateSink { published += it })
        val engine = ScriptedEngine("pinyin")
        val session = controller.begin("pinyin")

        assertTrue(session.attach(engine))
        session.processKey("ni")
        val selected = session.selectCandidate(1)

        assertEquals("候補B", target.committedTexts.single())
        assertEquals(listOf("ni"), target.composingTexts)
        assertEquals(1, engine.selectCalls)
        assertEquals("", selected.preedit)
        assertTrue(selected.candidates.isEmpty())
        assertEquals(selected, published.last())

        // An invalid index is rejected before it reaches a native engine or InputConnection.
        val beforeInvalidSelection = target.calls.toList()
        val unchanged = session.selectCandidate(9)
        assertEquals(1, engine.selectCalls)
        assertEquals(beforeInvalidSelection, target.calls)
        assertEquals(selected, unchanged)
    }

    @Test
    fun nativeResultIsCommittedInsteadOfThePreviousPreedit() {
        val target = RecordingTarget()
        val controller = CompositionSessionController(target)
        val engine = ScriptedEngine(mode = "kana", commitResult = "漢字")
        val session = controller.begin("kana")
        assertTrue(session.attach(engine))

        session.processKey("かんじ")
        val afterCommit = session.commit()

        // Mozc's Result can differ from its old reading/preedit. The controller must send the
        // native conversion result, rather than committing the old "かんじ" snapshot.
        assertEquals(listOf("漢字"), target.committedTexts)
        assertEquals("漢字", afterCommit.resultText)
        assertEquals("", afterCommit.preedit)
    }

    @Test
    fun replacementSessionRejectsLateEngineAndOldSessionInput() {
        val target = RecordingTarget()
        val controller = CompositionSessionController(target)
        val oldEngine = ScriptedEngine("pinyin")
        val oldSession = controller.begin("pinyin")
        assertTrue(oldSession.attach(oldEngine))
        oldSession.processKey("ni")

        val replacement = controller.begin("kana")
        val lateOldEngine = ScriptedEngine("pinyin")
        val replacementEngine = ScriptedEngine("kana")

        assertEquals(CompositionSessionLifecycle.Cancelled, oldSession.lifecycle)
        assertEquals(1, oldEngine.closeCalls)
        assertEquals(listOf("ni"), target.finishedCompositions)
        assertFalse(oldSession.attach(lateOldEngine))
        assertEquals(1, lateOldEngine.closeCalls)
        assertTrue(replacement.attach(replacementEngine))

        val oldProcessCalls = oldEngine.processCalls
        val callsBeforeOldInput = target.calls.toList()
        oldSession.processKey("hao")
        assertEquals(oldProcessCalls, oldEngine.processCalls)
        assertEquals(callsBeforeOldInput, target.calls)
        assertEquals(CompositionSessionLifecycle.Active, replacement.lifecycle)
    }

    @Test
    fun reentrantSubtypeSwitchDiscardsOldSnapshotAfterInputConnectionCall() {
        val target = RecordingTarget()
        val published = mutableListOf<CompositionState>()
        val controller = CompositionSessionController(target, CompositionStateSink { published += it })
        val oldEngine = ScriptedEngine("pinyin")
        val oldSession = controller.begin("pinyin")
        assertTrue(oldSession.attach(oldEngine))
        published.clear()

        var replacement: CompositionSession? = null
        target.onSetComposingText = {
            replacement = controller.begin("kana")
        }
        val returned = oldSession.processKey("ni")

        // The target caused a new session while setComposingText was in progress. The pinyin
        // state must not be sent to the candidate strip after that replacement is announced.
        assertEquals(CompositionSessionLifecycle.Cancelled, oldSession.lifecycle)
        assertEquals("", returned.preedit)
        assertEquals(listOf(CompositionState(inputMode = "kana", busy = true)), published)
        assertEquals(listOf("ni"), target.composingTexts)
        assertEquals(listOf("ni"), target.finishedCompositions)
        assertTrue(requireNotNull(replacement).attach(ScriptedEngine("kana")))
        assertEquals("kana", published.last().inputMode)
        assertFalse(published.any { it.inputMode == "pinyin" && it.preedit == "ni" })
    }

    @Test
    fun memoryPressureReleasesEngineFinishesPreeditAndRejectsLateAttach() {
        val target = RecordingTarget()
        val published = mutableListOf<CompositionState>()
        val controller = CompositionSessionController(target, CompositionStateSink { published += it })
        val engine = ScriptedEngine("pinyin")
        val session = controller.begin("pinyin")
        assertTrue(session.attach(engine))
        session.processKey("ni")

        assertFalse(controller.onTrimMemory(9))
        assertEquals(0, engine.closeCalls)
        assertTrue(controller.onTrimMemory(10))

        assertEquals(CompositionSessionLifecycle.ReleasedForMemoryPressure, session.lifecycle)
        assertEquals(1, engine.closeCalls)
        assertEquals(listOf("ni"), target.finishedCompositions)
        assertEquals(CompositionState(inputMode = "pinyin"), published.last())
        assertFalse(controller.onTrimMemory(10))

        val lateEngine = ScriptedEngine("pinyin")
        assertFalse(session.attach(lateEngine))
        assertEquals(1, lateEngine.closeCalls)
    }

    @Test
    fun unavailableInputConnectionFailsAndReleasesTheEngine() {
        val target = RecordingTarget(setComposingResult = false)
        val published = mutableListOf<CompositionState>()
        val controller = CompositionSessionController(target, CompositionStateSink { published += it })
        val engine = ScriptedEngine("pinyin")
        val session = controller.begin("pinyin")
        assertTrue(session.attach(engine))

        val failure = session.processKey("ni")

        assertIs<CompositionSessionLifecycle.Failed>(session.lifecycle)
        assertEquals("input-connection-unavailable", failure.error)
        assertEquals(1, engine.closeCalls)
        assertEquals(listOf("ni"), target.finishedCompositions)
        assertEquals(failure, published.last())
    }

    @Test
    fun engineCallsAreSerializedWhenCallersArriveOnDifferentThreads() {
        val target = RecordingTarget()
        val controller = CompositionSessionController(target)
        val engine = BlockingEngine()
        val session = controller.begin("pinyin")
        assertTrue(session.attach(engine))

        val first = Thread { session.processKey("first") }
        first.start()
        assertTrue(engine.firstEntered.await(2, TimeUnit.SECONDS))

        val second = Thread { session.processKey("second") }
        second.start()
        // The first native call owns the controller's serialized engine section, so a second
        // caller cannot execute its engine operation before the first one completes.
        assertFalse(engine.secondEntered.await(150, TimeUnit.MILLISECONDS))

        engine.releaseFirst.countDown()
        first.join(2_000)
        second.join(2_000)

        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
        assertEquals(listOf("first", "second"), engine.processOrder)
        assertEquals(1, engine.maxConcurrentCalls)
    }

    @Test
    fun cancellationTokenIsVisibleWhileNativeEvaluationStillOwnsTheControllerLock() {
        val target = RecordingTarget()
        val controller = CompositionSessionController(target)
        val engine = BlockingEngine()
        val session = controller.begin("pinyin")
        assertTrue(session.attach(engine))

        val evaluation = Thread { session.processKey("first") }
        evaluation.start()
        assertTrue(engine.firstEntered.await(2, TimeUnit.SECONDS))

        val cancellationReturned = CountDownLatch(1)
        val cancellation = Thread {
            session.cancel()
            cancellationReturned.countDown()
        }
        cancellation.start()

        // Cancellation must not wait for a potentially slow native EvalCommand to release
        // stateLock. The engine can observe this token and stop cooperatively.
        assertTrue(waitUntil(1_000) { session.cancellation.isCancellationRequested() })
        assertFalse(cancellationReturned.await(150, TimeUnit.MILLISECONDS))

        engine.releaseFirst.countDown()
        evaluation.join(2_000)
        cancellation.join(2_000)

        assertFalse(evaluation.isAlive)
        assertFalse(cancellation.isAlive)
        assertEquals(CompositionSessionLifecycle.Cancelled, session.lifecycle)
    }

    @Test
    fun explicitFailurePublishesStableErrorAndReleasesEngine() {
        val target = RecordingTarget()
        val published = mutableListOf<CompositionState>()
        val controller = CompositionSessionController(target, CompositionStateSink { published += it })
        val engine = ScriptedEngine("kana")
        val session = controller.begin("kana")
        assertTrue(session.attach(engine))

        val failed = session.fail("mozc-bundle-unavailable")

        assertIs<CompositionSessionLifecycle.Failed>(session.lifecycle)
        assertEquals("mozc-bundle-unavailable", failed.error)
        assertEquals(failed, published.last())
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun dedicatedCandidateActionsUseOnlyPagedAndModeCapabilities() {
        val target = RecordingTarget()
        val controller = CompositionSessionController(target)
        val engine = PagedModeEngine()
        val session = controller.begin(MozcInputMode.HIRAGANA.stableId)
        assertTrue(session.attach(engine))

        session.processKey("kanji")
        val next = session.nextCandidatePage()
        val previous = session.previousCandidatePage()
        val switched = session.switchInputMode(MozcInputMode.KATAKANA.stableId)

        assertEquals(1, engine.nextCalls)
        assertEquals(1, engine.previousCalls)
        assertEquals(1, next.page)
        assertEquals(0, previous.page)
        assertEquals(MozcInputMode.KATAKANA.stableId, switched.inputMode)
        assertEquals(listOf(MozcInputMode.KATAKANA.stableId), engine.modeRequests)
        assertEquals(listOf("kanji"), target.finishedCompositions)
    }

    private class RecordingTarget(
        private val setComposingResult: Boolean = true,
    ) : CompositionInputTarget {
        val calls = mutableListOf<String>()
        val composingTexts = mutableListOf<String>()
        val committedTexts = mutableListOf<String>()
        val finishedCompositions = mutableListOf<String>()
        var onSetComposingText: ((String) -> Unit)? = null

        override fun setComposingText(text: String): Boolean {
            calls += "set:$text"
            composingTexts += text
            val callback = onSetComposingText
            onSetComposingText = null
            callback?.invoke(text)
            return setComposingResult
        }

        override fun commitText(text: String): Boolean {
            calls += "commit:$text"
            committedTexts += text
            return true
        }

        override fun finishComposingText(): Boolean {
            calls += "finish"
            // Record the composition that was active just before finish. The test fake only
            // needs its value for assertions; it is not an editor implementation.
            finishedCompositions += composingTexts.lastOrNull().orEmpty()
            return true
        }
    }

    private class ScriptedEngine(
        private val mode: String,
        private val commitResult: String? = null,
    ) : CompositionEngine {
        var processCalls = 0
        var selectCalls = 0
        var closeCalls = 0

        override fun processKey(key: String): CompositionState {
            processCalls++
            return CompositionState(
                preedit = key,
                candidates = listOf("候補A", "候補B"),
                inputMode = mode,
            )
        }

        override fun backspace(): CompositionState = CompositionState(inputMode = mode)

        override fun selectCandidate(index: Int): CompositionState {
            selectCalls++
            return CompositionState(inputMode = mode)
        }

        override fun commit(): CompositionState = CompositionState(
            inputMode = mode,
            resultText = commitResult,
        )

        override fun reset(): CompositionState = CompositionState(inputMode = mode)

        override fun close() {
            closeCalls++
        }
    }

    private class BlockingEngine : CompositionEngine {
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val processOrder = mutableListOf<String>()
        private val lock = Any()
        private var concurrentCalls = 0
        var maxConcurrentCalls = 0
            private set

        override fun processKey(key: String): CompositionState {
            synchronized(lock) {
                concurrentCalls++
                maxConcurrentCalls = maxOf(maxConcurrentCalls, concurrentCalls)
                processOrder += key
            }
            try {
                when (key) {
                    "first" -> {
                        firstEntered.countDown()
                        check(releaseFirst.await(2, TimeUnit.SECONDS)) { "Test did not release first call" }
                    }

                    "second" -> secondEntered.countDown()
                }
                return CompositionState(preedit = key, inputMode = "pinyin")
            } finally {
                synchronized(lock) {
                    concurrentCalls--
                }
            }
        }

        override fun backspace(): CompositionState = CompositionState(inputMode = "pinyin")

        override fun selectCandidate(index: Int): CompositionState = CompositionState(inputMode = "pinyin")

        override fun commit(): CompositionState = CompositionState(inputMode = "pinyin")

        override fun reset(): CompositionState = CompositionState(inputMode = "pinyin")

        override fun close() = Unit
    }

    private class PagedModeEngine : PagedCompositionEngine, ModeSwitchingCompositionEngine {
        var nextCalls = 0
        var previousCalls = 0
        val modeRequests = mutableListOf<String>()
        private var mode = MozcInputMode.HIRAGANA.stableId

        override fun processKey(key: String): CompositionState = CompositionState(
            preedit = key,
            candidates = listOf("候補A", "候補B"),
            canPageForward = true,
            inputMode = mode,
        )

        override fun backspace(): CompositionState = CompositionState(inputMode = mode)

        override fun selectCandidate(index: Int): CompositionState = CompositionState(inputMode = mode)

        override fun previousCandidatePage(): CompositionState {
            previousCalls++
            return CompositionState(
                preedit = "kanji",
                candidates = listOf("候補A", "候補B"),
                canPageForward = true,
                inputMode = mode,
            )
        }

        override fun nextCandidatePage(): CompositionState {
            nextCalls++
            return CompositionState(
                preedit = "kanji",
                candidates = listOf("候補C", "候補D"),
                page = 1,
                canPageBackward = true,
                inputMode = mode,
            )
        }

        override fun switchInputMode(inputMode: String): CompositionState {
            modeRequests += inputMode
            mode = inputMode
            return CompositionState(inputMode = mode)
        }

        override fun commit(): CompositionState = CompositionState(inputMode = mode)

        override fun reset(): CompositionState = CompositionState(inputMode = mode)

        override fun close() = Unit
    }

    private fun waitUntil(timeoutMillis: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (System.nanoTime() < deadline) {
            if (predicate()) return true
            Thread.sleep(5)
        }
        return predicate()
    }
}
