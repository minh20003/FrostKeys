// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal.translation

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.cloud.CloudFeature
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.cloud.CloudRequestGate
import helium314.keyboard.latin.utils.Log
import okhttp3.Callback
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Handles translation requests using Gemini API.
 *
 * Translation is kept separate from AI Writing Tools - it uses a simpler model selection
 * (gemini-3.5-flash-lite for FAST, gemini-3.7-flash for HIGH quality), single result output,
 * and translation-specific prompts via [TranslationPromptBuilder].
 *
 * API key is always placed in the `x-goog-api-key` header — never in the URL.
 */
object TranslationService {
    private const val TAG = "TranslationService"

    private const val MODEL_FAST = "gemini-3.5-flash-lite"
    private const val MODEL_HIGH = "gemini-3.7-flash"
    private const val API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    private const val QUOTA_COOLDOWN_FALLBACK_MS = 60_000L
    private const val HEADER_API_KEY = "x-goog-api-key"

    @Volatile private var quotaCooldownUntilMs = 0L
    private val translationTracker = TranslationGenerationTracker()

    /**
     * Translation-specific error types.
     */
    class TranslationFailure private constructor(
        val reason: Reason,
        val retryAfterSeconds: Long = 0L,
    ) : Exception(reason.name) {

        companion object {
            fun apiKeyMissing() = TranslationFailure(Reason.API_KEY_MISSING)
            fun cloudDisabled() = TranslationFailure(Reason.CLOUD_DISABLED)
            fun sameLanguage() = TranslationFailure(Reason.SAME_LANGUAGE)
            fun inputInvalid() = TranslationFailure(Reason.INPUT_INVALID)
            fun apiKeyInvalid() = TranslationFailure(Reason.API_KEY_INVALID)
            fun quotaExhausted(seconds: Long) = TranslationFailure(Reason.QUOTA_EXHAUSTED, seconds)
            fun safetyBlocked() = TranslationFailure(Reason.SAFETY_BLOCKED)
            fun unavailable() = TranslationFailure(Reason.UNAVAILABLE)
            fun emptyResponse() = TranslationFailure(Reason.EMPTY_RESPONSE)
            fun invalidResponse() = TranslationFailure(Reason.INVALID_RESPONSE)
            fun cancelled() = TranslationFailure(Reason.CANCELLED)
        }

        enum class Reason {
            API_KEY_MISSING,
            CLOUD_DISABLED,
            SAME_LANGUAGE,
            INPUT_INVALID,
            API_KEY_INVALID,
            QUOTA_EXHAUSTED,
            SAFETY_BLOCKED,
            UNAVAILABLE,
            EMPTY_RESPONSE,
            INVALID_RESPONSE,
            CANCELLED,
        }
    }

    /**
     * Callback interface for translation results.
     */
    interface TranslationCallback {
        fun onResult(translation: String?)
        fun onError(error: TranslationFailure)
    }

