// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal.translation

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.CancellationSignal
import helium314.keyboard.latin.cloud.CloudFeature
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.cloud.CloudRequestGate
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Orchestrates translation between Gemini cloud and on-device fallback.
 *
 * Decision flow:
 * 1. Validate input locally (empty/invalid)
 * 2. If same source==target, return source locally without any network
 * 3. Try Gemini when cloud is enabled and key exists
 * 4. On eligible Gemini failures, fallback to on-device if enabled and capable
 * 5. If cloud disabled/key missing and on-device fallback enabled, use on-device directly
 *
 * @param context Android context
 * @param onDeviceProvider Provider for on-device translation (null to disable)
 */
class TranslationCoordinator(
    private val context: Context,
    private var onDeviceProvider: OnDeviceTranslationProvider? = null,
) {
    private val tag = "TranslationCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentJob: Job? = null

    // Active cancellation signal for on-device translation
    private var onDeviceCancellationSignal: CancellationSignal? = null

    // Track if current translation was cancelled to ignore stale callbacks
    @Volatile
    private var isCancelled = false

    /** Current result provider for UI display */
    enum class ResultProvider {
        NONE,
        LOCAL,
        GEMINI,
        ON_DEVICE,
    }

    /**
     * Callback interface for translation results.
     */
    interface TranslationCallback {
        fun onResult(
            translation: String?,
            provider: ResultProvider,
        )
        fun onError(
            error: TranslationError,
            provider: ResultProvider,
        )
        fun onOnDeviceDownloadRequired()
    }

    /**
     * Typed translation errors.
     */
    sealed class TranslationError {
        data object InputInvalid : TranslationError()
        data object CloudDisabled : TranslationError()
        data object ApiKeyMissing : TranslationError()
        data object ApiKeyInvalid : TranslationError()
        data class QuotaExhausted(val seconds: Long) : TranslationError()
        data object SafetyBlocked : TranslationError()
        data class OnDeviceUnavailable(val canDownload: Boolean) : TranslationError()
        data object Cancelled : TranslationError()
        data object Unavailable : TranslationError()
        data class Unknown(val details: String) : TranslationError()
    }

    /**
     * Translates text using the best available provider.
     *
     * Correct priority:
     * 1. Local validation (empty/invalid input)
     * 2. Same language → local result
     * 3. Try Gemini (primary when cloud enabled)
     * 4. Fallback to on-device only for eligible errors
     * 5. On-device direct when cloud disabled/missing key
     */
    fun translate(
        sourceText: String,
        source: TranslationLanguage,
        target: TranslationLanguage,
        quality: TranslationPromptBuilder.Quality,
        callback: TranslationCallback,
    ) {
        // Cancel any pending translation first
        cancelPending()
        isCancelled = false

        // Step 1: Validate input
        val validation = TranslationPromptBuilder.validateSourceText(sourceText)
        if (validation is TranslationPromptBuilder.ValidationResult.Invalid) {
            callback.onError(TranslationError.InputInvalid, ResultProvider.NONE)
            return
        }

        // Step 2: Same language - return locally without any network
        if (TranslationPromptBuilder.areSameLanguage(source, target)) {
            callback.onResult(sourceText, ResultProvider.LOCAL)
            return
        }

        // Step 3: Handle Auto source language detection
        val (actualSource, actualBcpSource) = resolveSourceLanguage(source, sourceText)

        // If auto detection was uncertain, require explicit source
        if (actualSource == null) {
            // Cannot proceed without knowing source language
            // The UI should handle this by prompting user
            callback.onError(TranslationError.InputInvalid, ResultProvider.NONE)
            return
        }

        val bcpTarget = resolveLanguageTag(target)

        // Step 4: Check cloud status and try Gemini (PRIMARY)
        val apiKey = CloudManager.getGeminiApiKey(context)
        val cloudEnabled = context.prefs().getBoolean(
            CloudManager.PREF_ENABLE_CLOUD_FEATURES,
            false // Default: cloud features disabled by default
        )

        if (cloudEnabled && apiKey.isNotEmpty()) {
            // Try Gemini - this is the PRIMARY path when cloud is enabled and has key
            translateWithGemini(sourceText, actualSource, actualBcpSource, target, bcpTarget, quality, callback)
            return
        }

        // Step 5: Cloud disabled or no key - use on-device if available and enabled
        val onDeviceEnabled = context.prefs().getBoolean(
            Settings.PREF_TRANSLATION_ON_DEVICE_FALLBACK,
            helium314.keyboard.latin.settings.Defaults.PREF_TRANSLATION_ON_DEVICE_FALLBACK
        )

        if (onDeviceEnabled && onDeviceProvider != null) {
            val capability = onDeviceProvider!!.getCapabilityState(actualBcpSource, bcpTarget)
            when (capability) {
                OnDeviceTranslationWrapper.CapabilityState.ON_DEVICE -> {
                    translateOnDevice(sourceText, actualBcpSource, bcpTarget, callback)
                    return
                }
                OnDeviceTranslationWrapper.CapabilityState.AVAILABLE_TO_DOWNLOAD -> {
                    callback.onOnDeviceDownloadRequired()
                    // Still try on-device as it may work
                    translateOnDevice(sourceText, actualBcpSource, bcpTarget, callback)
                    return
                }
                OnDeviceTranslationWrapper.CapabilityState.NOT_AVAILABLE -> {
                    // On-device not available
                    if (!cloudEnabled || apiKey.isEmpty()) {
                        callback.onError(TranslationError.OnDeviceUnavailable(canDownload = false), ResultProvider.NONE)
                    } else {
                        // Try Gemini anyway
                        translateWithGemini(sourceText, actualSource, actualBcpSource, target, bcpTarget, quality, callback)
                    }
                }
            }
        } else {
            // No on-device available
            if (cloudEnabled && apiKey.isNotEmpty()) {
                translateWithGemini(sourceText, actualSource, actualBcpSource, target, bcpTarget, quality, callback)
            } else {
                callback.onError(TranslationError.Unavailable, ResultProvider.NONE)
            }
        }
    }

    /**
     * Resolves the source language, handling Auto detection.
     * @return Pair of (TranslationLanguage or null, BCP-47 tag)
     */
    private fun resolveSourceLanguage(
        source: TranslationLanguage,
        text: String,
    ): Pair<TranslationLanguage?, String> {
        if (!source.isAutoDetect) {
            return Pair(source, source.id)
        }

        // Auto detect: use TextClassifier to detect language
        val detected = detectLanguage(text)
        if (detected != null) {
            val (tag, confidence) = detected
            val translationLanguage = TranslationLanguage.fromTag(tag)
            if (translationLanguage != null) {
                return Pair(translationLanguage, tag)
            }
        }

        // Detection failed or uncertain - cannot proceed
        return Pair(null, "und")
    }

    private fun translateWithGemini(
        sourceText: String,
        source: TranslationLanguage,
        bcpSource: String,
        target: TranslationLanguage,
        bcpTarget: String,
        quality: TranslationPromptBuilder.Quality,
        callback: TranslationCallback,
    ) {
        val onDeviceEnabled = context.prefs().getBoolean(
            Settings.PREF_TRANSLATION_ON_DEVICE_FALLBACK,
            helium314.keyboard.latin.settings.Defaults.PREF_TRANSLATION_ON_DEVICE_FALLBACK
        )

        val wrappedCallback = object : TranslationService.TranslationCallback {
            private var handled = false

            override fun onResult(translation: String?) {
                synchronized(this) {
                    if (isCancelled) return@synchronized
                    if (!handled) {
                        handled = true
                        if (translation.isNullOrBlank()) {
                            callback.onError(TranslationError.Unavailable, ResultProvider.GEMINI)
                        } else {
                            callback.onResult(translation, ResultProvider.GEMINI)
                        }
                    }
                }
            }

            override fun onError(error: TranslationService.TranslationFailure) {
                synchronized(this) {
                    if (isCancelled) return@synchronized
                    if (!handled) {
                        handled = true
                        // Check if on-device fallback is eligible
                        val shouldFallback = onDeviceEnabled && isEligibleForFallback(error.reason)

                        if (shouldFallback) {
                            Log.d(tag, "Gemini failed (${error.reason}), trying on-device fallback")
                            translateOnDevice(sourceText, bcpSource, bcpTarget, callback)
                        } else {
                            callback.onError(mapGeminiError(error), ResultProvider.GEMINI)
                        }
                    }
                }
            }
        }

        currentJob = scope.launch {
            val started = TranslationService.translate(
                context,
                sourceText,
                source,
                target,
                quality,
                wrappedCallback
            )
            if (!started) {
                callback.onError(TranslationError.Unavailable, ResultProvider.GEMINI)
            }
        }
    }

    private fun translateOnDevice(
        sourceText: String,
        source: String,
        target: String,
        callback: TranslationCallback,
    ) {
        val provider = onDeviceProvider ?: run {
            callback.onError(
                TranslationError.OnDeviceUnavailable(canDownload = false),
                ResultProvider.NONE
            )
            return
        }

        // Create and store cancellation signal
        onDeviceCancellationSignal = CancellationSignal()
        val signal = onDeviceCancellationSignal!!

        currentJob = scope.launch {
            try {
                val result = provider.translate(sourceText, source, target, signal)
                if (isCancelled) return@launch

                when (result) {
                    is OnDeviceTranslationWrapper.TranslationResult.Success -> {
                        callback.onResult(result.translatedText, ResultProvider.ON_DEVICE)
                    }
                    is OnDeviceTranslationWrapper.TranslationResult.Error -> {
                        callback.onError(
                            TranslationError.Unknown(result.reason),
                            ResultProvider.ON_DEVICE
                        )
                    }
                }
            } catch (e: Exception) {
                if (!isCancelled) {
                    callback.onError(
                        TranslationError.Unknown(e::class.simpleName ?: "unknown"),
                        ResultProvider.ON_DEVICE
                    )
                }
            }
        }
    }

    /**
     * Checks if an error is eligible for on-device fallback.
     */
    private fun isEligibleForFallback(reason: TranslationService.TranslationFailure.Reason): Boolean {
        return when (reason) {
            TranslationService.TranslationFailure.Reason.API_KEY_MISSING,
            TranslationService.TranslationFailure.Reason.CLOUD_DISABLED,
            TranslationService.TranslationFailure.Reason.QUOTA_EXHAUSTED,
            TranslationService.TranslationFailure.Reason.EMPTY_RESPONSE,
            TranslationService.TranslationFailure.Reason.INVALID_RESPONSE,
            TranslationService.TranslationFailure.Reason.UNAVAILABLE -> true

            // Do NOT fallback after these
            TranslationService.TranslationFailure.Reason.SAFETY_BLOCKED,
            TranslationService.TranslationFailure.Reason.SAME_LANGUAGE,
            TranslationService.TranslationFailure.Reason.INPUT_INVALID,
            TranslationService.TranslationFailure.Reason.API_KEY_INVALID,
            TranslationService.TranslationFailure.Reason.CANCELLED -> false
        }
    }

    /**
     * Maps Gemini errors to coordinator errors.
     */
    private fun mapGeminiError(error: TranslationService.TranslationFailure): TranslationError {
        return when (error.reason) {
            TranslationService.TranslationFailure.Reason.INPUT_INVALID -> TranslationError.InputInvalid
            TranslationService.TranslationFailure.Reason.SAME_LANGUAGE -> TranslationError.InputInvalid
            TranslationService.TranslationFailure.Reason.CLOUD_DISABLED -> TranslationError.CloudDisabled
            TranslationService.TranslationFailure.Reason.API_KEY_MISSING -> TranslationError.ApiKeyMissing
            TranslationService.TranslationFailure.Reason.API_KEY_INVALID -> TranslationError.ApiKeyInvalid
            TranslationService.TranslationFailure.Reason.QUOTA_EXHAUSTED -> TranslationError.QuotaExhausted(error.retryAfterSeconds)
            TranslationService.TranslationFailure.Reason.SAFETY_BLOCKED -> TranslationError.SafetyBlocked
            TranslationService.TranslationFailure.Reason.UNAVAILABLE -> TranslationError.OnDeviceUnavailable(canDownload = false)
            TranslationService.TranslationFailure.Reason.EMPTY_RESPONSE,
            TranslationService.TranslationFailure.Reason.INVALID_RESPONSE -> TranslationError.OnDeviceUnavailable(canDownload = false)
            TranslationService.TranslationFailure.Reason.CANCELLED -> TranslationError.Cancelled
        }
    }

    /**
     * Resolves BCP-47 language tag from TranslationLanguage.
     */
    private fun resolveLanguageTag(language: TranslationLanguage): String {
        return language.id
    }

    /**
     * Detects language using TextClassifier if available.
     */
    fun detectLanguage(text: String): Pair<String, Float>? {
        return onDeviceProvider?.detectLanguage(text)
    }

    /**
     * Gets PendingIntent for on-device translation settings.
     */
    fun getOnDeviceSettingsIntent(): android.app.PendingIntent? {
        return onDeviceProvider?.getSettingsPendingIntent()
    }

    /**
     * Cancels any pending translation.
     */
    fun cancelPending() {
        isCancelled = true
        currentJob?.cancel()
        currentJob = null
        onDeviceCancellationSignal?.cancel()
        onDeviceCancellationSignal = null
        TranslationService.cancelAll()
    }

    /**
     * Releases all resources.
     */
    fun release() {
        cancelPending()
        scope.cancel()
    }
}
