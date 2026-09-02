// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

/**
 * A user-safe, resource-backed error from the cloud-writing pipeline.
 *
 * Network/provider text is deliberately never surfaced here: it can be untranslated, noisy, or
 * include request-specific details. The view maps [reason] to a localized message instead.
 */
internal class AiWritingFailure(
    val reason: Reason,
    val retryAfterSeconds: Long = 0L,
) : Exception(reason.name) {
    internal enum class Reason {
        INPUT_TOO_LARGE,
        API_KEY_MISSING,
        MODELS_INITIALIZING,
        GENERATION_IN_PROGRESS,
        UNAVAILABLE,
        MODEL_LIST_REFRESHED,
        INVALID_API_KEY,
        EMPTY_RESPONSE,
        INVALID_RESPONSE,
        QUOTA_EXHAUSTED,
    }
}
