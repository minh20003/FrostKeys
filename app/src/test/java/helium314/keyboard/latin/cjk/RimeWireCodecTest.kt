// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RimeWireCodecTest {
    @Test
    fun decodesBoundedPinyinStateAndPageCapabilities() {
        val decoded = RimeWireCodec.decodeState(
            RimePacket.state(
                preedit = "nihao",
                result = "你好",
                candidates = listOf("你好", "你号"),
                page = 1,
                canPageBackward = true,
                canPageForward = true,
            ),
        )

        assertEquals("nihao", decoded.preedit)
        assertEquals("你好", decoded.resultText)
        assertEquals(listOf("你好", "你号"), decoded.candidates)
        assertEquals(1, decoded.page)
        assertTrue(decoded.canPageBackward)
        assertTrue(decoded.canPageForward)
    }

    @Test
    fun absentResultCannotMasqueradeAsACommit() {
        val decoded = RimeWireCodec.decodeState(RimePacket.state(preedit = "ni"))

        assertNull(decoded.resultText)
        assertEquals("ni", decoded.preedit)
    }

    @Test
    fun rejectsMalformedPacketsBeforeTheyCanReachTheEditor() {
        assertFailsWith<RimeWireFormatException> {
            RimeWireCodec.decodeState(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 7))
        }
        assertFailsWith<RimeWireFormatException> {
            RimeWireCodec.decodeState(RimePacket.state(preedit = "ni", unknownFlags = 0x80))
        }
        assertFailsWith<RimeWireFormatException> {
            RimeWireCodec.decodeState(RimePacket.state(preedit = "ni", result = "", forceResultFlag = true))
        }
        assertFailsWith<RimeWireFormatException> {
            RimeWireCodec.decodeState(RimePacket.state(candidates = listOf("候选"), page = 0, canPageBackward = true))
        }
        assertFailsWith<RimeWireFormatException> {
            RimeWireCodec.decodeState(RimePacket.malformedUtf8Preedit())
        }
    }

    @Test
    fun normalizesOnlyOneAsciiPinyinKeyOrDelimiter() {
        assertEquals('a'.code, RimeWireCodec.normalizedPinyinKey("a"))
        assertEquals('n'.code, RimeWireCodec.normalizedPinyinKey("N"))
        assertEquals('\''.code, RimeWireCodec.normalizedPinyinKey("'"))
        assertNull(RimeWireCodec.normalizedPinyinKey("ni"))
        assertNull(RimeWireCodec.normalizedPinyinKey("你"))
        assertNull(RimeWireCodec.normalizedPinyinKey("\ud800"))
    }

    @Test
    fun flagsAndPageBoundsRemainConsistent() {
        val first = RimeWireCodec.decodeState(
            RimePacket.state(candidates = listOf("你"), page = 0, canPageForward = true),
        )
        assertFalse(first.canPageBackward)
        assertTrue(first.canPageForward)
    }
}

/** Tiny packet writer used only to make deterministic unit-test fixtures for the native contract. */
internal object RimePacket {
    fun state(
        preedit: String = "",
        result: String? = null,
        candidates: List<String> = emptyList(),
        page: Int = 0,
        canPageBackward: Boolean = false,
        canPageForward: Boolean = false,
        unknownFlags: Int = 0,
        forceResultFlag: Boolean = false,
    ): ByteArray {
        require(page >= 0)
        val flags = unknownFlags or
            (if (result != null || forceResultFlag) 1 else 0) or
            (if (canPageBackward) 2 else 0) or
            (if (canPageForward) 4 else 0)
        return ByteArrayOutputStream().apply {
            write(1)
            write(flags)
            writeU16(candidates.size)
            writeU32(page)
            writeText(preedit)
            writeText(result.orEmpty())
            candidates.forEach { candidate -> writeText(candidate) }
        }.toByteArray()
    }

    fun malformedUtf8Preedit(): ByteArray = ByteArrayOutputStream().apply {
        write(1)
        write(0)
        writeU16(0)
        writeU32(0)
        writeU16(1)
        write(0x80)
        writeU16(0)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeText(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeU16(bytes.size)
        write(bytes)
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeU32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