    /**
     * Translates text from source language to target language.
     *
     * @param context Android context
     * @param sourceText Text to translate
     * @param source Source language
     * @param target Target language
     * @param quality Translation quality setting
     * @param callback Callback for results (called on main thread)
     * @return true if request was started, false if validation failed
     */
    fun translate(
        context: Context,
        sourceText: String,
        source: TranslationLanguage,
        target: TranslationLanguage,
        quality: TranslationPromptBuilder.Quality,
        callback: TranslationCallback,
    ): Boolean {
        val mainHandler = Handler(Looper.getMainLooper())

        // Validate input first
        val validation = TranslationPromptBuilder.validateSourceText(sourceText)
        if (validation is TranslationPromptBuilder.ValidationResult.Invalid) {
            mainHandler.post {
                callback.onError(TranslationFailure.inputInvalid())
            }
            return false
        }

        // Handle same language case — return source locally WITHOUT cloud request
        // This must be BEFORE cloud gate so same-language works offline
        if (TranslationPromptBuilder.areSameLanguage(source, target)) {
            mainHandler.post {
                callback.onResult(sourceText)
            }
            return true
        }

        // Check if translation feature is allowed (requires cloud at this point)
        if (!CloudRequestGate.isFeatureAllowed(context, CloudFeature.TRANSLATION)) {
            mainHandler.post {
                callback.onError(TranslationFailure.cloudDisabled())
            }
            return false
        }

        // Get API key
        val apiKey = CloudManager.getGeminiApiKey(context)
        if (apiKey.isBlank()) {
            mainHandler.post {
                callback.onError(TranslationFailure.apiKeyMissing())
            }
            return false
        }

        // Check quota cooldown
        activeQuotaCooldownSeconds()?.let { seconds ->
            mainHandler.post {
                callback.onError(TranslationFailure.quotaExhausted(seconds))
            }
            return false
        }

        // Try to start a generation — reject duplicate requests
        val generationId = translationTracker.tryStart()
        if (generationId == null) {
            Log.d(TAG, "Ignoring duplicate translation request while another is active")
            mainHandler.post {
                callback.onError(TranslationFailure.unavailable())
            }
            return false
        }

        // Select model based on quality
        val model = when (quality) {
            TranslationPromptBuilder.Quality.FAST -> MODEL_FAST
            TranslationPromptBuilder.Quality.HIGH -> MODEL_HIGH
        }

        val guardedCallback = object : TranslationCallback {
            override fun onResult(translation: String?) {
                if (translationTracker.finish(generationId)) {
                    callback.onResult(translation)
                }
            }
            override fun onError(error: TranslationFailure) {
                if (translationTracker.finish(generationId)) {
                    callback.onError(error)
                }
            }
        }

        // Build and execute request
        executeTranslationRequest(context, apiKey, sourceText, source, target, model, quality, generationId, guardedCallback)
        return true
    }

    /**
     * Cancels all pending translation requests.
     * Call this when the translation panel closes.
     * Does NOT cancel unrelated cloud features (AI Writing Tools).
     */
    fun cancelAll() {
        translationTracker.cancel()?.cancel()
        CloudRequestGate.cancelFeature(CloudFeature.TRANSLATION)
    }

    private fun trackTranslationCall(generationId: Long, call: Call) {
        if (translationTracker.trackCall(generationId, call)) call.cancel()
    }

    private fun clearTranslationCall(generationId: Long, call: Call) {
        translationTracker.clearCall(generationId, call)
    }

