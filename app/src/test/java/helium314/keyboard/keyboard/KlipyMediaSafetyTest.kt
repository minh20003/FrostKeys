// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.File

class KlipyMediaSafetyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun onlyExpectedMimeTypesAndDimensionsAreAccepted() {
        assertTrue(KlipyMediaSafety.acceptsMimeType("image/gif; charset=binary", KlipyMediaSafety.DownloadKind.GIF))
        assertTrue(KlipyMediaSafety.acceptsMimeType("image/webp", KlipyMediaSafety.DownloadKind.STICKER_SOURCE))
        assertFalse(KlipyMediaSafety.acceptsMimeType("image/png", KlipyMediaSafety.DownloadKind.GIF))
        assertFalse(KlipyMediaSafety.acceptsMimeType(null, KlipyMediaSafety.DownloadKind.STICKER_SOURCE))
        assertTrue(KlipyMediaSafety.hasSafeDimensions(2048, 2048))
        assertFalse(KlipyMediaSafety.hasSafeDimensions(2049, 50))
        assertEquals(24, KlipyMediaSafety.maxFrameCount(lowRamDevice = false))
        assertEquals(12, KlipyMediaSafety.maxFrameCount(lowRamDevice = true))
    }

    @Test
    fun cacheKeyCannotContainProviderPathSegments() {
        val malicious = KlipyMediaSafety.cacheKey(KlipyMediaSafety.DownloadKind.GIF, "../../private/secret")
        val ordinary = KlipyMediaSafety.cacheKey(KlipyMediaSafety.DownloadKind.GIF, "123")

        assertEquals(32, malicious.length)
        assertFalse(malicious.contains('/'))
        assertFalse(malicious.contains('\\'))
        assertNotEquals(malicious, ordinary)
    }

    @Test
    fun oversizedOrCancelledCopyNeverReplacesExistingFileAndCleansTemporaryFile() {
        val target = File(temporaryFolder.root, "media.gif")
        target.writeText("known-good")
        val oversized = ByteArrayInputStream(ByteArray(11) { 1 })

        try {
            KlipyAtomicMediaWriter.copyIntoPlace(oversized, target, maxBytes = 10)
            throw AssertionError("Expected oversized media to fail")
        } catch (_: IOException) {
            // Expected.
        }
        assertEquals("known-good", target.readText())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.endsWith(".partial") })

        val cancellingInput = object : InputStream() {
            private val source = ByteArrayInputStream(ByteArray(4) { 2 })
            override fun read(): Int = source.read()
            override fun read(buffer: ByteArray, off: Int, len: Int): Int = source.read(buffer, off, len)
        }
        try {
            KlipyAtomicMediaWriter.copyIntoPlace(cancellingInput, target, beforeChunk = { throw InterruptedException() })
            throw AssertionError("Expected cancellation callback to fail")
        } catch (_: InterruptedException) {
            // Expected.
        }
        assertEquals("known-good", target.readText())
        assertTrue(temporaryFolder.root.listFiles().orEmpty().none { it.name.endsWith(".partial") })
    }
}
