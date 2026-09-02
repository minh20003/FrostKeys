// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Decoder for the compact, FrostKeys-owned Rime JNI state packet.
 *
 * The packet intentionally has no generic command field. Kotlin calls a fixed native method for
 * every allowed Pinyin action; native returns only a bounded snapshot made up of a preedit, a
 * one-shot commit result, and the current visible candidate page. Keeping the decoder here makes
 * malformed native state fail closed before it reaches an editor or candidate strip.
 *
 * Binary layout (all integer fields are unsigned little endian):
 *
 * ```text
 * u8  version (= 1)
 * u8  flags (result, previous-page, next-page)
 * u16 visible-candidate count
 * u32 page number
 * u16 preedit UTF-8 byte length; bytes
 * u16 result UTF-8 byte length; bytes (must be empty without result flag)
 * repeated candidate count: u16 UTF-8 byte length; non-empty bytes
 * ```
 */
internal object RimeWireCodec {
    const val VERSION: Int = 1
    const val MAX_PACKET_BYTES: Int = 128 * 1024
    const val MAX_TEXT_BYTES: Int = 16 * 1024
    const val MAX_CANDIDATES: Int = 64

    private const val HEADER_BYTES = 8
    private const val FLAG_HAS_RESULT = 1 shl 0
    private const val FLAG_CAN_PAGE_BACKWARD = 1 shl 1
    private const val FLAG_CAN_PAGE_FORWARD = 1 shl 2
    private const val KNOWN_FLAGS = FLAG_HAS_RESULT or FLAG_CAN_PAGE_BACKWARD or FLAG_CAN_PAGE_FORWARD

    data class State(
        val preedit: String,
        val resultText: String?,
        val candidates: List<String>,
        val page: Int,
        val canPageBackward: Boolean,
        val canPageForward: Boolean,
    )

    fun decodeState(packet: ByteArray): State {
        requirePacket(packet.size in HEADER_BYTES..MAX_PACKET_BYTES, "Rime state packet has an invalid size")
        val reader = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        requirePacket(readUnsignedByte(reader) == VERSION, "Rime state packet has an unsupported version")
        val flags = readUnsignedByte(reader)
        requirePacket(flags and KNOWN_FLAGS.inv() == 0, "Rime state packet has unknown flags")
        val candidateCount = readUnsignedShort(reader)
        requirePacket(candidateCount <= MAX_CANDIDATES, "Rime state packet has too many candidates")
        val encodedPage = readUnsignedInt(reader)
        requirePacket(encodedPage <= Int.MAX_VALUE.toLong(), "Rime state packet page is out of range")
        val page = encodedPage.toInt()
        val preedit = readUtf8(reader, "preedit", allowEmpty = true)
        val result = readUtf8(reader, "result", allowEmpty = true)
        val hasResult = flags and FLAG_HAS_RESULT != 0
        requirePacket(hasResult || result.isEmpty(), "Rime state packet has an unflagged result")
        requirePacket(!hasResult || result.isNotEmpty(), "Rime state packet has an empty flagged result")

        val candidates = buildList(candidateCount) {
            repeat(candidateCount) {
                add(readUtf8(reader, "candidate", allowEmpty = false))
            }
        }
        requirePacket(!reader.hasRemaining(), "Rime state packet has trailing bytes")
        val canPageBackward = flags and FLAG_CAN_PAGE_BACKWARD != 0
        val canPageForward = flags and FLAG_CAN_PAGE_FORWARD != 0
        requirePacket(!canPageBackward || page > 0, "Rime state packet exposes a previous page before page zero")
        requirePacket(
            candidates.isNotEmpty() || (!canPageBackward && !canPageForward && page == 0),
            "Rime state packet has paging without visible candidates",
        )
        return State(
            preedit = preedit,
            resultText = result.takeIf { hasResult },
            candidates = candidates,
            page = page,
            canPageBackward = canPageBackward,
            canPageForward = canPageForward,
        )
    }

    /** Returns a native-safe lowercase Pinyin key, or null for input that Rime must never see. */
    fun normalizedPinyinKey(key: String): Int? {
        if (key.codePointCount(0, key.length) != 1) return null
        val codePoint = key.codePointAt(0)
        return when (codePoint) {
            in 'a'.code..'z'.code -> codePoint
            in 'A'.code..'Z'.code -> codePoint + ('a'.code - 'A'.code)
            '\''.code -> codePoint
            else -> null
        }
    }

    private fun readUnsignedByte(reader: ByteBuffer): Int {
        requirePacket(reader.remaining() >= 1, "Rime state packet is truncated")
        return reader.get().toInt() and 0xff
    }

    private fun readUnsignedShort(reader: ByteBuffer): Int {
        requirePacket(reader.remaining() >= Short.SIZE_BYTES, "Rime state packet is truncated")
        return reader.short.toInt() and 0xffff
    }

    private fun readUnsignedInt(reader: ByteBuffer): Long {
        requirePacket(reader.remaining() >= Int.SIZE_BYTES, "Rime state packet is truncated")
        return reader.int.toLong() and 0xffff_ffffL
    }

    private fun readUtf8(reader: ByteBuffer, label: String, allowEmpty: Boolean): String {
        val length = readUnsignedShort(reader)
        requirePacket(length <= MAX_TEXT_BYTES, "Rime $label exceeds the packet limit")
        requirePacket(length <= reader.remaining(), "Rime state packet is truncated")
        if (!allowEmpty) requirePacket(length > 0, "Rime $label is empty")
        val bytes = ByteArray(length)
        reader.get(bytes)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            throw RimeWireFormatException("Rime $label is not valid UTF-8")
        }
    }

    private fun requirePacket(condition: Boolean, message: String) {
        if (!condition) throw RimeWireFormatException(message)
    }
}

/** A malformed or out-of-contract state packet received from the Rime JNI bridge. */
internal class RimeWireFormatException(message: String) : IllegalArgumentException(message)
