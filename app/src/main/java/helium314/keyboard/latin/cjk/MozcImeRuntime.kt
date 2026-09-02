// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import android.content.Context
import android.os.Handler
import helium314.keyboard.event.Event
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * IME-thread owner for the optional offline Japanese Mozc session.
 *
 * The verified bundle is unpacked and native-created on [engineExecutor], while every editor call
 * and every [CompositionSession] operation stays on the IME thread. This class deliberately has
 * a narrow dedicated candidate-strip sink. It never converts Mozc candidates into
 * [helium314.keyboard.latin.SuggestedWords], so Japanese conversion cannot overwrite or be
 * selected through LatinIME's ordinary prediction strip.
 */
class MozcImeRuntime(
    context: Context,
    private val inputConnection: RichInputConnection,
    private val engineExecutor: Executor,
    private val imeHandler: Handler,
    private val candidateUiSink: MozcCandidateUiSink = MozcCandidateUiSink.NONE,
) : AutoCloseable {
    private sealed interface BufferedOperation {
        data class Key(val text: String) : BufferedOperation

        data object Backspace : BufferedOperation

        data object Reset : BufferedOperation

        /** Used only while bundle activation is pending, preserving text order across a boundary. */
        data class CommitAndAppend(val text: String) : BufferedOperation
    }

    private val appContext = context.applicationContext
    private val bundleManager = EngineBundleManager(appContext, engineExecutor)
    private val pendingOperations = ArrayDeque<BufferedOperation>()

    // All of these fields are confined to LatinIME's main/input thread. The manager invokes its
    // listener on a worker and immediately posts back through [imeHandler] before touching them.
    private var controller: CompositionSessionController? = null
    private var session: CompositionSession? = null
    private var activation: EngineBundleActivation? = null
    private var failedForCurrentInput = false
    private var closed = false
    private var selectedInputMode = MozcInputMode.HIRAGANA
    private val candidatePresentations = MozcCandidatePresentationStore()

    init {
        check(BuildConfig.FROSTKEYS_MOZC_BUNDLE_ENABLED) {
            "Mozc runtime must not be created without a verified APK bundle"
        }
        bundleManager.register(
            EngineBundleSpec(
                id = MozcJapaneseSubtype.BUNDLE_ID,
                manifestAssetPath = MozcJapaneseSubtype.MANIFEST_ASSET_PATH,
                engineFactory = EngineBundleFactory { dataDirectory, cancellation ->
                    val dataFile = File(dataDirectory, "data/mozc.data")
                    val profileDirectory = File(appContext.filesDir, "cjk-profile/mozc")
                    MozcCompositionEngine.create(
                        profileDirectory = profileDirectory,
                        dataFile = dataFile,
                        cancellation = cancellation,
                    )
                },
            ),
        )
    }

    /**
     * Begins lazy bundle activation for the current editor.
     *
     * Call this only after LatinIME has started its [RichInputConnection]. Calling it repeatedly
     * for the same input field is harmless; no engine is created until the first valid Japanese
     * subtype session needs one.
     */
    fun start() {
        if (closed || failedForCurrentInput || session != null || !inputConnection.isConnected) return

        selectedInputMode = MozcInputMode.HIRAGANA

        val newController = CompositionSessionController(
            inputTarget = RichInputConnectionCompositionTarget(inputConnection),
            stateSink = CompositionStateSink(::publishCandidatePresentation),
        )
        val newSession = newController.begin(selectedInputMode.stableId)
        controller = newController
        session = newSession
        val newActivation = bundleManager.activate(
            MozcJapaneseSubtype.BUNDLE_ID,
            object : EngineBundleActivationListener {
                override fun onEngineReady(activation: EngineBundleActivation) {
                    if (!imeHandler.post(Runnable {
                            attachReadyEngine(newSession, activation)
                        })) {
                        // The service has already lost its main loop. The manager still owns the
                        // result, so cancellation closes it instead of leaking a native session.
                        activation.cancel()
                    }
                }

                override fun onEngineFailure(
                        activation: EngineBundleActivation,
                        error: Throwable,
                    ) {
                    if (!imeHandler.post(Runnable {
                            handleActivationFailure(newSession, activation, error)
                        })) {
                        activation.cancel()
                    }
                }
            },
        )
        activation = newActivation
    }

    /** True only when this input field can begin a fresh lazy Mozc activation. */
    fun canStart(): Boolean = !closed && !failedForCurrentInput && session == null

    /** Whether this input field already owns a preparing or active Mozc session. */
    fun isStarted(): Boolean = session != null

    /**
     * True while this runtime owns the dedicated external row, including a stable failure state.
     *
     * Generic Latin suggestions must not replace that row asynchronously just because a native
     * bundle failed after the loading presentation was already visible.
     */
    fun ownsCandidatePresentation(): Boolean = candidatePresentations.current != null

    /** Current dedicated-strip state, primarily useful when LatinIME recreates its view tree. */
    fun currentCandidatePresentation(): MozcCandidatePresentation? = candidatePresentations.current

    /**
     * Gives a replacement input-view tree a fresh action generation for the same visual state.
     *
     * A click may already be queued on the detached strip when Android recreates the input view.
     * Reusing its generation would let that old view select a candidate in the new tree, so this
     * method is deliberately called before attaching the replacement strip.
     */
    fun refreshCandidatePresentationForNewView(): MozcCandidatePresentation? {
        if (closed) return null
        return candidatePresentations.refreshForNewView()
    }

    /** Selects a candidate only when [generation] still belongs to the current native window. */
    fun selectCandidate(index: Int, generation: Long): Boolean {
        val activeSession = sessionForGeneration(generation) ?: return false
        if (activeSession.lifecycle != CompositionSessionLifecycle.Active
            || activeSession.state.candidates.getOrNull(index) == null
        ) return false
        activeSession.selectCandidate(index)
        return true
    }

    /** Requests a previous native candidate page only from the current strip generation. */
    fun previousCandidatePage(generation: Long): Boolean {
        val activeSession = sessionForGeneration(generation) ?: return false
        if (activeSession.lifecycle != CompositionSessionLifecycle.Active
            || !activeSession.state.canPageBackward
        ) return false
        activeSession.previousCandidatePage()
        return true
    }

    /** Requests a next native candidate page only from the current strip generation. */
    fun nextCandidatePage(generation: Long): Boolean {
        val activeSession = sessionForGeneration(generation) ?: return false
        if (activeSession.lifecycle != CompositionSessionLifecycle.Active
            || !activeSession.state.canPageForward
        ) return false
        activeSession.nextCandidatePage()
        return true
    }

    /** Cancels the current preedit without committing it. A preparation queue is dropped safely. */
    fun cancelComposition(generation: Long): Boolean {
        val activeSession = sessionForGeneration(generation) ?: return false
        return when (activeSession.lifecycle) {
            CompositionSessionLifecycle.Preparing -> {
                // There is no native preedit to reset yet. Cancelling must nevertheless cancel
                // extraction/factory work; merely clearing the queue would still create a native
                // engine after the user explicitly dismissed the loading row.
                stop()
                true
            }

            CompositionSessionLifecycle.Active -> {
                if (!activeSession.state.hasComposition) return false
                activeSession.reset()
                true
            }

            else -> false
        }
    }

    /** Switches among the fixed Japanese strip modes without exposing raw native commands. */
    fun selectInputMode(inputMode: MozcInputMode, generation: Long): Boolean {
        val activeSession = sessionForGeneration(generation) ?: return false
        return when (activeSession.lifecycle) {
            CompositionSessionLifecycle.Preparing -> {
                selectedInputMode = inputMode
                publishCandidatePresentation(activeSession.state.copy(inputMode = inputMode.stableId))
                true
            }

            CompositionSessionLifecycle.Active -> {
                val state = activeSession.switchInputMode(inputMode.stableId)
                if (state.error == null && state.inputMode == inputMode.stableId) {
                    selectedInputMode = inputMode
                    true
                } else {
                    false
                }
            }

            else -> false
        }
    }

    /**
     * Consumes a soft or decoded hardware event before LatinIME hands it to InputLogic.
     *
     * Returning false deliberately leaves the event to the normal path after a live Mozc
     * composition was committed. This preserves editor actions, punctuation rules, and all
     * non-Japanese subtypes without duplicating InputLogic.
     */
    fun handleEvent(event: Event): Boolean {
        if (closed || failedForCurrentInput || !event.isHandled) return false
        val activeSession = session ?: return false
        if (activeSession.isCancelled) return false

        when (event.keyCode) {
            KeyCode.DELETE -> return handleBackspace(activeSession)
            KeyCode.ESCAPE -> return handleEscape(activeSession)
        }

        val text = event.textToCommit?.toString().orEmpty()
        if (text.isEmpty()) {
            // Cursor/mode/toolbar keys are owned by LatinIME. If a conversion is visible, commit
            // first, then let the original key continue to its normal implementation.
            if (activeSession.lifecycle == CompositionSessionLifecycle.Active
                && activeSession.state.hasComposition
            ) {
                activeSession.commit()
            }
            return false
        }
        return handleText(text, activeSession)
    }

    /** Same routing for multi-code-point soft keys that bypass [Event]'s code-point path. */
    fun handleTextInput(text: String?): Boolean {
        if (closed || failedForCurrentInput || text.isNullOrEmpty()) return false
        val activeSession = session ?: return false
        if (activeSession.isCancelled) return false
        return handleText(text, activeSession)
    }

    /** Cancels native/background work and clears any Android composing span without committing it. */
    fun stop() {
        activation?.cancel()
        activation = null
        session?.cancel()
        session = null
        controller?.close()
        controller = null
        pendingOperations.clear()
        failedForCurrentInput = false
        selectedInputMode = MozcInputMode.HIRAGANA
        hideCandidatePresentation()
        // Covers a native engine which became ready immediately before its worker callback could
        // post the main-thread hand-off.
        bundleManager.releaseActiveEngine()
    }

    /** Releases a CJK session under meaningful memory pressure; a later input restart may reopen it. */
    fun onTrimMemory(level: Int) {
        if (level < RUNNING_LOW_MEMORY_LEVEL) return
        stop()
        bundleManager.onTrimMemory(level)
    }

    override fun close() {
        if (closed) return
        stop()
        closed = true
        bundleManager.close()
    }

    private fun attachReadyEngine(
        expectedSession: CompositionSession,
        readyActivation: EngineBundleActivation,
    ) {
        val engine = bundleManager.takeActiveEngine(readyActivation) ?: return
        if (closed || activation !== readyActivation || session !== expectedSession) {
            runCatching { engine.close() }
            return
        }
        activation = null
        if (!expectedSession.attach(engine)) return // attach closes stale ownership itself.
        if (selectedInputMode != MozcInputMode.HIRAGANA) {
            val state = expectedSession.switchInputMode(selectedInputMode.stableId)
            if (state.error != null) {
                failedForCurrentInput = true
                session = null
                controller?.close()
                controller = null
                return
            }
        }
        replayPendingOperations(expectedSession)
    }

    private fun handleActivationFailure(
        expectedSession: CompositionSession,
        failedActivation: EngineBundleActivation,
        error: Throwable,
    ) {
        if (closed || activation !== failedActivation || session !== expectedSession) return
        activation = null
        pendingOperations.clear()
        failedForCurrentInput = true
        // Production Log keeps the exception class only, which is enough to diagnose a broken
        // verified bundle without persisting user text, filesystem paths or native error details.
        Log.w(TAG, "Offline Mozc activation failed", error)
        // Keep a dedicated, non-secret failure snapshot on screen. Hiding it here let the next
        // asynchronous Latin dictionary update overwrite the error row, leaving the user with no
        // explanation that Japanese conversion did not start.
        expectedSession.fail("mozc-bundle-unavailable")
        session = null
        controller?.close()
        controller = null
    }

    /** Main-thread-only stale-action gate for the dedicated candidate strip. */
    private fun sessionForGeneration(generation: Long): CompositionSession? {
        if (closed || !candidatePresentations.accepts(generation)) return null
        return session?.takeUnless { it.isCancelled }
    }

    /** Called by the controller on the IME thread after an editor effect has been applied. */
    private fun publishCandidatePresentation(state: CompositionState) {
        if (closed) return
        val presentation = candidatePresentations.publish(state)
        candidateUiSink.show(presentation)
    }

    private fun hideCandidatePresentation() {
        candidatePresentations.clear()
        candidateUiSink.hide()
    }

    private fun handleText(text: String, activeSession: CompositionSession): Boolean {
        if (MozcInputRouting.shouldRouteTextToMozc(
                text = text,
                currentState = activeSession.state,
                hasBufferedComposition = pendingOperations.isNotEmpty(),
            )
        ) {
            return dispatch(activeSession, BufferedOperation.Key(text))
        }

        // The standard path should still own whitespace, editor-action enter, emoji and arbitrary
        // pasted text. Commit any live Japanese preedit first. During first-run extraction there
        // is no engine yet, so queue the boundary plus its text to preserve ordering.
        return commitBeforePassThrough(activeSession, text)
    }

    private fun handleBackspace(activeSession: CompositionSession): Boolean = when (activeSession.lifecycle) {
        CompositionSessionLifecycle.Preparing -> {
            if (pendingOperations.isEmpty()) false else dispatch(activeSession, BufferedOperation.Backspace)
        }

        CompositionSessionLifecycle.Active -> {
            if (!activeSession.state.hasComposition) false
            else dispatch(activeSession, BufferedOperation.Backspace)
        }

        else -> false
    }

    private fun handleEscape(activeSession: CompositionSession): Boolean = when (activeSession.lifecycle) {
        CompositionSessionLifecycle.Preparing -> {
            if (pendingOperations.isEmpty()) false else dispatch(activeSession, BufferedOperation.Reset)
        }

        CompositionSessionLifecycle.Active -> {
            if (!activeSession.state.hasComposition) false
            else dispatch(activeSession, BufferedOperation.Reset)
        }

        else -> false
    }

    /**
     * Returns true only when a pending activation consumed [textToAppend] itself.
     *
     * Once active, the engine commits its exact native result and the original Event is allowed to
     * continue through InputLogic, so send/next editor actions and regular separator handling are
     * retained. During preparation we cannot safely replay the original Event later; direct cached
     * append is the only way to keep ``romaji + separator`` ordering correct.
     */
    private fun commitBeforePassThrough(
        activeSession: CompositionSession,
        textToAppend: String,
    ): Boolean = when (activeSession.lifecycle) {
        CompositionSessionLifecycle.Preparing -> {
            if (pendingOperations.isEmpty()) false
            else dispatch(activeSession, BufferedOperation.CommitAndAppend(textToAppend))
        }

        CompositionSessionLifecycle.Active -> {
            if (activeSession.state.hasComposition) activeSession.commit()
            false
        }

        else -> false
    }

    private fun dispatch(activeSession: CompositionSession, operation: BufferedOperation): Boolean = when (
        activeSession.lifecycle
    ) {
        CompositionSessionLifecycle.Preparing -> {
            if (!enqueue(operation)) {
                // A damaged/very slow bundle must not turn an IME into an unbounded key buffer.
                // Drop the optional session and immediately return to normal LatinIME input.
                pendingOperations.clear()
                failedForCurrentInput = true
                activeSession.cancel()
                session = null
                controller?.close()
                controller = null
                activation?.cancel()
                activation = null
                hideCandidatePresentation()
                false
            } else {
                true
            }
        }

        CompositionSessionLifecycle.Active -> {
            applyOperation(activeSession, operation)
            true
        }

        else -> false
    }

    private fun replayPendingOperations(activeSession: CompositionSession) {
        while (pendingOperations.isNotEmpty()
            && !closed
            && session === activeSession
            && activeSession.lifecycle == CompositionSessionLifecycle.Active
        ) {
            applyOperation(activeSession, pendingOperations.removeFirst())
        }
        pendingOperations.clear()
    }

    private fun applyOperation(activeSession: CompositionSession, operation: BufferedOperation) {
        when (operation) {
            is BufferedOperation.Key -> activeSession.processKey(operation.text)
            BufferedOperation.Backspace -> activeSession.backspace()
            BufferedOperation.Reset -> activeSession.reset()
            is BufferedOperation.CommitAndAppend -> {
                activeSession.commit()
                // The controller's commit cleared Android composition first. Use the same cached
                // RichInputConnection rather than a raw framework connection for the deferred
                // separator/paste fragment.
                if (activeSession.lifecycle == CompositionSessionLifecycle.Active
                    && inputConnection.isConnected
                ) {
                    inputConnection.commitText(operation.text, 1)
                }
            }
        }
    }

    private fun enqueue(operation: BufferedOperation): Boolean {
        val addedCharacters = when (operation) {
            is BufferedOperation.Key -> operation.text.length
            is BufferedOperation.CommitAndAppend -> operation.text.length
            else -> 0
        }
        val queuedCharacters = pendingOperations.sumOf {
            when (it) {
                is BufferedOperation.Key -> it.text.length
                is BufferedOperation.CommitAndAppend -> it.text.length
                else -> 0
            }
        }
        if (pendingOperations.size >= MAX_PENDING_OPERATIONS
            || queuedCharacters + addedCharacters > MAX_PENDING_CHARACTERS
        ) {
            return false
        }
        pendingOperations.addLast(operation)
        return true
    }

    private companion object {
        const val TAG = "MozcImeRuntime"
        const val RUNNING_LOW_MEMORY_LEVEL = 10
        const val MAX_PENDING_OPERATIONS = 128
        const val MAX_PENDING_CHARACTERS = 512
    }
}
