// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal.translation

import android.annotation.SuppressLint
import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.CancellationSignal
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationResponse
import android.view.translation.TranslationResponseValue
import android.view.translation.TranslationSpec
import android.view.translation.Translator
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

/**
 * Wrapper for Android's on-device translation capability (API 31+).
 *
 * The android.view.translation public API is available to all third-party apps on Android S+.
 * On-device translation capability depends on:
 * 1. The device manufacturer has included the translation service
 * 2. The specific language pair has been downloaded
 *
 * When on-device translation is not available, cloud translation (Gemini) is used.
 *
 * @see android.view.translation.TranslationManager
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
object OnDeviceTranslationWrapper {

    /**
     * Represents the capability state of on-device translation.
     */
    enum class CapabilityState {
        /** Translation can be performed on-device with an already-downloaded model. */
        ON_DEVICE,
        /** Translation capability is available but requires downloading a model. */
        AVAILABLE_TO_DOWNLOAD,
        /** Translation capability is not available for this language pair. */
        NOT_AVAILABLE,
    }

    /**
     * Result of a translation operation.
     */
    sealed class TranslationResult {
        data class Success(val translatedText: String) : TranslationResult()
        data class Error(val reason: String) : TranslationResult()
    }

    /**
     * Gets the capability state for a source/target language pair.
     *
     * @param context Android context
     * @param sourceLanguage BCP-47 language tag
     * @param targetLanguage BCP-47 language tag
     * @return CapabilityState indicating what is available
     */
    fun getCapabilityState(
        context: Context,
        sourceLanguage: String,
        targetLanguage: String,
    ): CapabilityState {
        val translationManager = context.getSystemService(TranslationManager::class.java)
            ?: return CapabilityState.NOT_AVAILABLE

        val sourceLocale = try {
            ULocale.forLanguageTag(sourceLanguage)
        } catch (e: Exception) {
            return CapabilityState.NOT_AVAILABLE
        }
        val targetLocale = try {
            ULocale.forLanguageTag(targetLanguage)
        } catch (e: Exception) {
            return CapabilityState.NOT_AVAILABLE
        }

        val sourceSpec = TranslationSpec(sourceLocale, TranslationSpec.DATA_FORMAT_TEXT)
        val targetSpec = TranslationSpec(targetLocale, TranslationSpec.DATA_FORMAT_TEXT)

        val capabilities: Set<TranslationCapability> = try {
            translationManager.getOnDeviceTranslationCapabilities(
                sourceSpec.dataFormat,
                targetSpec.dataFormat,
            )
        } catch (e: Exception) {
            return CapabilityState.NOT_AVAILABLE
        }

        for (capability in capabilities) {
            val capSourceLocale = capability.sourceSpec.locale
            val capTargetLocale = capability.targetSpec.locale
            // Check locale matching (compare language + country if present)
            if (localesMatch(capSourceLocale, sourceLocale) &&
                localesMatch(capTargetLocale, targetLocale) &&
                capability.sourceSpec.dataFormat == TranslationSpec.DATA_FORMAT_TEXT &&
                capability.targetSpec.dataFormat == TranslationSpec.DATA_FORMAT_TEXT
            ) {
                return when (capability.state) {
                    TranslationCapability.STATE_ON_DEVICE -> CapabilityState.ON_DEVICE
                    TranslationCapability.STATE_AVAILABLE_TO_DOWNLOAD -> CapabilityState.AVAILABLE_TO_DOWNLOAD
                    else -> CapabilityState.NOT_AVAILABLE
                }
            }
        }

        return CapabilityState.NOT_AVAILABLE
    }

    /**
     * Opens the system settings for on-device translation downloads.
     * Returns a PendingIntent that opens the Translation settings, or null if not available.
     */
    fun getOnDeviceSettingsPendingIntent(
        context: Context,
    ): android.app.PendingIntent? {
        val translationManager = context.getSystemService(TranslationManager::class.java)
            ?: return null
        return try {
            translationManager.onDeviceTranslationSettingsActivityIntent
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Detects the language of the given text using TextClassifier.
     *
     * @param context Android context
     * @param text Text to analyze
     * @return Pair of (BCP-47 language tag, confidence) or null if uncertain
     */
    fun detectLanguage(context: Context, text: String): Pair<String, Float>? {
        if (text.isBlank()) return null

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null

        return try {
            val tcm = context.getSystemService(TextClassificationManager::class.java)
                ?: return null
            val textClassifier = tcm.textClassifier
            if (textClassifier === android.view.textclassifier.TextClassifier.NO_OP) return null

            val request = TextLanguage.Request.Builder(text).build()
            val result = textClassifier.detectLanguage(request)
                ?: return null

            if (result.localeHypothesisCount == 0) return null

            val topLocale = result.getLocale(0)
            val confidence = result.getConfidenceScore(topLocale)
            if (confidence >= MIN_DETECTION_CONFIDENCE) {
                val tag = topLocale.toLanguageTag()
                Pair(tag, confidence)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Translates text using on-device translation (API 36+).
     *
     * This is an optional path. When on-device is not available,
     * use TranslationService (Gemini cloud) instead.
     *
     * @param context Android context
     * @param text Text to translate
     * @param sourceLanguage BCP-47 language tag
     * @param targetLanguage BCP-47 language tag
     * @param cancellationSignal Android CancellationSignal
     * @return TranslationResult with either the translated text or an error
     */
    suspend fun translate(
        context: Context,
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        cancellationSignal: CancellationSignal,
    ): TranslationResult = withContext(Dispatchers.Main) {
        if (text.isBlank()) {
            return@withContext TranslationResult.Error("empty_text")
        }

        val translationManager = context.getSystemService(TranslationManager::class.java)
            ?: return@withContext TranslationResult.Error("no_translation_manager")

        val sourceLocale = try {
            ULocale.forLanguageTag(sourceLanguage)
        } catch (e: Exception) {
            return@withContext TranslationResult.Error("invalid_source_locale")
        }
        val targetLocale = try {
            ULocale.forLanguageTag(targetLanguage)
        } catch (e: Exception) {
            return@withContext TranslationResult.Error("invalid_target_locale")
        }

        val sourceSpec = TranslationSpec(sourceLocale, TranslationSpec.DATA_FORMAT_TEXT)
        val targetSpec = TranslationSpec(targetLocale, TranslationSpec.DATA_FORMAT_TEXT)

        val translationContext = TranslationContext.Builder(sourceSpec, targetSpec)
            .setTranslationFlags(TranslationContext.FLAG_LOW_LATENCY)
            .build()

        suspendCancellableCoroutine<TranslationResult> { continuation ->
            val contRef = continuation
            // Track the translator instance for proper cleanup
            var translatorRef: Translator? = null
            var resultDelivered = false

            val executor = Executor { command ->
                if (contRef.isActive) {
                    Dispatchers.Main.immediate.dispatch(contRef.context) { command.run() }
                }
            }

            val responseCallback = java.util.function.Consumer<TranslationResponse> { response ->
                // Check if already cancelled or result delivered
                if (resultDelivered) {
                    // Stale callback - ignore
                    translatorRef?.destroy()
                    return@Consumer
                }
                if (cancellationSignal.isCanceled) {
                    translatorRef?.destroy()
                    return@Consumer
                }
                if (!contRef.isActive) {
                    translatorRef?.destroy()
                    return@Consumer
                }

                val result = parseTranslationResponse(response, cancellationSignal)
                resultDelivered = true
                translatorRef?.destroy()

                contRef.resume(result) {
                    // onCancellation - destroy translator if needed
                    if (!resultDelivered) {
                        translatorRef?.destroy()
                    }
                }
            }

            val translatorCallback = java.util.function.Consumer<Translator> { translator ->
                translatorRef = translator

                if (cancellationSignal.isCanceled) {
                    translator.destroy()
                    return@Consumer
                }
                if (!contRef.isActive) {
                    translator.destroy()
                    return@Consumer
                }
                if (resultDelivered) {
                    translator.destroy()
                    return@Consumer
                }

                val request = TranslationRequest.Builder()
                    .setTranslationRequestValues(listOf(TranslationRequestValue.forText(text)))
                    .build()
                translator.translate(request, cancellationSignal, executor, responseCallback)
            }

            try {
                translationManager.createOnDeviceTranslator(translationContext, executor, translatorCallback)
            } catch (e: Exception) {
                if (!contRef.isActive) return@suspendCancellableCoroutine
                resultDelivered = true
                contRef.resume(TranslationResult.Error("translator_error")) {
                    // onCancellation - nothing additional
                }
            }

            contRef.invokeOnCancellation {
                // If result not yet delivered, clean up
                if (!resultDelivered) {
                    resultDelivered = true
                    translatorRef?.destroy()
                }
            }
        }
    }

    private fun parseTranslationResponse(
        response: TranslationResponse,
        cancellationSignal: CancellationSignal,
    ): TranslationResult {
        if (cancellationSignal.isCanceled) {
            return TranslationResult.Error("cancelled")
        }

        if (response.translationStatus != TranslationResponse.TRANSLATION_STATUS_SUCCESS) {
            return TranslationResult.Error("translation_failed_status:${response.translationStatus}")
        }

        val values = response.translationResponseValues
        if (values == null || values.size() == 0) {
            return TranslationResult.Error("no_translation_values")
        }

        // Join all valid text parts (multi-part translation)
        val translatedParts = StringBuilder()
        for (i in 0 until values.size()) {
            val value = values.get(i)
            if (value.statusCode == TranslationResponseValue.STATUS_SUCCESS) {
                val text = value.text?.toString()
                if (!text.isNullOrBlank()) {
                    if (translatedParts.isNotEmpty()) {
                        translatedParts.append(" ")
                    }
                    translatedParts.append(text)
                }
            }
        }

        val result = translatedParts.toString()
        return if (result.isNotBlank()) {
            TranslationResult.Success(result)
        } else {
            TranslationResult.Error("empty_translation_result")
        }
    }

    private fun localesMatch(a: ULocale, b: ULocale): Boolean {
        // Match on language; optionally country if both have it
        if (a.language != b.language) return false
        val aCountry = a.country
        val bCountry = b.country
        if (aCountry.isNotEmpty() && bCountry.isNotEmpty()) {
            return aCountry == bCountry
        }
        return true
    }

    private const val MIN_DETECTION_CONFIDENCE = 0.5f
}

/**
 * Backward-compatible interface for testing without real OEM services.
 */
interface OnDeviceTranslationProvider {
    /**
     * Translates text using on-device translation.
     * @param signal CancellationSignal for cancelling the operation
     */
    suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        signal: android.os.CancellationSignal,
    ): OnDeviceTranslationWrapper.TranslationResult

    fun getCapabilityState(
        sourceLanguage: String,
        targetLanguage: String,
    ): OnDeviceTranslationWrapper.CapabilityState

    fun detectLanguage(text: String): Pair<String, Float>?

    fun getSettingsPendingIntent(): android.app.PendingIntent?
}

/**
 * Stub implementation for testing without real TranslationManager.
 * Use this in tests to avoid requiring a real TranslationManager.
 */
class StubOnDeviceTranslationProvider : OnDeviceTranslationProvider {
    override suspend fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        signal: android.os.CancellationSignal,
    ): OnDeviceTranslationWrapper.TranslationResult {
        return OnDeviceTranslationWrapper.TranslationResult.Error("stub_provider")
    }

    override fun getCapabilityState(
        sourceLanguage: String,
        targetLanguage: String,
    ): OnDeviceTranslationWrapper.CapabilityState {
        return OnDeviceTranslationWrapper.CapabilityState.NOT_AVAILABLE
    }

    override fun detectLanguage(text: String): Pair<String, Float>? {
        return null
    }

    override fun getSettingsPendingIntent(): android.app.PendingIntent? {
        return null
    }
}
