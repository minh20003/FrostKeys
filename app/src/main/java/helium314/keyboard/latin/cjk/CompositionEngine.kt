// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

/**
 * A snapshot of an offline composition engine.
 *
 * [inputMode] is an engine-defined stable identifier (for example, `pinyin`, `kana`, or
 * `direct`), not translated UI text. The IME owns rendering [preedit] with
 * `InputConnection.setComposingText` and rendering [candidates] in its candidate strip.
 */
data class CompositionState(
    val preedit: String = "",
    val candidates: List<String> = emptyList(),
    val page: Int = 0,
    /** Whether the native candidate window explicitly reports a preceding page. */
    val canPageBackward: Boolean = false,
    /** Whether the native candidate window explicitly reports a following page. */
    val canPageForward: Boolean = false,
    /**
     * Mozc's preedit cursor measured in Unicode code points, when the engine supplied one.
     *
     * The current generic controller preserves this information for a future cursor-aware
     * `InputConnection` adapter; it does not reinterpret it as a UTF-16 index or discard it.
     */
    val preeditCursor: Int? = null,
    val inputMode: String = "direct",
    /**
     * One-shot text that an engine has asked the IME to commit.
     *
     * Most engines only expose [preedit] and let the IME commit the currently selected candidate.
     * Mozc can instead return a `Result` in response to a regular key, submit, or candidate command.
     * Keeping that result in the immutable snapshot lets the lifecycle controller commit the exact
     * native value rather than guessing from stale preedit text. Consumers must treat this as an
     * event, not persistent candidate-strip content.
     */
    val resultText: String? = null,
    val busy: Boolean = false,
    val error: String? = null,
) {
    init {
        require(page >= 0) { "Candidate page must not be negative" }
        require(preeditCursor == null || preeditCursor >= 0) { "Preedit cursor must not be negative" }
        require(inputMode.isNotBlank()) { "Input mode must not be blank" }
    }

    val hasComposition: Boolean
        get() = preedit.isNotEmpty() || candidates.isNotEmpty()
}

/**
 * Contract shared by optional offline composition engines.
 *
 * This contract deliberately has no Android view or native-library dependency. Adapters convert
 * soft and hardware key events to [processKey], then apply the returned [CompositionState] to the
 * current [android.view.inputmethod.InputConnection]. [close] must free engine-owned memory and
 * native resources, and may be called more than once.
 */
interface CompositionEngine : AutoCloseable {
    /** Processes one textual key input and returns the resulting immutable state snapshot. */
    fun processKey(key: String): CompositionState

    /** Removes one unit from the current preedit or returns the current state when it is empty. */
    fun backspace(): CompositionState

    /** Commits the candidate at [index] and returns the resulting state snapshot. */
    fun selectCandidate(index: Int): CompositionState

    /** Commits the current preedit and returns the resulting state snapshot. */
    fun commit(): CompositionState

    /** Cancels preedit without committing it and returns the resulting state snapshot. */
    fun reset(): CompositionState

    override fun close()
}

/**
 * Optional capability for engines that expose a paged candidate window.
 *
 * Keeping paging separate from [CompositionEngine] lets compact/non-CJK engines retain the small
 * core contract while a dedicated CJK strip can safely disable its page buttons when unsupported.
 */
interface PagedCompositionEngine : CompositionEngine {
    fun previousCandidatePage(): CompositionState

    fun nextCandidatePage(): CompositionState
}

/**
 * Optional capability for engines that expose a small, reviewed set of input modes.
 *
 * The value is a stable engine identifier rather than translated UI text. Implementations must
 * reject unknown values; callers must never turn a UI label into a native command directly.
 */
interface ModeSwitchingCompositionEngine : CompositionEngine {
    fun switchInputMode(inputMode: String): CompositionState
}
