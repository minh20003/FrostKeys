// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.io.File

/** Stable, non-localized output identifiers accepted by [RimeCompositionEngine]. */
enum class RimePinyinOutputMode(val stableId: String) {
    SIMPLIFIED("pinyin-simplified"),
    TRADITIONAL("pinyin-traditional"),
    ;

    companion object {
        fun fromStableId(value: String): RimePinyinOutputMode? = entries.firstOrNull { it.stableId == value }
    }
}

/**
 * Minimal native surface used by [RimeCompositionEngine].
 *
 * There is deliberately no raw "command" method. Each method corresponds to exactly one
 * reviewed C++ bridge operation, which makes a future UI unable to turn arbitrary translated text
 * or a setting value into a Rime configuration command.
 */
internal interface RimeNativeSession : AutoCloseable {
    fun processPinyinKey(keyCode: Int): ByteArray

    fun backspace(): ByteArray

    fun selectCandidateOnCurrentPage(index: Int): ByteArray

    fun changePage(backward: Boolean): ByteArray

    fun commit(): ByteArray

    fun reset(): ByteArray

    fun setSimplifiedOutput(simplified: Boolean): ByteArray

    fun version(): String

    override fun close()
}

private fun throwIfRimeCancelled(cancellation: EngineBundleCancellation) {
    if (cancellation.isCancellationRequested()) throw EngineBundleInstallationCancelledException()
}

/**
 * Handle-owning binding for `libfrostkeys_rime.so`.
 *
 * The native bridge serializes librime's process-global setup/finalize lifecycle. This Kotlin half
 * serializes every handle operation and clears [handle] before calling native close, so a stale
 * IME callback cannot use a freed native session.
 */
internal class RimeNativeBridge : RimeNativeSession {
    private val lifecycleLock = Any()
    private var handle = 0L
    private var creationAttempted = false
    private var closed = false

    fun open(
        sharedDataDirectory: String,
        userDataDirectory: String,
        cancellation: EngineBundleCancellation,
    ) {
        require(sharedDataDirectory.isNotBlank()) { "Rime shared data directory must not be blank" }
        require(userDataDirectory.isNotBlank()) { "Rime user data directory must not be blank" }
        synchronized(lifecycleLock) {
            check(!creationAttempted && !closed) { "Rime native bridge cannot be reopened" }
            creationAttempted = true
            throwIfRimeCancelled(cancellation)
            ensureLibraryLoaded()
            throwIfRimeCancelled(cancellation)
            val createdHandle = nativeCreate(sharedDataDirectory, userDataDirectory)
            check(createdHandle != 0L) { "Rime native bridge returned an empty handle" }
            if (cancellation.isCancellationRequested()) {
                closed = true
                nativeClose(createdHandle)
                throw EngineBundleInstallationCancelledException()
            }
            handle = createdHandle
        }
    }

    override fun processPinyinKey(keyCode: Int): ByteArray = synchronized(lifecycleLock) {
        require(keyCode in 'a'.code..'z'.code || keyCode == '\''.code) { "Invalid Rime Pinyin key" }
        nativeProcessPinyinKey(requireOpenHandle(), keyCode)
    }

    override fun backspace(): ByteArray = synchronized(lifecycleLock) { nativeBackspace(requireOpenHandle()) }

    override fun selectCandidateOnCurrentPage(index: Int): ByteArray = synchronized(lifecycleLock) {
        require(index in 0 until RimeWireCodec.MAX_CANDIDATES) { "Rime candidate index is out of range" }
        nativeSelectCandidate(requireOpenHandle(), index)
    }

    override fun changePage(backward: Boolean): ByteArray = synchronized(lifecycleLock) {
        nativeChangePage(requireOpenHandle(), backward)
    }

    override fun commit(): ByteArray = synchronized(lifecycleLock) { nativeCommit(requireOpenHandle()) }

    override fun reset(): ByteArray = synchronized(lifecycleLock) { nativeReset(requireOpenHandle()) }

    override fun setSimplifiedOutput(simplified: Boolean): ByteArray = synchronized(lifecycleLock) {
        nativeSetSimplifiedOutput(requireOpenHandle(), simplified)
    }

    override fun version(): String = synchronized(lifecycleLock) { nativeVersion(requireOpenHandle()) }

