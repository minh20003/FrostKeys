// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

/** Immutable, generation-gated hand-off from Rime's IME runtime to its candidate strip. */
data class RimeCandidatePresentation(
    val state: CompositionState,
    val generation: Long,
) {
    init {
        require(generation >= 0L) { "Rime candidate generation must not be negative" }
    }
}

/** Keeps the optional Rime UI independent from LatinIME's generic SuggestedWords strip. */
interface RimeCandidateUiSink {
    fun show(presentation: RimeCandidatePresentation)

    fun hide()

    companion object {
        val NONE: RimeCandidateUiSink = object : RimeCandidateUiSink {
            override fun show(presentation: RimeCandidatePresentation) = Unit

            override fun hide() = Unit
        }
    }
}

/** Main-thread-only identity store that makes a tap from a detached strip inert. */
internal class RimeCandidatePresentationStore {
    var current: RimeCandidatePresentation? = null
        private set

    private var nextGeneration = 0L

    fun publish(state: CompositionState): RimeCandidatePresentation =
        RimeCandidatePresentation(state, takeNextGeneration()).also { current = it }

    fun refreshForNewView(): RimeCandidatePresentation? = current?.let { publish(it.state) }

    fun accepts(generation: Long): Boolean = generation == current?.generation

    fun clear() {
        current = null
        takeNextGeneration()
    }

    private fun takeNextGeneration(): Long {
        val generation = nextGeneration
        nextGeneration = if (generation == Long.MAX_VALUE) 0L else generation + 1L
        return generation
    }
}
