// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import android.content.Context
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Installs a bundle on the manager's background executor.
 *
 * Implementations must honor [cancellation] and must only return an atomically verified directory.
 * The default implementation is [EngineBundleInstaller.install]; this explicit boundary prevents a
 * future native bridge from accidentally doing first-run extraction on the IME main thread.
 */
fun interface EngineBundleInstallTask {
    fun install(
        context: Context,
        manifestAssetPath: String,
        cancellation: EngineBundleCancellation,
    ): File
}

/**
 * Creates a native-backed composition engine from an already verified bundle directory.
 *
 * The factory must check [cancellation] before loading a native library and while creating a
 * session. This keeps a late subtype switch or IME teardown from turning completed asset copying
 * into an unnecessary Rime/Mozc initialization. A future bridge is responsible for closing any
 * partially created native state before it throws.
 */
fun interface EngineBundleFactory {
    fun create(
        dataDirectory: File,
        cancellation: EngineBundleCancellation,
    ): CompositionEngine
}

/**
 * Describes an optional, APK-bundled offline composition data set.
 *
 * No bundle is registered by default. A future engine integration must explicitly register a
 * [manifestAssetPath] and an [engineFactory] after the corresponding verified native/data bundle
 * is actually shipped in the APK.
 */
class EngineBundleSpec(
    val id: String,
    manifestAssetPath: String,
    val engineFactory: EngineBundleFactory,
) {
    val manifestAssetPath: String = manifestAssetPath.replace('\\', '/')

    init {
        require(id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid engine bundle id" }
        require(EngineBundleInstaller.isSafeAssetPath(this.manifestAssetPath)) {
            "Invalid engine bundle manifest path"
        }
    }
}

/** State of [EngineBundleManager]'s active/pending optional engine. */
sealed class EngineBundleLifecycleState {
    data object Idle : EngineBundleLifecycleState()

    data class Loading(val bundleId: String) : EngineBundleLifecycleState()

    data class Ready(val bundleId: String) : EngineBundleLifecycleState()

    data class Failed(val bundleId: String, val error: Throwable) : EngineBundleLifecycleState()

    data object Closed : EngineBundleLifecycleState()
}

/** A cancellable request returned by [EngineBundleManager.activate]. */
interface EngineBundleActivation : AutoCloseable {
    val isCancelled: Boolean

    /**
     * Cancels this pending activation. Cancellation never closes an already active engine.
     * It is safe to invoke from any thread and more than once.
     */
    fun cancel()

    override fun close() = cancel()
}

/**
 * Notification for an activation whose engine must be transferred to an IME-owned session.
 *
 * Callbacks run on [EngineBundleManager]'s background executor.  They receive no raw engine:
 * [takeActiveEngine] is deliberately the only hand-off API, and it transfers ownership on the
 * IME thread after a lifecycle owner has checked that its composition session is still current.
 * Until then the manager keeps the engine and a cancellation/trim/close can release it safely.
 */
interface EngineBundleActivationListener {
    fun onEngineReady(activation: EngineBundleActivation)

    fun onEngineFailure(activation: EngineBundleActivation, error: Throwable)
}

/**
 * Lazily installs and owns one optional offline composition engine at a time.
 *
 * The manager does no work at construction or registration time. [activate] schedules installation
 * on [executor]; it is therefore the only point at which [EngineBundleInstaller] can unpack assets.
 * A later activation, [EngineBundleActivation.cancel], memory pressure, or [close] cancels a stale
 * request before it can create an engine. Native integration remains intentionally outside this
 * class: the factory is only supplied by a feature that has a bundled, verified implementation.
 */
class EngineBundleManager(
    private val context: Context,
    private val executor: Executor,
    private val installBundle: EngineBundleInstallTask =
        EngineBundleInstallTask { installContext, manifestAssetPath, cancellation ->
            EngineBundleInstaller.install(installContext, manifestAssetPath, cancellation)
        },
) : AutoCloseable {
    private data class ActiveEngine(
        val bundleId: String,
        val engine: CompositionEngine,
        /** Non-null only while an IME callback has not claimed this engine yet. */
        val activation: Activation?,
    )

    private val lock = Any()
    private val bundles = linkedMapOf<String, EngineBundleSpec>()
    private var activeEngine: ActiveEngine? = null
    private var pendingActivation: Activation? = null
    private var isClosed = false

    @Volatile
    var state: EngineBundleLifecycleState = EngineBundleLifecycleState.Idle
        private set

    /** Registers a data bundle without installing or loading it. */
    fun register(spec: EngineBundleSpec) {
        synchronized(lock) {
            check(!isClosed) { "EngineBundleManager is closed" }
            check(spec.id !in bundles) { "Engine bundle '${spec.id}' is already registered" }
            bundles[spec.id] = spec
        }
    }

    /**
     * Returns a manager-owned engine, or null while loading or after a listener has claimed it.
     *
     * IME code that supplied [EngineBundleActivationListener] must use [takeActiveEngine] rather
     * than holding this reference.  That prevents the manager and a composition session from both
     * believing they own the same native engine.
     */
    fun currentEngine(): CompositionEngine? = synchronized(lock) { activeEngine?.engine }

    /** Returns a stable snapshot of all registered optional bundle ids. */
    fun registeredBundleIds(): Set<String> = synchronized(lock) { bundles.keys.toSet() }

    /**
     * Starts a lazy activation. The returned handle can prevent queued or stale work from creating
     * an engine. Installing remains on [executor]; callers must not use a main-thread executor.
     */
    fun activate(bundleId: String): EngineBundleActivation = activateInternal(bundleId, null)

    /**
     * Starts a lazy activation whose result is later handed to a composition session.
     *
     * The listener is notified from [executor]. It must post work to the IME thread and call
     * [takeActiveEngine] there.  If a subtype switch, hidden window, or memory pressure happens
     * before that hand-off, the activation is cancelled and the manager closes the native engine
     * instead of leaking it in an abandoned callback queue.
     */
    fun activate(
        bundleId: String,
        listener: EngineBundleActivationListener,
    ): EngineBundleActivation = activateInternal(bundleId, listener)

    private fun activateInternal(
        bundleId: String,
        listener: EngineBundleActivationListener?,
    ): EngineBundleActivation {
        val activation: Activation
        synchronized(lock) {
            check(!isClosed) { "EngineBundleManager is closed" }
            val spec = bundles[bundleId] ?: error("Unknown engine bundle '$bundleId'")
            pendingActivation?.markCancelled()
            activation = Activation(spec, listener)
            pendingActivation = activation
            state = EngineBundleLifecycleState.Loading(bundleId)
        }
        try {
            executor.execute { activateOnExecutor(activation) }
        } catch (error: Throwable) {
            failActivation(activation, error)
        }
        return activation
    }

    /**
     * Atomically transfers an engine produced for [activation] to the caller.
     *
     * Only listener-backed activations may use this method. The caller owns and must close the
     * returned engine (normally by handing it to [CompositionSession.attach]); all other paths
     * keep manager ownership and remain compatible with the original [activate] API.
     */
    fun takeActiveEngine(activation: EngineBundleActivation): CompositionEngine? = synchronized(lock) {
        val internalActivation = activation as? Activation ?: return@synchronized null
        val active = activeEngine
            ?: return@synchronized null
        if (isClosed
            || internalActivation.isCancelled
            || internalActivation.listener == null
            || active.activation !== internalActivation
        ) {
            return@synchronized null
        }
        activeEngine = null
        state = if (pendingActivation != null) {
            EngineBundleLifecycleState.Loading(pendingActivation!!.spec.id)
        } else {
            EngineBundleLifecycleState.Idle
        }
        active.engine
    }

    /**
     * Releases the active engine and cancels queued activation without unregistering bundle specs.
     * This makes a later explicit [activate] safe after the IME is hidden or memory is constrained.
     */
    fun releaseActiveEngine() {
        val engineToClose: CompositionEngine?
        synchronized(lock) {
            pendingActivation?.markCancelled()
            pendingActivation = null
            engineToClose = activeEngine?.engine
            activeEngine = null
            if (!isClosed) state = EngineBundleLifecycleState.Idle
        }
        closeQuietly(engineToClose)
    }

    /**
     * Releases the optional engine under meaningful memory pressure.
     *
     * `10` is Android's stable `TRIM_MEMORY_RUNNING_LOW` threshold. The named framework constant
     * is deprecated by the current compile SDK even though Android 12+ devices still report these
     * trim levels to IME services.
     */
    fun onTrimMemory(level: Int) {
        if (level >= RUNNING_LOW_MEMORY_LEVEL) releaseActiveEngine()
    }

    /** Permanently releases all engine resources. A closed manager cannot be reused. */
    override fun close() {
        val engineToClose: CompositionEngine?
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            pendingActivation?.markCancelled()
            pendingActivation = null
            engineToClose = activeEngine?.engine
            activeEngine = null
            state = EngineBundleLifecycleState.Closed
        }
        closeQuietly(engineToClose)
    }

    private fun activateOnExecutor(activation: Activation) {
        if (!isActivationCurrent(activation)) return
        val dataDirectory = try {
            installBundle.install(
                context,
                activation.spec.manifestAssetPath,
                EngineBundleCancellation { activation.isCancelled },
            )
        } catch (error: Throwable) {
            failActivation(activation, error)
            return
        }
        if (!isActivationCurrent(activation)) return

        val createdEngine = try {
            activation.spec.engineFactory.create(
                dataDirectory,
                EngineBundleCancellation { !isActivationCurrent(activation) },
            )
        } catch (error: Throwable) {
            failActivation(activation, error)
            return
        }

        val previousEngine: CompositionEngine?
        val keepCreatedEngine: Boolean
        synchronized(lock) {
            keepCreatedEngine = !isClosed && !activation.isCancelled && pendingActivation === activation
            if (keepCreatedEngine) {
                previousEngine = activeEngine?.engine
                activeEngine = ActiveEngine(
                    bundleId = activation.spec.id,
                    engine = createdEngine,
                    activation = activation.takeIf { it.listener != null },
                )
                pendingActivation = null
                state = EngineBundleLifecycleState.Ready(activation.spec.id)
            } else {
                previousEngine = null
            }
        }
        if (keepCreatedEngine) {
            closeQuietly(previousEngine)
            notifyReady(activation)
        } else {
            closeQuietly(createdEngine)
        }
    }

    /**
     * Checking only [EngineBundleActivation.isCancelled] leaves a race with a newer activation:
     * both bundles could finish installing, and the stale one could still call its native factory.
     * Keep the identity check under the same lock that owns [pendingActivation] so only the newest
     * request can cross the installation-to-native-engine boundary.
     */
    private fun isActivationCurrent(activation: Activation): Boolean = synchronized(lock) {
        !isClosed && !activation.isCancelled && pendingActivation === activation
    }

    private fun failActivation(activation: Activation, error: Throwable) {
        var shouldNotify = false
        synchronized(lock) {
            if (isClosed || activation.isCancelled || pendingActivation !== activation) return
            pendingActivation = null
            state = EngineBundleLifecycleState.Failed(activation.spec.id, error)
            shouldNotify = activation.listener != null
        }
        if (shouldNotify) notifyFailure(activation, error)
    }

    private fun cancelActivation(activation: Activation) {
        var engineToClose: CompositionEngine? = null
        synchronized(lock) {
            if (pendingActivation === activation) {
                pendingActivation = null
            }
            // A no-listener activation keeps the historical behavior: cancel only prevents
            // pending work and never tears down its already active engine. A listener-backed
            // activation has not handed the engine to a session yet, so it is safe and necessary
            // to close it here.
            val active = activeEngine
            if (activation.listener != null && active?.activation === activation) {
                activeEngine = null
                engineToClose = active.engine
            }
            state = activeEngine?.let { EngineBundleLifecycleState.Ready(it.bundleId) }
                ?: pendingActivation?.let { EngineBundleLifecycleState.Loading(it.spec.id) }
                ?: EngineBundleLifecycleState.Idle
        }
        closeQuietly(engineToClose)
    }

    private fun notifyReady(activation: Activation) {
        runCatching { activation.listener?.onEngineReady(activation) }
    }

    private fun notifyFailure(activation: Activation, error: Throwable) {
        runCatching { activation.listener?.onEngineFailure(activation, error) }
    }

    private fun closeQuietly(engine: CompositionEngine?) {
        try {
            engine?.close()
        } catch (_: Throwable) {
            // Closing an optional engine must never destabilize the IME lifecycle.
        }
    }

    private inner class Activation(
        val spec: EngineBundleSpec,
        val listener: EngineBundleActivationListener?,
    ) : EngineBundleActivation {
        private val cancelled = AtomicBoolean(false)

        override val isCancelled: Boolean
            get() = cancelled.get()

        override fun cancel() {
            if (!cancelled.compareAndSet(false, true)) return
            cancelActivation(this)
        }

        fun markCancelled() {
            cancelled.set(true)
        }
    }

    private companion object {
        const val RUNNING_LOW_MEMORY_LEVEL = 10
    }
}
