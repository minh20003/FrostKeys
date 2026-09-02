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
 * IME-thread owner for the optional offline Rime Pinyin session.
 *
 * Rime extraction/native creation happens only after the Chinese subtype receives input. Editor
 * calls and composition state remain on LatinIME's thread. It deliberately renders through its
 * own candidate sink so generic Latin suggestions can never select a Rime candidate.
 */
class RimeImeRuntime(
    context: Context,
    private val inputConnection: RichInputConnection,
    private val engineExecutor: Executor,
    private val imeHandler: Handler,
    private val candidateUiSink: RimeCandidateUiSink = RimeCandidateUiSink.NONE,
) : AutoCloseable {
    private sealed interface BufferedOperation {
        data class Key(val text: String) : BufferedOperation
        data object Backspace : BufferedOperation
        data object Reset : BufferedOperation
        data object SelectFirstCandidate : BufferedOperation
        data class CommitAndAppend(val text: String) : BufferedOperation
    }

    private val appContext = context.applicationContext
    private val bundleManager = EngineBundleManager(appContext, engineExecutor)
    private val pendingOperations = ArrayDeque<BufferedOperation>()
    private var controller: CompositionSessionController? = null
    private var session: CompositionSession? = null
    private var activation: EngineBundleActivation? = null
    private var failedForCurrentInput = false
    private var closed = false
    private var selectedOutputMode = RimePinyinOutputMode.SIMPLIFIED
    private val candidatePresentations = RimeCandidatePresentationStore()

    init {
        check(BuildConfig.FROSTKEYS_RIME_BUNDLE_ENABLED) {
            "Rime runtime must not be created without a verified APK bundle"
        }
        bundleManager.register(
            EngineBundleSpec(
                id = RimeChineseSubtype.BUNDLE_ID,
                manifestAssetPath = RimeChineseSubtype.MANIFEST_ASSET_PATH,
                engineFactory = EngineBundleFactory { directory, cancellation ->
                    RimeCompositionEngine.create(
                        bundleDirectory = directory,
                        profileDirectory = File(appContext.filesDir, "cjk-profile/rime"),
                        inputMode = selectedOutputMode.stableId,
                        cancellation = cancellation,
                    )
                },
            ),
        )
    }

    fun start() {
        if (closed || failedForCurrentInput || session != null || !inputConnection.isConnected) return
        selectedOutputMode = RimePinyinOutputMode.SIMPLIFIED
        val newController = CompositionSessionController(
            inputTarget = RichInputConnectionCompositionTarget(inputConnection),
            stateSink = CompositionStateSink(::publishCandidatePresentation),
        )
        val newSession = newController.begin(selectedOutputMode.stableId)
        controller = newController
        session = newSession
        activation = bundleManager.activate(
            RimeChineseSubtype.BUNDLE_ID,
            object : EngineBundleActivationListener {
                override fun onEngineReady(activation: EngineBundleActivation) {
                    if (!imeHandler.post(Runnable { attachReadyEngine(newSession, activation) })) activation.cancel()
                }

                override fun onEngineFailure(activation: EngineBundleActivation, error: Throwable) {
                    if (!imeHandler.post(Runnable {
                            handleActivationFailure(newSession, activation, error)
                        })) activation.cancel()
                }
            },
        )
    }

    fun canStart(): Boolean = !closed && !failedForCurrentInput && session == null

    fun isStarted(): Boolean = session != null

    fun ownsCandidatePresentation(): Boolean = candidatePresentations.current != null

    fun currentCandidatePresentation(): RimeCandidatePresentation? = candidatePresentations.current

    fun refreshCandidatePresentationForNewView(): RimeCandidatePresentation? =
        if (closed) null else candidatePresentations.refreshForNewView()

    fun selectCandidate(index: Int, generation: Long): Boolean {
        val active = sessionForGeneration(generation) ?: return false
        if (active.lifecycle != CompositionSessionLifecycle.Active || active.state.candidates.getOrNull(index) == null) {
            return false
        }
        active.selectCandidate(index)
        return true
    }

    fun previousCandidatePage(generation: Long): Boolean {
        val active = sessionForGeneration(generation) ?: return false
        if (active.lifecycle != CompositionSessionLifecycle.Active || !active.state.canPageBackward) return false
        active.previousCandidatePage()
        return true
    }

    fun nextCandidatePage(generation: Long): Boolean {
        val active = sessionForGeneration(generation) ?: return false
        if (active.lifecycle != CompositionSessionLifecycle.Active || !active.state.canPageForward) return false
        active.nextCandidatePage()
        return true
    }

    fun cancelComposition(generation: Long): Boolean {
        val active = sessionForGeneration(generation) ?: return false
        return when (active.lifecycle) {
            CompositionSessionLifecycle.Preparing -> {
                stop()
                true
            }

            CompositionSessionLifecycle.Active -> {
                if (!active.state.hasComposition) false else {
                    active.reset()
                    true
                }
            }

            else -> false
        }
    }

    fun selectOutputMode(mode: RimePinyinOutputMode, generation: Long): Boolean {
        val active = sessionForGeneration(generation) ?: return false
        return when (active.lifecycle) {
            CompositionSessionLifecycle.Preparing -> {
                selectedOutputMode = mode
                publishCandidatePresentation(active.state.copy(inputMode = mode.stableId))
                true
            }

            CompositionSessionLifecycle.Active -> {
                val state = active.switchInputMode(mode.stableId)
                if (state.error == null && state.inputMode == mode.stableId) {
                    selectedOutputMode = mode
                    true
                } else {
                    false
                }
            }

            else -> false
        }
    }

    fun handleEvent(event: Event): Boolean {
        if (closed || failedForCurrentInput || !event.isHandled) return false
        val active = session ?: return false
        if (active.isCancelled) return false
        when (event.keyCode) {
            KeyCode.DELETE -> return handleBackspace(active)
            KeyCode.ESCAPE -> return handleEscape(active)
        }
        val text = event.textToCommit?.toString().orEmpty()
        if (text.isEmpty()) {
            if (active.lifecycle == CompositionSessionLifecycle.Active && active.state.hasComposition) active.commit()
            return false
        }
        return handleText(text, active)
    }

    fun handleTextInput(text: String?): Boolean {
        if (closed || failedForCurrentInput || text.isNullOrEmpty()) return false
        val active = session ?: return false
        if (active.isCancelled) return false
        return handleText(text, active)
    }

    fun stop() {
        activation?.cancel()
        activation = null
        session?.cancel()
        session = null
        controller?.close()
        controller = null
        pendingOperations.clear()
        failedForCurrentInput = false
        selectedOutputMode = RimePinyinOutputMode.SIMPLIFIED
        hideCandidatePresentation()
        bundleManager.releaseActiveEngine()
    }

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

    private fun attachReadyEngine(expected: CompositionSession, ready: EngineBundleActivation) {
        val engine = bundleManager.takeActiveEngine(ready) ?: return
        if (closed || activation !== ready || session !== expected) {
            runCatching { engine.close() }
            return
        }
        activation = null
        if (!expected.attach(engine)) return
        if (selectedOutputMode != RimePinyinOutputMode.SIMPLIFIED) {
            val state = expected.switchInputMode(selectedOutputMode.stableId)
            if (state.error != null) {
                failedForCurrentInput = true
                session = null
                controller?.close()
                controller = null
                return
            }
        }
        replayPendingOperations(expected)
    }

    private fun handleActivationFailure(
        expected: CompositionSession,
        failed: EngineBundleActivation,
        error: Throwable,
    ) {
        if (closed || activation !== failed || session !== expected) return
        activation = null
        pendingOperations.clear()
        failedForCurrentInput = true
        // Keep a release-safe diagnostic: Log intentionally records only the throwable class in
        // non-debug builds, never a private file path, editor text or native-library message.
        Log.w(TAG, "Offline Rime activation failed", error)
        expected.fail("rime-bundle-unavailable")
        session = null
        controller?.close()
        controller = null
    }

    private fun sessionForGeneration(generation: Long): CompositionSession? {
        if (closed || !candidatePresentations.accepts(generation)) return null
        return session?.takeUnless { it.isCancelled }
    }

    private fun publishCandidatePresentation(state: CompositionState) {
        if (!closed) candidateUiSink.show(candidatePresentations.publish(state))
    }

    private fun hideCandidatePresentation() {
        candidatePresentations.clear()
        candidateUiSink.hide()
    }

    private fun handleText(text: String, active: CompositionSession): Boolean {
        if (text == " " && RimeInputRouting.shouldSelectFirstOnSpace(active.state, pendingOperations.isNotEmpty())) {
            return dispatch(active, BufferedOperation.SelectFirstCandidate)
        }
        if (RimeInputRouting.isPinyinText(text)) return dispatch(active, BufferedOperation.Key(text))
        return commitBeforePassThrough(active, text)
    }

    private fun handleBackspace(active: CompositionSession): Boolean = when (active.lifecycle) {
        CompositionSessionLifecycle.Preparing -> if (pendingOperations.isEmpty()) false
        else dispatch(active, BufferedOperation.Backspace)
        CompositionSessionLifecycle.Active -> if (!active.state.hasComposition) false
        else dispatch(active, BufferedOperation.Backspace)
        else -> false
    }

    private fun handleEscape(active: CompositionSession): Boolean = when (active.lifecycle) {
        CompositionSessionLifecycle.Preparing -> if (pendingOperations.isEmpty()) false
        else dispatch(active, BufferedOperation.Reset)
        CompositionSessionLifecycle.Active -> if (!active.state.hasComposition) false
        else dispatch(active, BufferedOperation.Reset)
        else -> false
    }

    private fun commitBeforePassThrough(active: CompositionSession, append: String): Boolean = when (active.lifecycle) {
        CompositionSessionLifecycle.Preparing -> if (pendingOperations.isEmpty()) false
        else dispatch(active, BufferedOperation.CommitAndAppend(append))
        CompositionSessionLifecycle.Active -> {
            if (active.state.hasComposition) commitCurrent(active)
            false
        }
        else -> false
    }

    private fun dispatch(active: CompositionSession, operation: BufferedOperation): Boolean = when (active.lifecycle) {
        CompositionSessionLifecycle.Preparing -> {
            if (enqueue(operation)) true else {
                pendingOperations.clear()
                failedForCurrentInput = true
                active.cancel()
                session = null
                controller?.close()
                controller = null
                activation?.cancel()
                activation = null
                hideCandidatePresentation()
                false
            }
        }
        CompositionSessionLifecycle.Active -> {
            applyOperation(active, operation)
            true
        }
        else -> false
    }

    private fun replayPendingOperations(active: CompositionSession) {
        while (pendingOperations.isNotEmpty() && !closed && session === active
            && active.lifecycle == CompositionSessionLifecycle.Active
        ) {
            applyOperation(active, pendingOperations.removeFirst())
        }
        pendingOperations.clear()
    }

    private fun applyOperation(active: CompositionSession, operation: BufferedOperation) {
        when (operation) {
            is BufferedOperation.Key -> active.processKey(operation.text)
            BufferedOperation.Backspace -> active.backspace()
            BufferedOperation.Reset -> active.reset()
            BufferedOperation.SelectFirstCandidate -> commitCurrent(active)
            is BufferedOperation.CommitAndAppend -> {
                commitCurrent(active)
                if (active.lifecycle == CompositionSessionLifecycle.Active && inputConnection.isConnected) {
                    inputConnection.commitText(operation.text, 1)
                }
            }
        }
    }

    private fun commitCurrent(active: CompositionSession) {
        if (active.state.candidates.isNotEmpty()) active.selectCandidate(0) else active.commit()
    }

    private fun enqueue(operation: BufferedOperation): Boolean {
        val characters = when (operation) {
            is BufferedOperation.Key -> operation.text.length
            is BufferedOperation.CommitAndAppend -> operation.text.length
            else -> 0
        }
        val queued = pendingOperations.sumOf {
            when (it) {
                is BufferedOperation.Key -> it.text.length
                is BufferedOperation.CommitAndAppend -> it.text.length
                else -> 0
            }
        }
        if (pendingOperations.size >= MAX_PENDING_OPERATIONS || queued + characters > MAX_PENDING_CHARACTERS) return false
        pendingOperations.addLast(operation)
        return true
    }

    private companion object {
        const val TAG = "RimeImeRuntime"
        const val RUNNING_LOW_MEMORY_LEVEL = 10
        const val MAX_PENDING_OPERATIONS = 128
        const val MAX_PENDING_CHARACTERS = 512
    }
}
