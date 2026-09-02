// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RimeCandidatePresentationStoreTest {
    @Test
    fun oldCandidateActionsAreRejectedAfterStateUpdateOrViewRecreation() {
        val store = RimeCandidatePresentationStore()
        val initial = store.publish(
            CompositionState(
                preedit = "zhong",
                candidates = listOf("中", "种"),
                inputMode = RimePinyinOutputMode.SIMPLIFIED.stableId,
            ),
        )

        assertTrue(store.accepts(initial.generation))

        val updated = store.publish(initial.state.copy(candidates = listOf("中")))
        assertFalse(store.accepts(initial.generation))
        assertTrue(store.accepts(updated.generation))

        val replacementView = requireNotNull(store.refreshForNewView())
        assertEquals(updated.state, replacementView.state)
        assertFalse(store.accepts(updated.generation))
        assertTrue(store.accepts(replacementView.generation))
    }

    @Test
    fun clearInvalidatesQueuedClickEvenWhenNoRowWasVisible() {
        val store = RimeCandidatePresentationStore()
        val presentation = store.publish(
            CompositionState(inputMode = RimePinyinOutputMode.SIMPLIFIED.stableId),
        )

        store.clear()

        assertNull(store.current)
        assertFalse(store.accepts(presentation.generation))
        store.clear()
        assertNull(store.refreshForNewView())
    }
}
