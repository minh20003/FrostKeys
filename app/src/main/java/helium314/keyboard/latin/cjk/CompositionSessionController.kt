// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import android.view.inputmethod.InputConnection
import helium314.keyboard.latin.RichInputConnection
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The small subset of an [InputConnection] needed by an offline composition engine.
 *
 * This deliberately keeps the engine/controller contract independent from the LatinIME view
 * hierarchy. A future subtype owner can wrap the currently valid [InputConnection] with
 * [InputConnectionCompositionTarget], while unit tests can use a deterministic fake. All calls
 * must be serialized on the IME input thread; a target must not be retained after that input
 * connection becomes invalid.
 */
interface CompositionInputTarget {
    /** Mirrors [InputConnection.setComposingText]. */
    fun setComposingText(text: String): Boolean

    /** Mirrors [InputConnection.commitText] and must end any previous Android composition. */
    fun commitText(text: String): Boolean

    /** Mirrors [InputConnection.finishComposingText]. */
    fun finishComposingText(): Boolean
}

/** Adapter kept here so a future IME lifecycle owner does not need CJK-specific InputConnection code. */
class InputConnectionCompositionTarget(
    private val inputConnection: InputConnection,
) : CompositionInputTarget {
    override fun setComposingText(text: String): Boolean = inputConnection.setComposingText(text, 1)

    override fun commitText(text: String): Boolean = inputConnection.commitText(text, 1)

    override fun finishComposingText(): Boolean = inputConnection.finishComposingText()
}

/**
 * Composition target backed by LatinIME's cache-aware [RichInputConnection].
 *
 * Optional CJK engines must not bypass that wrapper with a raw framework [InputConnection]: the
 * wrapper owns the expected selection and composing-text cache used by the normal input path.
 * Its commit/finish methods intentionally do not expose framework return values, so a disconnected
 * wrapper is treated as an unavailable target before issuing any editor operation.
 */
class RichInputConnectionCompositionTarget(
    private val inputConnection: RichInputConnection,
) : CompositionInputTarget {
    override fun setComposingText(text: String): Boolean {
        if (!inputConnection.isConnected) return false
        return inputConnection.setComposingText(text, 1)
    }

    override fun commitText(text: String): Boolean {
        if (!inputConnection.isConnected) return false
        inputConnection.commitText(text, 1)
        return true
    }

    override fun finishComposingText(): Boolean {
        if (!inputConnection.isConnected) return false
        inputConnection.finishComposingText()
        return true
    }
}

/** Receives immutable state snapshots for a future candidate strip or mode indicator. */
fun interface CompositionStateSink {
    fun onCompositionStateChanged(state: CompositionState)
}

/**
 * Lifecycle of a single optional CJK composition session.
 *
 * A session begins in [Preparing] so an asynchronous bundle activation can be cancelled before it
 * creates native state. [attach] transfers ownership of an engine to this session only while it
 * is still preparing. Terminal lifecycle values permanently reject new keys and native engines.
 */
sealed class CompositionSessionLifecycle {
    data object Preparing : CompositionSessionLifecycle()

    data object Active : CompositionSessionLifecycle()

    data object Cancelled : CompositionSessionLifecycle()

    data object ReleasedForMemoryPressure : CompositionSessionLifecycle()

    data class Failed(val reason: String) : CompositionSessionLifecycle()

    data object Closed : CompositionSessionLifecycle()
}

/**
 * A cancellable, engine-owning composition session.
 *
 * An IME subtype switch should call [cancel] and also cancel the matching
 * [EngineBundleActivation]. [cancellation] can be passed into lower-level installation/factory
 * code so a late background result is closed by [attach] rather than becoming active.
 */
interface CompositionSession : AutoCloseable {
    val state: CompositionState

    val lifecycle: CompositionSessionLifecycle

    /** True once this session can no longer accept an engine or input. */
    val isCancelled: Boolean

    /** Cooperative cancellation suitable for installer/factory work associated with this session. */
    val cancellation: EngineBundleCancellation

