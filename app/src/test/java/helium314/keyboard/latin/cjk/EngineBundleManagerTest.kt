// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EngineBundleManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun compositionStateContainsCompositionContract() {
        val state = CompositionState(
            preedit = "nih",
            candidates = listOf("你", "呢"),
            page = 2,
            inputMode = "pinyin",
            busy = true,
            error = "dictionary-not-ready",
        )

        assertEquals("nih", state.preedit)
        assertEquals(listOf("你", "呢"), state.candidates)
        assertEquals(2, state.page)
        assertEquals("pinyin", state.inputMode)
        assertTrue(state.busy)
        assertEquals("dictionary-not-ready", state.error)
        assertTrue(state.hasComposition)
        assertFailsWith<IllegalArgumentException> { state.copy(page = -1) }
    }

    @Test
    fun activationIsLazyUntilExplicitRequestRuns() {
        val executor = QueuedExecutor()
        var installCalls = 0
        val engine = FakeEngine()
        val manager = EngineBundleManager(
            context = context,
            executor = executor,
            installBundle = EngineBundleInstallTask { _, manifest, _ ->
                installCalls++
                assertEquals("cjk/offline-pinyin/manifest.json", manifest)
                File(context.cacheDir, "offline-pinyin")
            },
        )
        manager.register(
            EngineBundleSpec("offline-pinyin", "cjk/offline-pinyin/manifest.json") { _, _ -> engine },
        )

        assertEquals(0, installCalls)
        assertEquals(EngineBundleLifecycleState.Idle, manager.state)
        assertEquals(setOf("offline-pinyin"), manager.registeredBundleIds())

        manager.activate("offline-pinyin")
        assertEquals(0, installCalls)
        assertEquals(EngineBundleLifecycleState.Loading("offline-pinyin"), manager.state)

        executor.runAll()
        assertEquals(1, installCalls)
        assertEquals(EngineBundleLifecycleState.Ready("offline-pinyin"), manager.state)
        assertSame(engine, manager.currentEngine())
    }

    @Test
    fun engineSpecCanonicalizesOnlySafeManifestAssetPaths() {
        val spec = EngineBundleSpec("offline-pinyin", "cjk\\offline-pinyin\\manifest.json") { _, _ -> FakeEngine() }
        assertEquals("cjk/offline-pinyin/manifest.json", spec.manifestAssetPath)
        assertFailsWith<IllegalArgumentException> {
            EngineBundleSpec("unsafe", "cjk/../offline-pinyin/manifest.json") { _, _ -> FakeEngine() }
        }
        assertFailsWith<IllegalArgumentException> {
            EngineBundleSpec("unsafe", "cjk//offline-pinyin/manifest.json") { _, _ -> FakeEngine() }
        }
    }

    @Test
    fun cancelledRequestNeverInstallsOrCreatesAnEngine() {
        val executor = QueuedExecutor()
        var installCalls = 0
        var factoryCalls = 0
        val manager = EngineBundleManager(
            context = context,
            executor = executor,
            installBundle = EngineBundleInstallTask { _, _, _ ->
                installCalls++
                File(context.cacheDir, "offline-kana")
            },
        )
        manager.register(
            EngineBundleSpec("offline-kana", "cjk/offline-kana/manifest.json") { _, _ ->
                factoryCalls++
                FakeEngine()
            },
        )

        val activation = manager.activate("offline-kana")
        activation.cancel()
        executor.runAll()

        assertTrue(activation.isCancelled)
        assertEquals(0, installCalls)
        assertEquals(0, factoryCalls)
        assertEquals(EngineBundleLifecycleState.Idle, manager.state)
        assertNull(manager.currentEngine())
    }

    @Test
    fun listenerActivationTransfersEngineOnlyWhenTheImeClaimsIt() {
        val executor = QueuedExecutor()
        val engine = FakeEngine()
        var readyActivation: EngineBundleActivation? = null
        val manager = EngineBundleManager(
            context = context,
            executor = executor,
            installBundle = EngineBundleInstallTask { _, _, _ -> File(context.cacheDir, "offline-mozc") },
        )
        manager.register(
            EngineBundleSpec("offline-mozc", "cjk/offline-mozc/manifest.json") { _, _ -> engine },
        )

        val activation = manager.activate(
            "offline-mozc",
            object : EngineBundleActivationListener {
                override fun onEngineReady(activation: EngineBundleActivation) {
                    readyActivation = activation
                }

                override fun onEngineFailure(activation: EngineBundleActivation, error: Throwable) {
                    throw AssertionError("Unexpected activation failure", error)
                }
            },
        )
        executor.runAll()

        assertSame(activation, readyActivation)
        assertSame(engine, manager.currentEngine())
        val handedOff = manager.takeActiveEngine(activation)
        assertSame(engine, handedOff)
        assertNull(manager.currentEngine())
        assertEquals(EngineBundleLifecycleState.Idle, manager.state)

        handedOff?.close()
        assertEquals(1, engine.closeCalls)
    }

    @Test
    fun cancellingUnclaimedListenerEngineClosesIt() {
        val executor = QueuedExecutor()
        val engine = FakeEngine()
        val manager = EngineBundleManager(
            context = context,
            executor = executor,
            installBundle = EngineBundleInstallTask { _, _, _ -> File(context.cacheDir, "offline-mozc") },
        )
        manager.register(
            EngineBundleSpec("offline-mozc", "cjk/offline-mozc/manifest.json") { _, _ -> engine },
        )
        val activation = manager.activate(
            "offline-mozc",
            object : EngineBundleActivationListener {
                override fun onEngineReady(activation: EngineBundleActivation) = Unit

                override fun onEngineFailure(activation: EngineBundleActivation, error: Throwable) = Unit
            },
        )
        executor.runAll()

        activation.cancel()
        assertEquals(1, engine.closeCalls)
        assertNull(manager.currentEngine())
        assertEquals(EngineBundleLifecycleState.Idle, manager.state)
    }

    @Test
    fun staleActivationDoesNotCrossTheInstallToFactoryBoundary() {
        val executor = QueuedExecutor()
        var firstFactoryCalls = 0
        var secondFactoryCalls = 0
        lateinit var manager: EngineBundleManager
        manager = EngineBundleManager(
            context = context,
            executor = executor,
            installBundle = EngineBundleInstallTask { _, manifest, _ ->
                if (manifest == "cjk/first/manifest.json") {
                    // Simulate a subtype switch while a large bundle has just finished copying.
                    manager.activate("second")
                }
                File(context.cacheDir, manifest.substringAfter("cjk/").substringBefore('/'))
            },
        )
        manager.register(
            EngineBundleSpec("first", "cjk/first/manifest.json") { _, _ ->
                firstFactoryCalls++
                FakeEngine()
            },
        )
        manager.register(
            EngineBundleSpec("second", "cjk/second/manifest.json") { _, _ ->
                secondFactoryCalls++
                FakeEngine()
            },
        )

        manager.activate("first")
        executor.runAll()

        assertEquals(0, firstFactoryCalls)
        assertEquals(1, secondFactoryCalls)
        assertEquals(EngineBundleLifecycleState.Ready("second"), manager.state)
    }

    @Test
    fun memoryPressureAndCloseReleaseActiveEngine() {
        val executor = QueuedExecutor()
        val first = FakeEngine()
        val manager = EngineBundleManager(
            context = context,
            executor = executor,
            installBundle = EngineBundleInstallTask { _, _, _ ->
                File(context.cacheDir, "offline-thai")
            },
        )
        manager.register(
            EngineBundleSpec("offline-thai", "cjk/offline-thai/manifest.json") { _, _ -> first },
        )
        manager.activate("offline-thai")
        executor.runAll()

        manager.onTrimMemory(10) // Android's stable TRIM_MEMORY_RUNNING_LOW threshold.
        assertEquals(1, first.closeCalls)
        assertEquals(EngineBundleLifecycleState.Idle, manager.state)
        assertNull(manager.currentEngine())

        manager.close()
        assertEquals(EngineBundleLifecycleState.Closed, manager.state)
        assertFailsWith<IllegalStateException> { manager.activate("offline-thai") }
        assertFalse(manager.registeredBundleIds().isEmpty())
    }

    private class QueuedExecutor : Executor {
        private val queued = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            queued += command
        }

        fun runAll() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private class FakeEngine : CompositionEngine {
        var closeCalls = 0

        override fun processKey(key: String) = CompositionState(preedit = key, inputMode = "test")

        override fun backspace() = CompositionState(inputMode = "test")

        override fun selectCandidate(index: Int) = CompositionState(inputMode = "test")

        override fun commit() = CompositionState(inputMode = "test")

        override fun reset() = CompositionState(inputMode = "test")

        override fun close() {
            closeCalls++
        }
    }
}