    private fun activeQuotaCooldownSeconds(): Long? {
        val remainingMs = quotaCooldownUntilMs - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            quotaCooldownUntilMs = 0L
            return null
        }
        return ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
    }

    private fun setQuotaCooldown(delayMs: Long) {
        val safeDelayMs = delayMs.coerceAtLeast(1_000L)
        quotaCooldownUntilMs = System.currentTimeMillis() + safeDelayMs
    }

    private fun parseRetryDelayMs(responseBody: String?): Long? {
        if (responseBody.isNullOrBlank()) return null
        try {
            val details = JSONObject(responseBody)
                .optJSONObject("error")
                ?.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    val detail = details.optJSONObject(i) ?: continue
                    val retryDelay = detail.optString("retryDelay", "")
                    parseDelayStringMs(retryDelay)?.let { return it }
                }
            }
        } catch (_: Exception) {
            // Fall through to the loose message parser below.
        }

        val retryMatch = Regex("""retry in ([0-9.]+)s""", RegexOption.IGNORE_CASE)
            .find(responseBody)
        return retryMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let {
            (it * 1000.0).toLong()
        }
    }

    private fun parseDelayStringMs(value: String): Long? {
        if (value.isBlank()) return null
        val match = Regex("""([0-9.]+)\s*([a-z]*)""", RegexOption.IGNORE_CASE).matchEntire(value.trim())
            ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val multiplier = when (unit) {
            "", "s", "sec", "secs", "second", "seconds" -> 1000.0
            "m", "min", "mins", "minute", "minutes" -> 60_000.0
            "ms", "millisecond", "milliseconds" -> 1.0
            else -> 1000.0
        }
        return (amount * multiplier).toLong()
    }

    /**
     * Parse structured safety signals from the Gemini response.
     * Never search the raw body for words like "safety", "blocked", or "harmful" —
     * valid translated text can contain those words.
     */
    private fun parseFinishReason(jsonResponse: JSONObject): String? {
        return try {
            val candidates = jsonResponse.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                // Check prompt feedback for overall safety blocks
                val promptFeedback = jsonResponse.optJSONObject("promptFeedback")
                val blockReason = promptFeedback?.optString("blockReason")
                if (!blockReason.isNullOrBlank() && blockReason != "NONE") {
                    return blockReason
                }
                return null
            }
            val candidate = candidates.optJSONObject(0) ?: return null
            candidate.optString("finishReason").takeIf { it.isNotBlank() && it != "STOP" }
        } catch (_: Exception) {
            null
        }
    }

    private fun executeTranslationRequest(
        context: Context,
        apiKey: String,
        sourceText: String,
        source: TranslationLanguage,
        target: TranslationLanguage,
        model: String,
        quality: TranslationPromptBuilder.Quality,
        generationId: Long,
        callback: TranslationCallback,
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        // API key goes in header — never in URL — to prevent leakage in logs/proxies
        val url = "$API_BASE_URL$model:generateContent"

        // Build system instruction and user instruction
        val systemInstruction = TranslationPromptBuilder.buildSystemInstruction()
        val userInstruction = TranslationPromptBuilder.buildUserInstruction(source, target)

        val payload = try {
            JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", systemInstruction)
                    }))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "$userInstruction\n\n$sourceText")
                    }))
                }))
                // Translation is deterministic — low temperature ensures consistent results
                // thinkingConfig improves quality at slight latency cost
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 2048)
                    // Minimal thinking for fast, low thinking for high quality
                    if (quality == TranslationPromptBuilder.Quality.HIGH) {
                        put("thinkingConfig", JSONObject().apply {
                            put("thinkingBudget", 1024)
                        })
                    } else {
                        put("thinkingConfig", JSONObject().apply {
                            put("thinkingBudget", 256)
                        })
                    }
                })
            }
        } catch (e: Exception) {
            mainHandler.post {
                callback.onError(TranslationFailure.invalidResponse())
            }
            return
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader(HEADER_API_KEY, apiKey)
            .post(requestBody)
            .build()

        try {
            val call = CloudRequestGate.enqueue(
                context,
                CloudFeature.TRANSLATION,
                request,
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!translationTracker.isActive(generationId)) {
                            return  // Already cancelled/replaced — ignore
                        }
                        clearTranslationCall(generationId, call)
                        if (e is java.net.SocketException || e is java.net.ProtocolException) {
                            // These often indicate cancellation
                            if (!translationTracker.isActive(generationId)) return
                        }
                        Log.e(TAG, "Translation request failed", e)
                        mainHandler.post {
                            callback.onError(TranslationFailure.unavailable())
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!translationTracker.isActive(generationId)) {
                            response.close()
                            return  // Already cancelled/replaced
                        }
                        clearTranslationCall(generationId, call)
                        response.use { resp ->
                            val responseBody = resp.body?.string()

                            if (!resp.isSuccessful) {
                                Log.e(TAG, "Translation request failed with HTTP ${resp.code}")

                                when {
                                    resp.code == 429 -> {
                                        val retryDelayMs = parseRetryDelayMs(responseBody) ?: QUOTA_COOLDOWN_FALLBACK_MS
                                        setQuotaCooldown(retryDelayMs)
                                        mainHandler.post {
                                            callback.onError(
                                                TranslationFailure.quotaExhausted(
                                                    ((retryDelayMs + 999L) / 1000L).coerceAtLeast(1L),
                                                )
                                            )
                                        }
                                    }
                                    resp.code == 401 || resp.code == 403 -> {
                                        mainHandler.post {
                                            callback.onError(TranslationFailure.apiKeyInvalid())
                                        }
                                    }
                                    resp.code >= 500 -> {
                                        mainHandler.post {
                                            callback.onError(TranslationFailure.unavailable())
                                        }
                                    }
                                    else -> {
                                        mainHandler.post {
                                            callback.onError(TranslationFailure.unavailable())
                                        }
                                    }
                                }
                                return
                            }

                            try {
                                val jsonResponse = JSONObject(responseBody ?: "")

                                // Check structured finish reason before parsing content
                                val finishReason = parseFinishReason(jsonResponse)
                                if (finishReason != null) {
                                    val reasonUpper = finishReason.uppercase()
                                    when {
                                        reasonUpper == "SAFETY" -> {
                                            mainHandler.post {
                                                callback.onError(TranslationFailure.safetyBlocked())
                                            }
                                            return@use
                                        }
                                        reasonUpper == "MAX_TOKENS" || reasonUpper == "MAX_OUTPUT_TOKENS" -> {
                                            mainHandler.post {
                                                callback.onError(TranslationFailure.invalidResponse())
                                            }
                                            return@use
                                        }
                                        reasonUpper == "RECITATION" -> {
                                            mainHandler.post {
                                                callback.onError(TranslationFailure.safetyBlocked())
                                            }
                                            return@use
                                        }
                                        else -> {
                                            // Unknown finish reason — treat as invalid
                                            mainHandler.post {
                                                callback.onError(TranslationFailure.invalidResponse())
                                            }
                                            return@use
                                        }
                                    }
                                }

                                val candidates = jsonResponse.optJSONArray("candidates")
                                if (candidates == null || candidates.length() == 0) {
                                    Log.w(TAG, "Translation returned empty response")
                                    mainHandler.post {
                                        callback.onError(TranslationFailure.emptyResponse())
                                    }
                                    return@use
                                }

                                val candidate = candidates.optJSONObject(0)
                                if (candidate == null) {
                                    mainHandler.post {
                                        callback.onError(TranslationFailure.emptyResponse())
                                    }
                                    return@use
                                }

                                val content = candidate.optJSONObject("content")
                                val parts = content?.optJSONArray("parts")
                                if (parts == null || parts.length() == 0) {
                                    mainHandler.post {
                                        callback.onError(TranslationFailure.emptyResponse())
                                    }
                                    return@use
                                }

                                // Join all valid text parts (multi-part translation)
                                val translationBuilder = StringBuilder()
                                var hasValidText = false
                                for (i in 0 until parts.length()) {
                                    val text = parts.optJSONObject(i)?.optString("text")
                                    if (!text.isNullOrBlank()) {
                                        if (translationBuilder.isNotEmpty()) {
                                            translationBuilder.append(" ")
                                        }
                                        translationBuilder.append(text)
                                        hasValidText = true
                                    }
                                }
                                val translation = translationBuilder.toString()
                                if (!hasValidText) {
                                    mainHandler.post {
                                        callback.onError(TranslationFailure.emptyResponse())
                                    }
                                    return@use
                                }

                                // Validate output
                                val validation = TranslationPromptBuilder.validateTranslationOutput(
                                    translation,
                                    sourceText
                                )

                                if (validation is TranslationPromptBuilder.ValidationResult.Invalid) {
                                    Log.w(TAG, "Translation output validation failed: ${validation.reason}")
                                    mainHandler.post {
                                        callback.onError(TranslationFailure.invalidResponse())
                                    }
                                    return@use
                                }

                                mainHandler.post {
                                    callback.onResult(translation)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse translation response", e)
                                mainHandler.post {
                                    callback.onError(TranslationFailure.invalidResponse())
                                }
                            }
                        }
                    }
                },
            )
            trackTranslationCall(generationId, call)
        } catch (e: SecurityException) {
            mainHandler.post {
                callback.onError(TranslationFailure.cloudDisabled())
            }
        }
    }
}

