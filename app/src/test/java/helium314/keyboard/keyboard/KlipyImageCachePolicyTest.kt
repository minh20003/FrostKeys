// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import org.junit.Test
import kotlin.test.assertEquals

class KlipyImageCachePolicyTest {
    @Test
    fun memoryCacheUsesEightPercentUntilTheThirtyTwoMiBCap() {
        val mib = 1024L * 1024L

        assertEquals((256L * mib * 8L / 100L).toInt(), KlipyImageCachePolicy.memoryCacheBytes(256L * mib))
        assertEquals(32L * mib, KlipyImageCachePolicy.memoryCacheBytes(2_048L * mib).toLong())
        assertEquals(32L * mib, KlipyImageCachePolicy.memoryCacheBytes(Long.MAX_VALUE).toLong())
    }

    @Test
    fun tinyOrInvalidHeapStillGetsAValidBoundedCache() {
        assertEquals(1, KlipyImageCachePolicy.memoryCacheBytes(0L))
        assertEquals(1, KlipyImageCachePolicy.memoryCacheBytes(-1L))
        assertEquals(150L * 1024L * 1024L, KlipyImageCachePolicy.DISK_CACHE_BYTES)
    }
}
