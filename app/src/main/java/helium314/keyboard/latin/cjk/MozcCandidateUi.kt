// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

/**
 * Immutable hand-off from the optional Mozc runtime to its dedicated candidate strip.
 *
 * Every user action must return [generation] to the runtime. A delayed click from a detached or
 * already-redrawn strip is then harmless instead of selecting a candidate from a newer native
 * window.
 */
data class MozcCandidatePresentation(
    val state: CompositionState,
    val generation: Long,
) {
    init {
        require(generation >= 0L) { "Mozc candidate generation must not be negative" }
    }
}

/** Minimal view boundary so CJK runtime code stays independent from LatinIME's suggestion strip. */
interface MozcCandidateUiSink {
    fun show(presentation: MozcCandidatePresentation)

    fun hide()

    companion object {
        val NONE: MozcCandidateUiSink = object : MozcCandidateUiSink {
            override fun show(presentation: MozcCandidatePresentation) = Unit

            override fun hide() = Unit
        }
    }
}

/**
 * Main-thread-only generation owner for the dedicated Mozc candidate row.
 *
 * Separating it from the view is intentional: both a detached view click and an asynchronous
 * state callback must pass the same identity gate. A refresh for a replacement input-view tree
 * receives a new generation even when its text/candidates are unchanged.
 */
internal class MozcCandidatePresentationStore {
    var current: MozcCandidatePresentation? = null
        private set

    private var nextGeneration = 0L

    fun publish(state: CompositionState): MozcCandidatePresentation {
        return MozcCandidatePresentation(state, takeNextGeneration()).also { current = it }
    }

    fun refreshForNewView(): MozcCandidatePresentation? {
        val previous = current ?: return null
        return publish(previous.state)
    }

    fun accepts(generation: Long): Boolean = generation == current?.generation

    fun clear() {
        current = null
        // Advance even with no current row, invalidating a click that was posted immediately
        // before a window teardown.
        takeNextGeneration()
    }

    private fun takeNextGeneration(): Long {
        val generation = nextGeneration
        // The value is only an in-process action identity. Wrapping keeps the immutable
        // presentation contract non-negative during long-lived instrumentation runs.
        nextGeneration = if (generation == Long.MAX_VALUE) 0L else generation + 1L
        return generation
    }
}