    override fun close() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            val activeHandle = handle
            handle = 0L
            if (activeHandle != 0L) nativeClose(activeHandle)
        }
    }

    private fun requireOpenHandle(): Long = handle.takeIf { it != 0L && !closed }
        ?: throw IllegalStateException("Rime engine is closed")

    private external fun nativeCreate(sharedDataDirectory: String, userDataDirectory: String): Long

    private external fun nativeProcessPinyinKey(handle: Long, keyCode: Int): ByteArray

    private external fun nativeBackspace(handle: Long): ByteArray

    private external fun nativeSelectCandidate(handle: Long, index: Int): ByteArray

    private external fun nativeChangePage(handle: Long, backward: Boolean): ByteArray

    private external fun nativeCommit(handle: Long): ByteArray

    private external fun nativeReset(handle: Long): ByteArray

    private external fun nativeSetSimplifiedOutput(handle: Long, simplified: Boolean): ByteArray

    private external fun nativeVersion(handle: Long): String

    private external fun nativeClose(handle: Long)

    private companion object {
        private val libraryLoadLock = Any()

        @Volatile
        private var libraryLoaded = false

        private fun ensureLibraryLoaded() {
            synchronized(libraryLoadLock) {
                if (libraryLoaded) return
                // The final verified APK will load the matching librime.so first through its
                // normal arm64-v8a JNI directory. This bridge itself contains no APK path or
                // fallback download; an absent payload is a hard feature-unavailable error.
                System.loadLibrary("frostkeys_rime")
                libraryLoaded = true
            }
        }
    }
}

/**
 * Offline Rime Pinyin adapter. It owns only native engine state, not an Android subtype or view.
 *
 * The Chinese Rime subtype constructs this object on the optional engine's background executor
 * only after its bridge, OpenCC `.ocd2` data, and APK hash manifest have passed verification.
 * [RimeImeRuntime] attaches it to [CompositionSessionController]; no startup path initializes it.
 */
