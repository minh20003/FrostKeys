// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.io.File

/**
 * The narrow native-session surface used by [MozcCompositionEngine].
 *
 * It is internal so production code cannot inject arbitrary serialized Mozc commands. Unit tests
 * use a deterministic fake instead of loading a native library.
 */
internal interface MozcNativeSession : AutoCloseable {
    fun evaluate(command: ByteArray): ByteArray

    fun dataVersion(): String

    override fun close()
}

private fun throwIfMozcCancelled(cancellation: EngineBundleCancellation) {
    if (cancellation.isCancellationRequested()) throw EngineBundleInstallationCancelledException()
}

/**
 * Handle-owning binding for the FrostKeys-owned `libfrostkeys_mozc.so` bridge.
 *
 * The native source at `tools/cjk/mozc_bridge/frostkeys_mozc_jni.cc` deliberately accepts only
 * `SEND_KEY` and `SEND_COMMAND`, and it overwrites the session id. This class adds the Java-side
 * half of the lifetime guarantee: every evaluation and close is serialized, and [handle] is reset
 * to zero under [lifecycleLock] *before* `nativeClose` is called. Therefore a raw native pointer
 * can never be used by an evaluation after close has begun.
 */
internal class MozcNativeBridge : MozcNativeSession {
    private val lifecycleLock = Any()
    private var handle = 0L
    private var creationAttempted = false
    private var closed = false

    /**
     * Loads the owned JNI library and creates one data-backed Mozc session.
     *
     * Callers must provide the already verified app-private profile directory and `mozc.data`
     * path. Cancellation is checked before loading, after loading, and after native creation so a
     * late subtype switch cannot leave a native engine resident.
     */
    fun open(
        profileDirectory: String,
        dataFilePath: String,
        cancellation: EngineBundleCancellation,
    ) {
        require(profileDirectory.isNotBlank()) { "Mozc profile directory must not be blank" }
        require(dataFilePath.isNotBlank()) { "Mozc data file path must not be blank" }
        synchronized(lifecycleLock) {
            check(!creationAttempted && !closed) { "Mozc native bridge cannot be reopened" }
            creationAttempted = true
            throwIfMozcCancelled(cancellation)
            ensureLibraryLoaded()
            throwIfMozcCancelled(cancellation)
            val createdHandle = nativeCreate(profileDirectory, dataFilePath)
            check(createdHandle != 0L) { "Mozc native bridge returned an empty handle" }
            if (cancellation.isCancellationRequested()) {
                // `createdHandle` has not been published, so close that exact handle directly.
                // Do not set [handle] first: an observer must never regard a cancelled session as
                // open even for a moment.
                closed = true
                nativeClose(createdHandle)
                throw EngineBundleInstallationCancelledException()
            }
            handle = createdHandle
        }
    }

    override fun evaluate(command: ByteArray): ByteArray {
        require(command.isNotEmpty() && command.size <= MozcWireCodec.MAX_COMMAND_BYTES) {
            "Mozc command exceeds the allowed size"
        }
        return synchronized(lifecycleLock) {
            val activeHandle = checkNotNullHandle()
            nativeEvalCommand(activeHandle, command)
        }
    }

    override fun dataVersion(): String = synchronized(lifecycleLock) {
        nativeDataVersion(checkNotNullHandle())
    }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            // Clear first under the same lock used by evaluate()/dataVersion(). Any later caller
            // sees an invalid handle rather than racing native deletion.
            val activeHandle = handle
            handle = 0L
            if (activeHandle != 0L) nativeClose(activeHandle)
        }
    }

    private fun checkNotNullHandle(): Long = handle.takeIf { it != 0L && !closed }
        ?: throw IllegalStateException("Mozc engine is closed")

    private external fun nativeCreate(profileDirectory: String, dataFilePath: String): Long

    private external fun nativeEvalCommand(handle: Long, request: ByteArray): ByteArray

    private external fun nativeDataVersion(handle: Long): String

    private external fun nativeClose(handle: Long)

    private companion object {
        private val libraryLoadLock = Any()

        @Volatile
        private var libraryLoaded = false

        private fun ensureLibraryLoaded() {
            synchronized(libraryLoadLock) {
                if (libraryLoaded) return
                System.loadLibrary("frostkeys_mozc")
                libraryLoaded = true
            }
        }
    }
}