    /**
     * Transfers [engine] ownership to this session when it is still current and preparing.
     *
     * Returns false for stale/closed sessions and closes [engine] in that case. This is important
     * when a Rime/Mozc factory finishes after the user has already changed subtype or hidden the
     * keyboard.
     */
    fun attach(engine: CompositionEngine): Boolean

    fun processKey(key: String): CompositionState

    fun backspace(): CompositionState

    fun selectCandidate(index: Int): CompositionState

    /** Requests the previous page when the attached engine supports candidate paging. */
    fun previousCandidatePage(): CompositionState

    /** Requests the next page when the attached engine supports candidate paging. */
    fun nextCandidatePage(): CompositionState

    /** Switches to a stable engine input-mode identifier when the engine supports it. */
    fun switchInputMode(inputMode: String): CompositionState

    fun commit(): CompositionState

    fun reset(): CompositionState

    /**
     * Stops this session with a recoverable engine/bundle failure.
     *
     * This is intentionally distinct from [cancel]: callers use it when the optional engine
     * cannot become available, so the dedicated strip can show a stable error state instead of
     * silently disappearing and being replaced by a late Latin suggestion update.
     */
    fun fail(reason: String): CompositionState

    /** Cancels preedit without committing it and releases an attached engine. */
    fun cancel()

    override fun close()
}

/**
 * Bounded bridge between a pure [CompositionEngine] and IME input/candidate lifecycle.
 *
 * Rime and Mozc IME runtimes use this controller only after their separately verified native/data
 * bundle is staged in the APK. It defines the hand-off used by that wiring:
 *
 * 1. [begin] on a subtype/input-connection change;
 * 2. pass [CompositionSession.cancellation] into lazy engine work;
 * 3. call [CompositionSession.attach] on the IME input thread once a verified engine exists;
 * 4. forward keys/candidate actions; and
 * 5. [CompositionSession.cancel], [onTrimMemory], or [close] on teardown.
 *
 * The controller owns an engine after a successful [CompositionSession.attach], and closes it
 * exactly once on replacement, cancellation, failure, memory pressure, or controller teardown.
 */
