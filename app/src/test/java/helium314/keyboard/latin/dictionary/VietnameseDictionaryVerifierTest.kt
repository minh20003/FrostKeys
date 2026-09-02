// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VietnameseDictionaryVerifierTest {
    private val dictionary = "Vietnamese offline dictionary fixture".encodeToByteArray()

    @Test
    fun acceptsExactPinnedVietnameseDictionary() {
        assertTrue(VietnameseDictionaryVerifier.matches(manifestFor(dictionary), ByteArrayInputStream(dictionary)))
    }

    @Test
    fun rejectsTamperedOrWrongSizedDictionary() {
        assertFalse(
            VietnameseDictionaryVerifier.matches(
                manifestFor(dictionary),
                ByteArrayInputStream("Vietnamese offline dictionary fixture!".encodeToByteArray()),
            ),
        )
        assertFalse(
            VietnameseDictionaryVerifier.matches(
                manifestFor(dictionary, byteCount = dictionary.size.toLong() - 1),
                ByteArrayInputStream(dictionary),
            ),
        )
    }

    @Test
    fun rejectsNonVietnameseOrUnknownManifestFormat() {
        assertFalse(
            VietnameseDictionaryVerifier.matches(
                manifestFor(dictionary).copy(locale = "en-US"),
                ByteArrayInputStream(dictionary),
            ),
        )
        assertFalse(
            VietnameseDictionaryVerifier.matches(
                manifestFor(dictionary).copy(formatVersion = 2),
                ByteArrayInputStream(dictionary),
            ),
        )
    }

    private fun manifestFor(bytes: ByteArray, byteCount: Long = bytes.size.toLong()): DictionaryManifest =
        DictionaryManifest(
            locale = "vi",
            version = "fixture",
            source = "test",
            license = "test",
            sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            byteCount = byteCount,
            formatVersion = 1,
        )
}
