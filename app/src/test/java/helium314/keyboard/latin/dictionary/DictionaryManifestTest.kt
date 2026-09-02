// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.dictionary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DictionaryManifestTest {
    @Test
    fun parsesPinnedVietnameseDictionaryMetadata() {
        val manifest = DictionaryManifest.parse(
            """{"locale":"vi","version":"2023-09-16","source":"Leipzig","license":"CC-BY-4.0","sha256":"410fb85388b646b6694373e83f30052040332acd2baaeb640574d3846c7c5ea4","byteCount":128328,"formatVersion":1}"""
        )
        assertEquals("vi", manifest.locale)
        assertEquals(1, manifest.formatVersion)
    }

    @Test
    fun rejectsUnpinnedMetadata() {
        assertFailsWith<IllegalArgumentException> {
            DictionaryManifest.parse(
            """{"locale":"vi","version":"1","source":"x","license":"x","sha256":"not-a-hash","formatVersion":1}"""
        )
        }
    }
}
