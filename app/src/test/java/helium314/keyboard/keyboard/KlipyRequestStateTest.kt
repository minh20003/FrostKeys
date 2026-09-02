// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KlipyRequestStateTest {
    @Test
    fun staleResponseCannotReplaceNewQueryOrTab() {
        val state = KlipyRequestState()
        val oldRequest = state.begin("cat", "GIF")
        val newRequest = state.begin("dog", "STICKER")

        assertFalse(state.accept(oldRequest, hasMore = true))
        assertTrue(state.accept(newRequest, hasMore = true))
        assertEquals(1, state.committedPageForTest())
    }

    @Test
    fun pageAdvancesOnlyAfterSuccessfulResponse() {
        val state = KlipyRequestState()
        val first = state.begin("xin chao", "GIF")

        // A failed request is intentionally not accepted: retry is still page one.
        assertEquals(first, state.nextPage())
        assertTrue(state.accept(first, hasMore = true))
        val second = state.nextPage()
        assertEquals(2, second?.page)
        assertTrue(state.accept(second!!, hasMore = false))
        assertNull(state.nextPage())
    }

    @Test
    fun invalidationRejectsInFlightResponse() {
        val state = KlipyRequestState()
        val request = state.begin("meme", "GIF")
        state.invalidate()

        assertFalse(state.isCurrent(request))
        assertFalse(state.accept(request, hasMore = true))
    }
}