/**
 * Synchronizes the one translation request that the panel permits at a time.
 * Keeps this state separate from the network gate so that a cancelled OkHttp call
 * does not prevent the next panel from starting immediately.
 */
internal class TranslationGenerationTracker {
    private val lock = Any()
    private var inFlight = false
    private var activeId = 0L
    private var activeCall: Call? = null

    fun tryStart(): Long? = synchronized(lock) {
        if (inFlight) return@synchronized null
        inFlight = true
        activeId += 1L
        activeId
    }

    fun finish(id: Long): Boolean = synchronized(lock) {
        if (!inFlight || activeId != id) return@synchronized false
        inFlight = false
        activeCall = null
        true
    }

    fun cancel(id: Long? = null): Call? = synchronized(lock) {
        if (!inFlight || (id != null && activeId != id)) return@synchronized null
        inFlight = false
        activeId += 1L
        val call = activeCall
        activeCall = null
        call
    }

    fun isActive(id: Long): Boolean = synchronized(lock) {
        inFlight && activeId == id
    }

    fun trackCall(id: Long, call: Call): Boolean = synchronized(lock) {
        if (!inFlight || activeId != id) {
            true
        } else {
            activeCall = call
            false
        }
    }

    fun clearCall(id: Long, call: Call) {
        synchronized(lock) {
            if (inFlight && activeId == id && activeCall === call) {
                activeCall = null
            }
        }
    }
}
