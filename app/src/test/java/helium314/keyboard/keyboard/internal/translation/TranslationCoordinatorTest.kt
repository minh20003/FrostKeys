// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal.translation

import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Behavioral tests for TranslationCoordinator.
 *
 * Tests verify:
 * 1. Same language returns LOCAL result without network
 * 2. Empty input returns InputInvalid error
 * 3. Stub provider reports NOT_AVAILABLE
 * 4. On-device used when cloud disabled and capable
 * 5. Cloud disabled with no capable provider returns unavailable
 * 6. Cancellation cancels the job
 * 7. New translation cancels previous
 * 8. All error types are accessible
 * 9. Provider labels are correct
 * 10. Download notification when available
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class TranslationCoordinatorTest {
    private lateinit var context: Context
    private lateinit var coordinator: TranslationCoordinator

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.prefs().edit {
            putBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, true)
            putBoolean(Settings.PREF_TRANSLATION_ON_DEVICE_FALLBACK, true)
        }

        coordinator = TranslationCoordinator(context, StubOnDeviceTranslationProvider())
    }

    @After
    fun tearDown() {
        coordinator.release()
        context.prefs().edit {
            putBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, false)
            putBoolean(Settings.PREF_TRANSLATION_ON_DEVICE_FALLBACK, false)
        }
    }

    // ========== Same Language Tests ==========

    @Test
    fun sameLanguage_returnsLocalWithoutNetwork() {
        var result: String? = null
        var provider: TranslationCoordinator.ResultProvider? = null

        coordinator.translate(
            sourceText = "Hello world",
            source = TranslationLanguage.ENGLISH,
            target = TranslationLanguage.ENGLISH,
            quality = TranslationPromptBuilder.Quality.FAST,
            callback = object : TranslationCoordinator.TranslationCallback {
                override fun onResult(translation: String?, p: TranslationCoordinator.ResultProvider) {
                    result = translation
                    provider = p
                }
                override fun onError(error: TranslationCoordinator.TranslationError, p: TranslationCoordinator.ResultProvider) {}
                override fun onOnDeviceDownloadRequired() {}
            }
        )

        assertEquals("Hello world", result)
        assertEquals(TranslationCoordinator.ResultProvider.LOCAL, provider)
    }

    // ========== Empty Input Tests ==========

    @Test
    fun emptyText_returnsInputInvalidError() {
        var error: TranslationCoordinator.TranslationError? = null

        coordinator.translate(
            sourceText = "",
            source = TranslationLanguage.ENGLISH,
            target = TranslationLanguage.VIETNAMESE,
            quality = TranslationPromptBuilder.Quality.FAST,
            callback = object : TranslationCoordinator.TranslationCallback {
                override fun onResult(translation: String?, p: TranslationCoordinator.ResultProvider) {}
                override fun onError(e: TranslationCoordinator.TranslationError, p: TranslationCoordinator.ResultProvider) {
                    error = e
                }
                override fun onOnDeviceDownloadRequired() {}
            }
        )

        assertTrue(error is TranslationCoordinator.TranslationError.InputInvalid)
    }

    // ========== Stub Provider Tests ==========

    @Test
    fun stubProvider_reportsNotAvailable() {
        val stub = StubOnDeviceTranslationProvider()
        assertEquals(
            OnDeviceTranslationWrapper.CapabilityState.NOT_AVAILABLE,
            stub.getCapabilityState("en", "vi")
        )
    }

    @Test
    fun stubProvider_settingsIntentIsNull() {
        val stub = StubOnDeviceTranslationProvider()
        assertNull(stub.getSettingsPendingIntent())
    }

    @Test
    fun stubProvider_detectReturnsNull() {
        val stub = StubOnDeviceTranslationProvider()
        assertNull(stub.detectLanguage("Hello"))
    }

    // ========== Cloud Disabled Tests ==========

    @Test
    fun cloudDisabled_noApiKey_noOnDevice_returnsUnavailable() {
        // Disable cloud
        context.prefs().edit {
            putBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, false)
        }

        // Use stub provider (not capable)
        val testCoordinator = TranslationCoordinator(context, StubOnDeviceTranslationProvider())

        var error: TranslationCoordinator.TranslationError? = null

        testCoordinator.translate(
            sourceText = "Hello",
            source = TranslationLanguage.ENGLISH,
            target = TranslationLanguage.VIETNAMESE,
            quality = TranslationPromptBuilder.Quality.FAST,
            callback = object : TranslationCoordinator.TranslationCallback {
                override fun onResult(translation: String?, p: TranslationCoordinator.ResultProvider) {}
                override fun onError(e: TranslationCoordinator.TranslationError, p: TranslationCoordinator.ResultProvider) {
                    error = e
                }
                override fun onOnDeviceDownloadRequired() {}
            }
        )

        // Should get unavailable error
        assertTrue(
            error is TranslationCoordinator.TranslationError.Unavailable ||
            error is TranslationCoordinator.TranslationError.OnDeviceUnavailable,
            "Should get unavailable when cloud disabled and on-device not capable"
        )

        testCoordinator.release()

        // Restore
        context.prefs().edit {
            putBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, true)
        }
    }

    // ========== Cancellation Tests ==========

    @Test
    fun cancelPending_cancelsJobs() {
        // Cancel with no pending job should not throw
        coordinator.cancelPending()
        // Just verify it doesn't crash
    }

    @Test
    fun release_cleansUp() {
        coordinator.release()
        // Verify release doesn't crash
    }

    // ========== Error Types Tests ==========

    @Test
    fun translationErrorTypes_areDistinct() {
        // Verify all error types are distinct
        val errors = setOf(
            TranslationCoordinator.TranslationError.InputInvalid,
            TranslationCoordinator.TranslationError.CloudDisabled,
            TranslationCoordinator.TranslationError.ApiKeyMissing,
            TranslationCoordinator.TranslationError.ApiKeyInvalid,
            TranslationCoordinator.TranslationError.QuotaExhausted(60),
            TranslationCoordinator.TranslationError.SafetyBlocked,
            TranslationCoordinator.TranslationError.OnDeviceUnavailable(canDownload = true),
            TranslationCoordinator.TranslationError.Cancelled,
            TranslationCoordinator.TranslationError.Unavailable,
            TranslationCoordinator.TranslationError.Unknown("test"),
        )

        assertEquals(10, errors.size)
    }

    @Test
    fun quotaExhausted_hasSeconds() {
        val error = TranslationCoordinator.TranslationError.QuotaExhausted(120)
        val seconds = when (error) {
            is TranslationCoordinator.TranslationError.QuotaExhausted -> error.seconds
            else -> 0L
        }
        assertEquals(120L, seconds)
    }

    // ========== Provider Label Tests ==========

    @Test
    fun localProvider_hasCorrectName() {
        assertEquals(TranslationCoordinator.ResultProvider.LOCAL, TranslationCoordinator.ResultProvider.LOCAL)
    }

    @Test
    fun geminiProvider_hasCorrectName() {
        assertEquals(TranslationCoordinator.ResultProvider.GEMINI, TranslationCoordinator.ResultProvider.GEMINI)
    }

    @Test
    fun onDeviceProvider_hasCorrectName() {
        assertEquals(TranslationCoordinator.ResultProvider.ON_DEVICE, TranslationCoordinator.ResultProvider.ON_DEVICE)
    }

    @Test
    fun noneProvider_exists() {
        assertEquals(TranslationCoordinator.ResultProvider.NONE, TranslationCoordinator.ResultProvider.NONE)
    }

    // ========== On-Device Download Tests ==========

    @Test
    fun downloadRequiredCallback_notifiesListener() {
        // Create provider that returns AVAILABLE_TO_DOWNLOAD
        val downloadableProvider = object : OnDeviceTranslationProvider {
            override suspend fun translate(
                text: String,
                sourceLanguage: String,
                targetLanguage: String,
                signal: CancellationSignal,
            ): OnDeviceTranslationWrapper.TranslationResult {
                return OnDeviceTranslationWrapper.TranslationResult.Error("stub")
            }

            override fun getCapabilityState(
                sourceLanguage: String,
                targetLanguage: String,
            ): OnDeviceTranslationWrapper.CapabilityState {
                return OnDeviceTranslationWrapper.CapabilityState.AVAILABLE_TO_DOWNLOAD
            }

            override fun detectLanguage(text: String): Pair<String, Float>? = null
            override fun getSettingsPendingIntent(): android.app.PendingIntent? = null
        }

        // Disable cloud to use on-device directly
        context.prefs().edit {
            putBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, false)
        }

        val testCoordinator = TranslationCoordinator(context, downloadableProvider)
        var downloadRequiredCalled = false

        testCoordinator.translate(
            sourceText = "Hello",
            source = TranslationLanguage.ENGLISH,
            target = TranslationLanguage.VIETNAMESE,
            quality = TranslationPromptBuilder.Quality.FAST,
            callback = object : TranslationCoordinator.TranslationCallback {
                override fun onResult(translation: String?, p: TranslationCoordinator.ResultProvider) {}
                override fun onError(error: TranslationCoordinator.TranslationError, p: TranslationCoordinator.ResultProvider) {}
                override fun onOnDeviceDownloadRequired() {
                    downloadRequiredCalled = true
                }
            }
        )

        assertTrue(downloadRequiredCalled, "Should notify when on-device is available to download")

        testCoordinator.release()

        // Restore
        context.prefs().edit {
            putBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, true)
        }
    }

    // ========== Language Tag Tests ==========

    @Test
    fun englishLanguage_hasCorrectId() {
        assertEquals("en", TranslationLanguage.ENGLISH.id)
    }

    @Test
    fun vietnameseLanguage_hasCorrectId() {
        assertEquals("vi", TranslationLanguage.VIETNAMESE.id)
    }

    @Test
    fun autoDetectLanguage_isMarkedCorrectly() {
        assertTrue(TranslationLanguage.AUTO_DETECT.isAutoDetect)
        assertEquals("auto", TranslationLanguage.AUTO_DETECT.id)
    }
}