/**
 * Offline Mozc composition adapter.
 *
 * This remains a pure [CompositionEngine]: [MozcImeRuntime] creates it on the engine bundle's
 * background executor and hands it to [CompositionSessionController]. The controller owns editor
 * effects; this adapter owns only the verified native session and the candidate-id mapping needed
 * to turn a visible index into `SessionCommand.SELECT_CANDIDATE`.
 */
class MozcCompositionEngine internal constructor(
    private val nativeSession: MozcNativeSession,
    inputMode: String,
    private val cancellation: EngineBundleCancellation,
    /** Version reported by the verified data-backed native session, never a network value. */
    val dataVersion: String,
) : PagedCompositionEngine, ModeSwitchingCompositionEngine {
    private val lifecycleLock = Any()
    private var closed = false
    private var inputMode = inputMode
    private var currentCandidates: List<MozcWireCodec.Candidate> = emptyList()
    private var currentState = CompositionState(inputMode = inputMode)

    /** The exact latest `commands.Result.value`, if the preceding native response had one. */
    val latestResultText: String?
        get() = synchronized(lifecycleLock) { currentState.resultText }

    /** Immutable snapshot useful to a subtype owner before it forwards a key to the controller. */
    val state: CompositionState
        get() = synchronized(lifecycleLock) { currentState }

    init {
        require(inputMode.isNotBlank()) { "Mozc input mode must not be blank" }
        require(dataVersion.isNotBlank()) { "Mozc data version must not be blank" }
    }

    override fun processKey(key: String): CompositionState {
        if (key.isEmpty()) return state
        return transact(MozcWireCodec.sendKey(key))
    }

    override fun backspace(): CompositionState = transact(MozcWireCodec.backspace())

    override fun selectCandidate(index: Int): CompositionState = synchronized(lifecycleLock) {
        terminalStateIfNeededLocked()?.let { return@synchronized it }
        val candidate = currentCandidates.getOrNull(index) ?: return@synchronized currentState
        // CandidateWindow.id is optional in Mozc's schema. A visible item without an id cannot
        // safely be selected: never guess from its list position because positions change on pages.
        val candidateId = candidate.id ?: return@synchronized currentState
        transactLocked(MozcWireCodec.selectCandidate(candidateId))
    }

    override fun commit(): CompositionState = transact(MozcWireCodec.submit())

    override fun reset(): CompositionState = transact(
        command = MozcWireCodec.revert(),
        forceEmptyComposition = true,
    )

    /** Requests the prior verified candidate page when a candidate window is currently visible. */
    override fun previousCandidatePage(): CompositionState = page(MozcWireCodec.previousPage())

    /** Requests the next verified candidate page when a candidate window is currently visible. */
    override fun nextCandidatePage(): CompositionState = page(MozcWireCodec.nextPage())

    /**
     * Clears the current conversion then activates a reviewed Mozc mode.
     *
     * The clear happens before the mode command so an old preedit can never be committed under a
     * different script. [MozcInputMode] is deliberately decoded from the stable identifier here,
     * instead of accepting an arbitrary native enum or a translated UI label.
     */
    override fun switchInputMode(inputMode: String): CompositionState = synchronized(lifecycleLock) {
        terminalStateIfNeededLocked()?.let { return@synchronized it }
        val targetMode = MozcInputMode.fromStableId(inputMode) ?: return@synchronized currentState
        if (targetMode.stableId == this.inputMode) return@synchronized currentState
        val cleared = transactLocked(MozcWireCodec.revert(), forceEmptyComposition = true)
        if (cleared.error != null || closed) return@synchronized cleared
        this.inputMode = targetMode.stableId
        transactLocked(MozcWireCodec.activate(targetMode), forceEmptyComposition = true)
    }

    override fun close() {
        synchronized(lifecycleLock) {
            closeLocked(error = "mozc-closed")
        }
    }

    private fun page(command: ByteArray): CompositionState = synchronized(lifecycleLock) {
        terminalStateIfNeededLocked()?.let { return@synchronized it }
        if (currentCandidates.isEmpty()) return@synchronized currentState
        transactLocked(command)
    }

    private fun transact(command: ByteArray, forceEmptyComposition: Boolean = false): CompositionState =
        synchronized(lifecycleLock) {
            terminalStateIfNeededLocked()?.let { return@synchronized it }
            transactLocked(command, forceEmptyComposition)
        }

    /** Called only while [lifecycleLock] is held. */
    private fun transactLocked(command: ByteArray, forceEmptyComposition: Boolean = false): CompositionState {
        try {
            val response = nativeSession.evaluate(command)
            // A cancellation may happen while native code evaluates. Drop that response rather
            // than publishing preedit/candidates for a subtype that has already gone away.
            if (cancellation.isCancellationRequested()) return closeLocked(error = "mozc-cancelled")
            val decoded = MozcWireCodec.decodeOutput(response)
            currentCandidates = decoded.candidates
            currentState = CompositionState(
                preedit = if (forceEmptyComposition) "" else decoded.preedit,
                candidates = if (forceEmptyComposition) emptyList() else decoded.candidates.map { it.value },
                page = if (forceEmptyComposition) 0 else decoded.page,
                canPageBackward = !forceEmptyComposition && decoded.canPageBackward,
                canPageForward = !forceEmptyComposition && decoded.canPageForward,
                preeditCursor = if (forceEmptyComposition) null else decoded.preeditCursor,
                inputMode = inputMode,
                resultText = if (forceEmptyComposition) null else decoded.resultText,
            )
            if (forceEmptyComposition) currentCandidates = emptyList()
            return currentState
        } catch (error: Throwable) {
            if (error is ThreadDeath || error is VirtualMachineError) throw error
            closeLocked(error = "mozc-engine-failure")
            throw error
        }
    }

    /** Returns a terminal state if the engine was closed or cancellation was requested. */
    private fun terminalStateIfNeededLocked(): CompositionState? {
        if (closed) return currentState
        if (cancellation.isCancellationRequested()) return closeLocked(error = "mozc-cancelled")
        return null
    }

    /** Called only while [lifecycleLock] is held. Native close is intentionally idempotent. */
    private fun closeLocked(error: String): CompositionState {
        if (!closed) {
            closed = true
            currentCandidates = emptyList()
            runCatching { nativeSession.close() }
        }
        currentState = CompositionState(inputMode = inputMode, error = error)
        return currentState
    }

    /** Starts conversion explicitly; a fresh Mozc session otherwise may remain in DIRECT mode. */
    private fun initializeHiragana() {
        val initialized = transact(MozcWireCodec.activateHiragana())
        when (initialized.error) {
            null -> Unit
            "mozc-cancelled" -> throw EngineBundleInstallationCancelledException()
            else -> throw IllegalStateException("Mozc initial mode could not be activated")
        }
    }

    companion object {
        const val DEFAULT_INPUT_MODE = "hiragana"

        /**
         * Creates a native-backed engine from an atomically verified bundle.
         *
         * This method deliberately does not know an APK asset path or register a subtype. The
         * caller supplies the extracted `mozc.data` and app-private profile directory after
         * [EngineBundleInstaller] has checked their manifest and hashes.
         */
        fun create(
            profileDirectory: File,
            dataFile: File,
            inputMode: String = DEFAULT_INPUT_MODE,
            cancellation: EngineBundleCancellation = EngineBundleCancellation.NONE,
        ): MozcCompositionEngine {
            require(inputMode.isNotBlank()) { "Mozc input mode must not be blank" }
            throwIfMozcCancelled(cancellation)
            require(dataFile.isFile) { "Verified Mozc data file is missing" }
            if (!profileDirectory.isDirectory && !profileDirectory.mkdirs()) {
                throw IllegalStateException("Could not create Mozc profile directory")
            }
            throwIfMozcCancelled(cancellation)

            val bridge = MozcNativeBridge()
            try {
                bridge.open(profileDirectory.canonicalPath, dataFile.canonicalPath, cancellation)
                throwIfMozcCancelled(cancellation)
                val dataVersion = bridge.dataVersion()
                require(dataVersion.isNotBlank()) { "Mozc data version is missing" }
                throwIfMozcCancelled(cancellation)
                return MozcCompositionEngine(
                    nativeSession = bridge,
                    inputMode = inputMode,
                    cancellation = cancellation,
                    dataVersion = dataVersion,
                ).also { engine -> engine.initializeHiragana() }
            } catch (error: Throwable) {
                bridge.close()
                throw error
            }
        }

        /** Test-only construction path that never loads JNI or exposes a raw command API. */
        internal fun createForTesting(
            nativeSession: MozcNativeSession,
            inputMode: String = DEFAULT_INPUT_MODE,
            cancellation: EngineBundleCancellation = EngineBundleCancellation.NONE,
            dataVersion: String = "test-data",
        ): MozcCompositionEngine {
            if (cancellation.isCancellationRequested()) {
                nativeSession.close()
                throw EngineBundleInstallationCancelledException()
            }
            return MozcCompositionEngine(nativeSession, inputMode, cancellation, dataVersion).also { engine ->
                engine.initializeHiragana()
            }
        }

    }
}