class CompositionSessionController(
    private val inputTarget: CompositionInputTarget,
    private val stateSink: CompositionStateSink = CompositionStateSink {},
) : AutoCloseable {
    private data class Termination(
        val engine: CompositionEngine?,
        val needsFinishComposing: Boolean,
        val state: CompositionState,
    )

    private val stateLock = Any()
    private val outputLock = Any()
    @Volatile
    private var activeSession: Session? = null
    private var isClosed = false

    /**
     * Starts a replacement session in a busy state without loading an engine.
     *
     * The previous session is cancelled first, so its native resources can never receive a key
     * after a new subtype has started. The candidate sink sees only the new busy state, avoiding a
     * visible stale empty-state flash during subtype switches.
     */
    fun begin(inputMode: String): CompositionSession {
        require(inputMode.isNotBlank()) { "Input mode must not be blank" }
        // Do not wait for a potentially slow native EvalCommand just to make cancellation
        // observable. The old session's engine receives this atomic signal immediately; normal
        // ownership cleanup still happens under the controller locks below.
        activeSession?.requestCancellation()
        var previousTermination: Termination? = null
        val session: Session
        synchronized(outputLock) {
            synchronized(stateLock) {
                check(!isClosed) { "CompositionSessionController is closed" }
                activeSession?.let { previous ->
                    previousTermination = previous.terminateLocked(CompositionSessionLifecycle.Cancelled)
                }
                session = Session(inputMode)
                activeSession = session
            }
            previousTermination?.let { termination ->
                if (termination.needsFinishComposing) safeFinishComposing()
            }
            notifyState(session.currentState())
        }
        previousTermination?.engine?.let(::closeQuietly)
        return session
    }

    /**
     * Releases the active optional engine at Android's stable RUNNING_LOW threshold or worse.
     *
     * The caller should forward [android.inputmethodservice.InputMethodService.onTrimMemory] here
     * rather than keeping Rime/Mozc resident while the IME is hidden or the process is pressured.
     * Returns true only when a live/preparing session was released.
     */
    fun onTrimMemory(level: Int): Boolean {
        if (level < RUNNING_LOW_MEMORY_LEVEL) return false
        val session = activeSession ?: return false
        session.requestCancellation()
        return session.terminate(CompositionSessionLifecycle.ReleasedForMemoryPressure)
    }

    /** Releases the active session and permanently prevents new sessions. */
    override fun close() {
        activeSession?.requestCancellation()
        var termination: Termination? = null
        synchronized(outputLock) {
            synchronized(stateLock) {
                if (isClosed) return
                isClosed = true
                activeSession?.let { session ->
                    termination = session.terminateLocked(CompositionSessionLifecycle.Closed)
                }
                activeSession = null
            }
            termination?.let { stopped ->
                if (stopped.needsFinishComposing) safeFinishComposing()
                notifyState(stopped.state)
            }
        }
        termination?.engine?.let(::closeQuietly)
    }

    private inner class Session(
        private val initialInputMode: String,
    ) : CompositionSession {
        /**
         * Native evaluation can hold [stateLock] while it is in C++. This flag must therefore
         * never require that lock: a subtype switch/window teardown needs to be visible to an
         * in-flight native engine immediately, not only after EvalCommand returns.
         */
        private val cancellationRequested = AtomicBoolean(false)
        private var ownedEngine: CompositionEngine? = null
        private var currentLifecycle: CompositionSessionLifecycle = CompositionSessionLifecycle.Preparing
        private var currentState = CompositionState(inputMode = initialInputMode, busy = true)
        private var revision = 0L

        override val state: CompositionState
            get() = currentState()

        override val lifecycle: CompositionSessionLifecycle
            get() = synchronized(stateLock) { currentLifecycle }

        override val isCancelled: Boolean
            get() = cancellationRequested.get() || synchronized(stateLock) { !isOpenLocked() }

        override val cancellation: EngineBundleCancellation =
            EngineBundleCancellation { cancellationRequested.get() }

        override fun attach(engine: CompositionEngine): Boolean {
            var accepted = false
            var attachedState: CompositionState? = null
            synchronized(outputLock) {
                synchronized(stateLock) {
                    if (activeSession === this
                        && currentLifecycle == CompositionSessionLifecycle.Preparing
                        && ownedEngine == null
                        && !cancellationRequested.get()
                    ) {
                        ownedEngine = engine
                        currentLifecycle = CompositionSessionLifecycle.Active
                        currentState = currentState.copy(busy = false, error = null)
                        revision++
                        attachedState = currentState
                        accepted = true
                    }
                }
                attachedState?.let(::notifyState)
            }
            if (!accepted) closeQuietly(engine)
            return accepted
        }

        override fun processKey(key: String): CompositionState {
            if (key.isEmpty()) return currentState()
            return runEngineOperation { engine -> engine.processKey(key) }
        }

        override fun backspace(): CompositionState = runEngineOperation { engine -> engine.backspace() }

        override fun selectCandidate(index: Int): CompositionState {
            // Read the candidate only inside runEngineOperation's serialized critical section.
            // Reading it here and then calling the engine later would let a concurrent key update
            // replace the candidate list in between, causing text from the old list to be
            // committed while the engine selected an item from the new list.
            return runEngineOperation(
                shouldRun = { state -> state.candidates.getOrNull(index) != null },
                // Mozc (and other native engines) can return an exact Result for a selection.
                // Prefer it over the old strip text, which may be a display value or may have
                // changed while the engine performed conversion. The old candidate remains the
                // compatibility fallback for simple engines that do not expose Result.
                commitTextFor = { before, after ->
                    after.resultText?.takeIf(String::isNotEmpty) ?: before.candidates[index]
                },
            ) { engine -> engine.selectCandidate(index) }
        }

        override fun previousCandidatePage(): CompositionState = pageCandidates(
            canPage = { state -> state.canPageBackward },
            operation = { engine -> engine.previousCandidatePage() },
        )

        override fun nextCandidatePage(): CompositionState = pageCandidates(
            canPage = { state -> state.canPageForward },
            operation = { engine -> engine.nextCandidatePage() },
        )

        override fun switchInputMode(inputMode: String): CompositionState {
            if (inputMode.isBlank()) return currentState()
            return runEngineOperation(forceFinishComposing = true) { engine ->
                (engine as? ModeSwitchingCompositionEngine)?.switchInputMode(inputMode)
                    ?: currentState()
            }
        }

        override fun commit(): CompositionState {
            // The exact preedit snapshot that the engine commits is also the only text that may
            // be sent to InputConnection when no native Result is returned. Keeping this read
            // inside the serialized operation prevents a late commit from publishing an older
            // preedit after another key arrived.
            return runEngineOperation(
                commitTextFor = { before, after ->
                    after.resultText?.takeIf(String::isNotEmpty)
                        ?: before.preedit.takeIf(String::isNotEmpty)
                },
            ) { engine -> engine.commit() }
        }

        override fun reset(): CompositionState = runEngineOperation(forceFinishComposing = true) { engine ->
            // Reset is contractual cancellation, so a buggy/native engine cannot leave stale text
            // or candidates rendered in the next editor.
            engine.reset().copy(preedit = "", candidates = emptyList(), resultText = null, busy = false)
        }

        override fun fail(reason: String): CompositionState {
            require(reason.isNotBlank()) { "Composition failure reason must not be blank" }
            return failSession(reason)
        }

        override fun cancel() {
            terminate(CompositionSessionLifecycle.Cancelled)
        }

        override fun close() {
            terminate(CompositionSessionLifecycle.Closed)
        }

        private fun pageCandidates(
            canPage: (CompositionState) -> Boolean,
            operation: (PagedCompositionEngine) -> CompositionState,
        ): CompositionState = runEngineOperation(
            shouldRun = { state -> canPage(state) },
        ) { engine ->
            (engine as? PagedCompositionEngine)?.let(operation) ?: currentState()
        }

        /** Must be called while [stateLock] is held. */
        fun terminateLocked(nextLifecycle: CompositionSessionLifecycle): Termination? {
            // Cancellation may have been requested before this method could acquire stateLock
            // (for example while native code is evaluating). Lifecycle ownership is still open
            // in that case and must be closed exactly once.
            if (!isOpenLocked()) return null
            cancellationRequested.set(true)
            val previousState = currentState
            val engine = ownedEngine
            ownedEngine = null
            currentLifecycle = nextLifecycle
            currentState = CompositionState(
                inputMode = previousState.inputMode,
                error = (nextLifecycle as? CompositionSessionLifecycle.Failed)?.reason,
            )
            revision++
            if (activeSession === this) activeSession = null
            return Termination(
                engine = engine,
                needsFinishComposing = previousState.preedit.isNotEmpty(),
                state = currentState,
            )
        }

        fun terminate(nextLifecycle: CompositionSessionLifecycle): Boolean {
            requestCancellation()
            var termination: Termination? = null
            synchronized(outputLock) {
                synchronized(stateLock) {
                    termination = terminateLocked(nextLifecycle)
                }
                termination?.let { stopped ->
                    if (stopped.needsFinishComposing) safeFinishComposing()
                    notifyState(stopped.state)
                }
            }
            termination?.engine?.let(::closeQuietly)
            return termination != null
        }

        private fun runEngineOperation(
            shouldRun: (CompositionState) -> Boolean = { true },
            commitTextFor: (before: CompositionState, after: CompositionState) -> String? =
                { _, after -> after.resultText?.takeIf(String::isNotEmpty) },
            forceFinishComposing: Boolean = false,
            operation: (CompositionEngine) -> CompositionState,
        ): CompositionState {
            val before: CompositionState
            val after: CompositionState
            val expectedRevision: Long
            val commitText: String?
            try {
                synchronized(stateLock) {
                    if (!isActiveLocked()) return currentState
                    before = currentState
                    if (!shouldRun(before)) return currentState
                    after = operation(requireNotNull(ownedEngine))
                    commitText = commitTextFor(before, after)
                    // The engine owns no UI. The snapshot is the only data that can be rendered by
                    // the target/candidate sink, and all effects are applied after it returns.
                    currentState = after
                    revision++
                    expectedRevision = revision
                }
            } catch (error: Throwable) {
                if (error is ThreadDeath || error is VirtualMachineError) throw error
                return failSession("engine-failure")
            }
            return emitOperation(
                before = before,
                after = after,
                expectedRevision = expectedRevision,
                commitText = commitText,
                forceFinishComposing = forceFinishComposing,
            )
        }

        private fun emitOperation(
            before: CompositionState,
            after: CompositionState,
            expectedRevision: Long,
            commitText: String?,
            forceFinishComposing: Boolean,
        ): CompositionState {
            synchronized(outputLock) {
                synchronized(stateLock) {
                    if (!isActiveLocked() || revision != expectedRevision) return currentState
                }
                val inputApplied = runCatching {
                    applyInputOperation(before, after, commitText, forceFinishComposing)
                }.getOrDefault(false)
                if (!inputApplied) return failSession("input-connection-unavailable")
                // InputConnection is external code. A disconnect, subtype switch, or IME close
                // can happen re-entrantly while applying the text above. Do not let that old
                // operation publish a stale candidate/preedit snapshot after the replacement
                // session has already announced itself.
                synchronized(stateLock) {
                    if (!isActiveLocked() || revision != expectedRevision) return currentState
                }
                notifyState(after)
                return after
            }
        }

        private fun applyInputOperation(
            before: CompositionState,
            after: CompositionState,
            commitText: String?,
            forceFinishComposing: Boolean,
        ): Boolean {
            if (commitText != null && !inputTarget.commitText(commitText)) return false
            return when {
                after.preedit.isNotEmpty() -> inputTarget.setComposingText(after.preedit)
                forceFinishComposing -> inputTarget.finishComposingText()
                // commitText() already ends Android composition, so do not issue a redundant
                // finish call after candidate/preedit commit.
                commitText == null && before.preedit.isNotEmpty() -> inputTarget.finishComposingText()
                else -> true
            }
        }

        private fun failSession(reason: String): CompositionState {
            requestCancellation()
            var termination: Termination? = null
            synchronized(outputLock) {
                synchronized(stateLock) {
                    if (isOpenLocked()) {
                        termination = terminateLocked(CompositionSessionLifecycle.Failed(reason))
                    }
                }
                termination?.let { stopped ->
                    // A failure can occur after an editor disconnected. Finishing is best-effort
                    // and must not hide the generic engine/input failure state from the candidate UI.
                    if (stopped.needsFinishComposing) safeFinishComposing()
                    notifyState(stopped.state)
                }
            }
            termination?.engine?.let(::closeQuietly)
            return termination?.state ?: currentState()
        }

        fun currentState(): CompositionState = synchronized(stateLock) { currentState }

        private fun isActiveLocked(): Boolean = activeSession === this
            && currentLifecycle == CompositionSessionLifecycle.Active
            && ownedEngine != null
            && !cancellationRequested.get()

        private fun isOpenLocked(): Boolean = activeSession === this
            && (currentLifecycle == CompositionSessionLifecycle.Preparing
                || currentLifecycle == CompositionSessionLifecycle.Active)

        /** Safe from any thread and deliberately does not wait for [stateLock]. */
        fun requestCancellation() {
            cancellationRequested.set(true)
        }
    }

    private fun safeFinishComposing() {
        runCatching { inputTarget.finishComposingText() }
    }

    private fun notifyState(state: CompositionState) {
        // Rendering an optional candidate strip cannot be allowed to tear down the IME because a
        // third-party/native engine is transitioning state. The next state replaces this snapshot.
        runCatching { stateSink.onCompositionStateChanged(state) }
    }

    private fun closeQuietly(engine: CompositionEngine) {
        runCatching { engine.close() }
    }

    private companion object {
        // Android's stable TRIM_MEMORY_RUNNING_LOW level. Keep the controller Android-service
        // agnostic so it remains unit-testable without a live InputMethodService.
        const val RUNNING_LOW_MEMORY_LEVEL = 10
    }
}