class RimeCompositionEngine internal constructor(
    private val nativeSession: RimeNativeSession,
    private val cancellation: EngineBundleCancellation,
    initialMode: RimePinyinOutputMode,
    /** Version reported by the native librime build attached to this exact session. */
    val dataVersion: String,
) : PagedCompositionEngine, ModeSwitchingCompositionEngine {
    private val lifecycleLock = Any()
    private var closed = false
    private var mode = initialMode
    private var currentState = CompositionState(inputMode = initialMode.stableId)

    val state: CompositionState
        get() = synchronized(lifecycleLock) { currentState }

    override fun processKey(key: String): CompositionState {
        val keyCode = RimeWireCodec.normalizedPinyinKey(key) ?: return state
        return transact { nativeSession.processPinyinKey(keyCode) }
    }

    override fun backspace(): CompositionState = transact { nativeSession.backspace() }

    override fun selectCandidate(index: Int): CompositionState = synchronized(lifecycleLock) {
        terminalStateIfNeededLocked()?.let { return@synchronized it }
        if (index !in currentState.candidates.indices) return@synchronized currentState
        transactLocked { nativeSession.selectCandidateOnCurrentPage(index) }
    }

    override fun commit(): CompositionState = transact { nativeSession.commit() }

    override fun reset(): CompositionState = transact(forceEmptyComposition = true) { nativeSession.reset() }

    override fun previousCandidatePage(): CompositionState = synchronized(lifecycleLock) {
        terminalStateIfNeededLocked()?.let { return@synchronized it }
        if (!currentState.canPageBackward) return@synchronized currentState
        transactLocked { nativeSession.changePage(backward = true) }
    }

    override fun nextCandidatePage(): CompositionState = synchronized(lifecycleLock) {
        terminalStateIfNeededLocked()?.let { return@synchronized it }
        if (!currentState.canPageForward) return@synchronized currentState
        transactLocked { nativeSession.changePage(backward = false) }
    }

    override fun switchInputMode(inputMode: String): CompositionState = synchronized(lifecycleLock) {
        terminalStateIfNeededLocked()?.let { return@synchronized it }
        val target = RimePinyinOutputMode.fromStableId(inputMode) ?: return@synchronized currentState
        if (target == mode) return@synchronized currentState
        mode = target
        transactLocked(forceEmptyComposition = true) { nativeSession.setSimplifiedOutput(target == RimePinyinOutputMode.SIMPLIFIED) }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            closeLocked("rime-closed")
        }
    }

    private fun transact(
        forceEmptyComposition: Boolean = false,
        action: () -> ByteArray,
    ): CompositionState = synchronized(lifecycleLock) {
        terminalStateIfNeededLocked()?.let { return@synchronized it }
        transactLocked(forceEmptyComposition, action)
    }

    private fun transactLocked(
        forceEmptyComposition: Boolean = false,
        action: () -> ByteArray,
    ): CompositionState {
        try {
            val decoded = RimeWireCodec.decodeState(action())
            if (cancellation.isCancellationRequested()) return closeLocked("rime-cancelled")
            currentState = CompositionState(
                preedit = if (forceEmptyComposition) "" else decoded.preedit,
                candidates = if (forceEmptyComposition) emptyList() else decoded.candidates,
                page = if (forceEmptyComposition) 0 else decoded.page,
                canPageBackward = !forceEmptyComposition && decoded.canPageBackward,
                canPageForward = !forceEmptyComposition && decoded.canPageForward,
                inputMode = mode.stableId,
                resultText = if (forceEmptyComposition) null else decoded.resultText,
            )
            return currentState
        } catch (error: Throwable) {
            if (error is ThreadDeath || error is VirtualMachineError) throw error
            closeLocked("rime-engine-failure")
            throw error
        }
    }

    private fun terminalStateIfNeededLocked(): CompositionState? {
        if (closed) return currentState
        if (cancellation.isCancellationRequested()) return closeLocked("rime-cancelled")
        return null
    }

    private fun closeLocked(error: String): CompositionState {
        if (!closed) {
            closed = true
            runCatching { nativeSession.close() }
        }
        currentState = CompositionState(inputMode = mode.stableId, error = error)
        return currentState
    }

    companion object {
        const val DEFAULT_INPUT_MODE: String = "pinyin-simplified"
        private const val SHARED_DATA_DIRECTORY = "shared"

        /**
         * Creates a data-backed Rime session only after all required offline assets were installed
         * atomically. There is no network fallback and no loose data directory accepted here.
         */
        fun create(
            bundleDirectory: File,
            profileDirectory: File,
            inputMode: String = DEFAULT_INPUT_MODE,
            cancellation: EngineBundleCancellation = EngineBundleCancellation.NONE,
        ): RimeCompositionEngine {
            val requestedMode = requireNotNull(RimePinyinOutputMode.fromStableId(inputMode)) {
                "Unsupported Rime input mode"
            }
            throwIfRimeCancelled(cancellation)
            val sharedDataDirectory = File(bundleDirectory, SHARED_DATA_DIRECTORY)
            require(sharedDataDirectory.isDirectory) { "Verified Rime shared data directory is missing" }
            require(File(sharedDataDirectory, "luna_pinyin.schema.yaml").isFile) {
                "Verified Rime Pinyin schema is missing"
            }
            // OpenCC configurations resolve relative to the Rime shared-data directory. Keeping
            // the compiled .ocd2 files beside their JSON configurations matches librime's own
            // resolver and avoids an untracked secondary search path at runtime.
            require(File(sharedDataDirectory, "t2s.json").isFile && File(sharedDataDirectory, "t2tw.json").isFile) {
                "Verified Rime OpenCC configuration is missing"
            }
            require(sharedDataDirectory.walkTopDown().any { it.isFile && it.extension == "ocd2" }) {
                "Verified Rime OpenCC dictionaries are missing"
            }
            if (!profileDirectory.isDirectory && !profileDirectory.mkdirs()) {
                throw IllegalStateException("Could not create the Rime profile directory")
            }
            throwIfRimeCancelled(cancellation)

            val bridge = RimeNativeBridge()
            try {
                bridge.open(sharedDataDirectory.canonicalPath, profileDirectory.canonicalPath, cancellation)
                throwIfRimeCancelled(cancellation)
                val dataVersion = bridge.version()
                require(dataVersion.isNotBlank()) { "Rime data version is missing" }
                // nativeCreate always sets Rime's explicit Simplified default. Keep the Kotlin
                // state aligned with that fact, then issue the reviewed option change when the
                // caller requested Traditional. Constructing the engine directly in Traditional
                // mode would make switchInputMode see a no-op and silently leave native output
                // Simplified.
                return RimeCompositionEngine(
                    bridge,
                    cancellation,
                    RimePinyinOutputMode.SIMPLIFIED,
                    dataVersion,
                ).also { engine ->
                    if (requestedMode != RimePinyinOutputMode.SIMPLIFIED) {
                        val switched = engine.switchInputMode(requestedMode.stableId)
                        check(switched.error == null && switched.inputMode == requestedMode.stableId) {
                            "Could not activate the requested Rime output mode"
                        }
                    }
                }
            } catch (error: Throwable) {
                bridge.close()
                throw error
            }
        }

        /** Test-only construction path; production creation always requires a verified asset tree. */
        internal fun createForTesting(
            nativeSession: RimeNativeSession,
            inputMode: RimePinyinOutputMode = RimePinyinOutputMode.SIMPLIFIED,
            cancellation: EngineBundleCancellation = EngineBundleCancellation.NONE,
            dataVersion: String = "rime-test-data",
        ): RimeCompositionEngine = RimeCompositionEngine(nativeSession, cancellation, inputMode, dataVersion)
    }
}
