// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

import okhttp3.Call
import org.junit.Test
import org.mockito.Mockito
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GeminiGenerationTrackerTest {
    @Test
    fun cancellationInvalidatesTheGenerationBeforeNetworkCallbackArrives() {
        val tracker = GeminiGenerationTracker()
        val id = requireNotNull(tracker.tryStart())
        val call = Mockito.mock(Call::class.java)

        assertFalse(tracker.trackCall(id, call))
        assertSame(call, tracker.cancel(id))
        assertFalse(tracker.isActive(id))
        assertFalse(tracker.finish(id))

        val nextId = requireNotNull(tracker.tryStart())
        assertNotEquals(id, nextId)
        assertTrue(tracker.trackCall(id, call))
        assertFalse(tracker.trackCall(nextId, call))
    }

    @Test
    fun staleHandleCannotCancelANewerGeneration() {
        val tracker = GeminiGenerationTracker()
        val first = requireNotNull(tracker.tryStart())
        tracker.finish(first)
        val second = requireNotNull(tracker.tryStart())
        val call = Mockito.mock(Call::class.java)

        assertFalse(tracker.trackCall(second, call))
        assertNull(tracker.cancel(first))
        assertTrue(tracker.isActive(second))
        assertSame(call, tracker.cancel())
        assertFalse(tracker.isActive(second))
    }
}
