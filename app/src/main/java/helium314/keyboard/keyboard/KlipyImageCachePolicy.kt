// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

/** One bounded cache policy shared by every Klipy thumbnail adapter. */
internal object KlipyImageCachePolicy {
    const val DISK_CACHE_BYTES = 150L * 1024L * 1024L
    private const val MAX_MEMORY_CACHE_BYTES = 32L * 1024L * 1024L
    private const val MEMORY_CACHE_PERCENT = 8L

    fun memoryCacheBytes(maxHeapBytes: Long): Int {
        // Clamp before multiplying so a malformed runtime value cannot overflow a Long.
        val heapRelevantToCap = maxHeapBytes.coerceIn(
            0L,
            MAX_MEMORY_CACHE_BYTES * 100L / MEMORY_CACHE_PERCENT,
        )
        val requested = heapRelevantToCap * MEMORY_CACHE_PERCENT / 100L
        return requested.coerceIn(1L, MAX_MEMORY_CACHE_BYTES).toInt()
    }
}
