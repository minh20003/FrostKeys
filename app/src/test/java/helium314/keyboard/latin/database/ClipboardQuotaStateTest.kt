// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipboardQuotaStateTest {
    @Test
    fun pinnedQuotaCountsBothItemAndImageBoundaries() {
        assertFalse(ClipboardDao.pinnedQuotaStateFor(199, 100L * 1024L * 1024L - 1L).isQuotaReached)
        assertTrue(ClipboardDao.pinnedQuotaStateFor(200, 0L).entryQuotaReached)
        assertTrue(ClipboardDao.pinnedQuotaStateFor(0, 100L * 1024L * 1024L).imageQuotaReached)
    }
}
