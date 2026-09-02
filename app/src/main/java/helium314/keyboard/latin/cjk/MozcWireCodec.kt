// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Bounded protobuf boundary for the FrostKeys-owned Mozc JNI bridge.
 *
 * Mozc's generated protobuf classes are intentionally not linked into the Android application:
 * they would pull a second Java protobuf runtime into the IME and, more importantly, make it too
 * easy for an arbitrary caller to manufacture server commands. This codec exposes only the
 * reviewed request shapes accepted by `frostkeys_mozc_jni.cc`: textual keys, backspace, submit,
 * revert, candidate selection, candidate paging, and a small reviewed set of input-mode
 * transitions. It never accepts a raw command byte array.
 *
 * The native bridge independently limits a serialized `commands.Command` to 256 KiB. Repeating
 * that limit here protects Java heap/CPU before a malformed response reaches JNI or the candidate
 * strip. Nested messages, field count, string size, candidate count, and UTF-8 validity are also
 * bounded because native output is still an untrusted process boundary from the IME's perspective.
 */
internal object MozcWireCodec {
    const val MAX_COMMAND_BYTES = 256 * 1024

    private const val MAX_KEY_BYTES = 8 * 1024
    private const val MAX_TEXT_BYTES = 64 * 1024
    private const val MAX_FIELDS = 4_096
    private const val MAX_NESTING = 16
    private const val MAX_CANDIDATES_PER_RESPONSE = 256
    private const val MAX_CANDIDATE_TOTAL = 100_000
    private const val DEFAULT_PAGE_SIZE = 9

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LENGTH_DELIMITED = 2
    private const val WIRE_START_GROUP = 3
    private const val WIRE_END_GROUP = 4
    private const val WIRE_FIXED32 = 5

    private const val INPUT_SEND_KEY = 3
    private const val INPUT_SEND_COMMAND = 5
    private const val SPECIAL_KEY_BACKSPACE = 12

    private const val SESSION_REVERT = 1
    private const val SESSION_SUBMIT = 2
    private const val SESSION_SELECT_CANDIDATE = 3
    private const val SESSION_CONVERT_PREV_PAGE = 20
    private const val SESSION_CONVERT_NEXT_PAGE = 21
    private const val SESSION_TURN_ON_IME = 22
    private const val COMPOSITION_HIRAGANA = 1
    private const val COMPOSITION_FULL_KATAKANA = 2
    private const val COMPOSITION_HALF_ASCII = 3

    /** Candidate data needed to map a visible strip index back to Mozc's stable candidate id. */
    internal data class Candidate(
        val id: Int?,
        val index: Int,
        val value: String,
    )

    /** The deliberately small part of `commands.Output` the IME is allowed to consume. */
    internal data class Output(
        val preedit: String,
        val preeditCursor: Int?,
        val resultText: String?,
        val candidates: List<Candidate>,
        val page: Int,
        val canPageBackward: Boolean,
        val canPageForward: Boolean,
    )

    /** Encodes `Command { input { type: SEND_KEY, key { key_string } } }`. */
    fun sendKey(key: String): ByteArray {
        val keyBytes = checkedUtf8(key, MAX_KEY_BYTES, "Mozc key")
        val keyEvent = Writer().apply {
            writeLengthDelimited(fieldNumber = 5, value = keyBytes)
        }.toByteArray()
        return commandWithInput(
            Writer().apply {
                writeVarintField(fieldNumber = 1, value = INPUT_SEND_KEY.toLong())
                writeLengthDelimited(fieldNumber = 3, value = keyEvent)
            }.toByteArray(),
        )
    }

    /** Encodes `SEND_KEY` with Mozc's `KeyEvent.SpecialKey.BACKSPACE`. */
    fun backspace(): ByteArray {
        val keyEvent = Writer().apply {
            writeVarintField(fieldNumber = 3, value = SPECIAL_KEY_BACKSPACE.toLong())
        }.toByteArray()
        return commandWithInput(
            Writer().apply {
                writeVarintField(fieldNumber = 1, value = INPUT_SEND_KEY.toLong())
                writeLengthDelimited(fieldNumber = 3, value = keyEvent)
            }.toByteArray(),
        )
    }

    /** Encodes `SEND_COMMAND { SUBMIT }`; it is not an arbitrary command escape hatch. */
    fun submit(): ByteArray = sessionCommand(SESSION_SUBMIT)

    /** Encodes `SEND_COMMAND { REVERT }`; it is not an arbitrary command escape hatch. */
    fun revert(): ByteArray = sessionCommand(SESSION_REVERT)

    /** Encodes `SEND_COMMAND { SELECT_CANDIDATE, id }` for a parsed native candidate id. */
    fun selectCandidate(candidateId: Int): ByteArray = sessionCommand(
        type = SESSION_SELECT_CANDIDATE,
        candidateId = candidateId,
    )

    /** Encodes `SEND_COMMAND { CONVERT_PREV_PAGE }`. */
    fun previousPage(): ByteArray = sessionCommand(SESSION_CONVERT_PREV_PAGE)

    /** Encodes `SEND_COMMAND { CONVERT_NEXT_PAGE }`. */
    fun nextPage(): ByteArray = sessionCommand(SESSION_CONVERT_NEXT_PAGE)

    /**
     * Encodes one of the fixed `TURN_ON_IME` mode transitions approved for the Japanese strip.
     *
     * A newly created Mozc session can remain in DIRECT mode. The UI can select only the enum
     * values here; it cannot serialize arbitrary `SessionCommand` bytes or native enum values.
     */
    fun activate(inputMode: MozcInputMode): ByteArray = sessionCommand(
        type = SESSION_TURN_ON_IME,
        compositionMode = when (inputMode) {
            MozcInputMode.HIRAGANA -> COMPOSITION_HIRAGANA
            MozcInputMode.KATAKANA -> COMPOSITION_FULL_KATAKANA
            MozcInputMode.LATIN -> COMPOSITION_HALF_ASCII
        },
    )

    /** Compatibility convenience for the initial Romaji-to-Hiragana startup path. */
    fun activateHiragana(): ByteArray = activate(MozcInputMode.HIRAGANA)

    /**
     * Decodes the `Output` field returned by the JNI bridge.
     *
     * The bridge echoes our `Input` in `Command`, so this parser deliberately ignores all fields
     * except top-level `output = 2`; it cannot be used as a general Mozc protobuf decoder.
     */
    fun decodeOutput(command: ByteArray): Output {
        require(command.isNotEmpty() && command.size <= MAX_COMMAND_BYTES) {
            "Mozc response exceeds the allowed size"
        }
        val reader = Reader(command)
        var output: Output? = null
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            when {
                tag.fieldNumber == 2 && tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    protocolRequire(output == null, "Mozc response contains multiple outputs")
                    output = parseOutput(reader.readSubMessage())
                }

                tag.wireType == WIRE_END_GROUP -> {
                    throw MozcWireFormatException("Unexpected protobuf end-group in Mozc command")
                }

                else -> reader.skipValue(tag)
            }
        }
        return output ?: throw MozcWireFormatException("Mozc response has no output")
    }

    private fun sessionCommand(
        type: Int,
        candidateId: Int? = null,
        compositionMode: Int? = null,
    ): ByteArray {
        val command = Writer().apply {
            writeVarintField(fieldNumber = 1, value = type.toLong())
            candidateId?.let { writeVarintField(fieldNumber = 2, value = it.toLong()) }
            compositionMode?.let { writeVarintField(fieldNumber = 3, value = it.toLong()) }
        }.toByteArray()
        return commandWithInput(
            Writer().apply {
                writeVarintField(fieldNumber = 1, value = INPUT_SEND_COMMAND.toLong())
                writeLengthDelimited(fieldNumber = 4, value = command)
            }.toByteArray(),
        )
    }

    private fun commandWithInput(input: ByteArray): ByteArray = Writer().apply {
        writeLengthDelimited(fieldNumber = 1, value = input)
    }.toByteArray()

    private fun parseOutput(reader: Reader): Output {
        var preedit: Preedit? = null
        var result: String? = null
        var sawResult = false
        var candidateWindow: CandidateWindow? = null
        var errorCode = 0
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            when {
                tag.fieldNumber == 4 && tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    protocolRequire(!sawResult, "Mozc output contains multiple results")
                    result = parseResult(reader.readSubMessage())
                    sawResult = true
                }

                tag.fieldNumber == 5 && tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    protocolRequire(preedit == null, "Mozc output contains multiple preedits")
                    preedit = parsePreedit(reader.readSubMessage())
                }

                tag.fieldNumber == 6 && tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    protocolRequire(candidateWindow == null, "Mozc output contains multiple candidate windows")
                    candidateWindow = parseCandidateWindow(reader.readSubMessage())
                }

                tag.fieldNumber == 11 && tag.wireType == WIRE_VARINT -> {
                    errorCode = reader.readUInt32()
                }

                tag.wireType == WIRE_END_GROUP -> {
                    throw MozcWireFormatException("Unexpected protobuf end-group in Mozc output")
                }

                else -> reader.skipValue(tag)
            }
        }
        protocolRequire(errorCode == 0, "Mozc session returned an error")
        val candidates = candidateWindow?.candidates.orEmpty()
        return Output(
            preedit = preedit?.value.orEmpty(),
            preeditCursor = preedit?.cursor,
            resultText = result,
            candidates = candidates,
            page = candidateWindow?.page ?: 0,
            canPageBackward = candidateWindow?.canPageBackward ?: false,
            canPageForward = candidateWindow?.canPageForward ?: false,
        )
    }

    private fun parseResult(reader: Reader): String? {
        var type: Int? = null
        var value: String? = null
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            when {
                tag.fieldNumber == 1 && tag.wireType == WIRE_VARINT -> {
                    protocolRequire(type == null, "Mozc result contains multiple types")
                    type = reader.readUInt32()
                }

                tag.fieldNumber == 2 && tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    protocolRequire(value == null, "Mozc result contains multiple values")
                    value = reader.readString(MAX_TEXT_BYTES, "Mozc result")
                }

                tag.wireType == WIRE_END_GROUP -> {
                    throw MozcWireFormatException("Unexpected protobuf end-group in Mozc result")
                }

                else -> reader.skipValue(tag)
            }
        }
        protocolRequire(type != null && value != null, "Mozc result is incomplete")
        val resultType = type ?: throw MozcWireFormatException("Mozc result is incomplete")
        val resultValue = value ?: throw MozcWireFormatException("Mozc result is incomplete")
        return when (resultType) {
            0 -> null // ResultType.NONE
            1 -> resultValue // ResultType.STRING
            else -> throw MozcWireFormatException("Mozc result has an unsupported type")
        }
    }

    private data class Preedit(
        val value: String,
        val cursor: Int,
    )

    private fun parsePreedit(reader: Reader): Preedit {
        var cursor: Int? = null
        val values = StringBuilder()
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            when {
                tag.fieldNumber == 1 && tag.wireType == WIRE_VARINT -> {
                    protocolRequire(cursor == null, "Mozc preedit contains multiple cursors")
                    cursor = reader.readUInt32()
                }

                tag.fieldNumber == 2 && tag.wireType == WIRE_START_GROUP -> {
                    appendBounded(values, parsePreeditSegment(reader))
                }

                tag.wireType == WIRE_END_GROUP -> {
                    throw MozcWireFormatException("Unexpected protobuf end-group in Mozc preedit")
                }

                else -> reader.skipValue(tag)
            }
        }
        val preeditValue = values.toString()
        val preeditCursor = cursor ?: throw MozcWireFormatException("Mozc preedit has no cursor")
        protocolRequire(
            preeditCursor <= preeditValue.codePointCount(0, preeditValue.length),
            "Mozc preedit cursor is outside its value",
        )
        return Preedit(value = preeditValue, cursor = preeditCursor)
    }

    /** Parses `Preedit.Segment`, a proto2 group on field 2. */
    private fun parsePreeditSegment(reader: Reader): String {
        var sawAnnotation = false
        var value: String? = null
        var valueLength: Int? = null
        var sawEndGroup = false
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            if (tag.wireType == WIRE_END_GROUP) {
                protocolRequire(tag.fieldNumber == 2, "Mozc preedit segment has the wrong end-group")
                sawEndGroup = true
                break
            }
            when {
                tag.fieldNumber == 3 && tag.wireType == WIRE_VARINT -> {
                    reader.readUInt32()
                    sawAnnotation = true
                }

                tag.fieldNumber == 4 && tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    protocolRequire(value == null, "Mozc preedit segment contains multiple values")
                    value = reader.readString(MAX_TEXT_BYTES, "Mozc preedit")
                }

                tag.fieldNumber == 5 && tag.wireType == WIRE_VARINT -> {
                    protocolRequire(valueLength == null, "Mozc preedit segment contains multiple lengths")
                    valueLength = reader.readUInt32()
                }

                else -> reader.skipValue(tag)
            }
        }
        protocolRequire(
            sawEndGroup && sawAnnotation && value != null && valueLength != null,
            "Mozc preedit segment is incomplete",
        )
        val segmentValue = value ?: throw MozcWireFormatException("Mozc preedit segment is incomplete")
        val segmentLength = valueLength
            ?: throw MozcWireFormatException("Mozc preedit segment is incomplete")
        protocolRequire(
            segmentValue.codePointCount(0, segmentValue.length) == segmentLength,
            "Mozc preedit segment length does not match its value",
        )
        return segmentValue
    }

    private data class CandidateWindow(
        val candidates: List<Candidate>,
        val page: Int,
        val canPageBackward: Boolean,
        val canPageForward: Boolean,
    )

    private fun parseCandidateWindow(reader: Reader): CandidateWindow {
        var totalSize: Int? = null
        var sawPosition = false
        var pageSize = DEFAULT_PAGE_SIZE
        val candidates = mutableListOf<Candidate>()
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            when {
                tag.fieldNumber == 2 && tag.wireType == WIRE_VARINT -> {
                    protocolRequire(totalSize == null, "Mozc candidate window contains multiple sizes")
                    totalSize = reader.readUInt32()
                    protocolRequire(totalSize <= MAX_CANDIDATE_TOTAL, "Mozc candidate window is too large")
                }

                tag.fieldNumber == 3 && tag.wireType == WIRE_START_GROUP -> {
                    protocolRequire(
                        candidates.size < MAX_CANDIDATES_PER_RESPONSE,
                        "Mozc candidate response has too many candidates",
                    )
                    candidates += parseCandidate(reader)
                }

                tag.fieldNumber == 6 && tag.wireType == WIRE_VARINT -> {
                    reader.readUInt32()
                    sawPosition = true
                }

                tag.fieldNumber == 18 && tag.wireType == WIRE_VARINT -> {
                    pageSize = reader.readUInt32()
                }

                tag.wireType == WIRE_END_GROUP -> {
                    throw MozcWireFormatException("Unexpected protobuf end-group in Mozc candidate window")
                }

                else -> reader.skipValue(tag)
            }
        }
        protocolRequire(totalSize != null && sawPosition, "Mozc candidate window is incomplete")
        val expectedTotalSize = totalSize
            ?: throw MozcWireFormatException("Mozc candidate window is incomplete")
        protocolRequire(pageSize in 1..MAX_CANDIDATES_PER_RESPONSE, "Mozc candidate page size is invalid")
        val ordered = candidates.sortedBy(Candidate::index)
        protocolRequire(ordered.map(Candidate::index).distinct().size == ordered.size, "Mozc candidate indexes repeat")
        ordered.forEach { candidate ->
            protocolRequire(candidate.index < expectedTotalSize, "Mozc candidate index is outside its window")
        }
        val page = ordered.firstOrNull()?.index?.div(pageSize) ?: 0
        return CandidateWindow(
            candidates = ordered,
            page = page,
            canPageBackward = page > 0,
            // Use a wide intermediate even though input bounds are checked. It makes the page
            // calculation safe if Mozc's protocol expands its maximum candidate count later.
            canPageForward = (page.toLong() + 1L) * pageSize.toLong() < expectedTotalSize.toLong(),
        )
    }

    /** Parses `CandidateWindow.Candidate`, a proto2 group on field 3. */
    private fun parseCandidate(reader: Reader): Candidate {
        var index: Int? = null
        var value: String? = null
        var id: Int? = null
        var sawEndGroup = false
        while (!reader.isAtEnd) {
            val tag = reader.readTag()
            if (tag.wireType == WIRE_END_GROUP) {
                protocolRequire(tag.fieldNumber == 3, "Mozc candidate has the wrong end-group")
                sawEndGroup = true
                break
            }
            when {
                tag.fieldNumber == 4 && tag.wireType == WIRE_VARINT -> {
                    protocolRequire(index == null, "Mozc candidate contains multiple indexes")
                    index = reader.readUInt32()
                }

                tag.fieldNumber == 5 && tag.wireType == WIRE_LENGTH_DELIMITED -> {
                    protocolRequire(value == null, "Mozc candidate contains multiple values")
                    value = reader.readString(MAX_TEXT_BYTES, "Mozc candidate")
                }

                tag.fieldNumber == 9 && tag.wireType == WIRE_VARINT -> {
                    protocolRequire(id == null, "Mozc candidate contains multiple ids")
                    id = reader.readInt32()
                }

                else -> reader.skipValue(tag)
            }
        }
        protocolRequire(sawEndGroup && index != null && value != null, "Mozc candidate is incomplete")
        return Candidate(
            id = id,
            index = index ?: throw MozcWireFormatException("Mozc candidate is incomplete"),
            value = value ?: throw MozcWireFormatException("Mozc candidate is incomplete"),
        )
    }

    private fun appendBounded(builder: StringBuilder, value: String) {
        protocolRequire(builder.length + value.length <= MAX_TEXT_BYTES, "Mozc preedit is too large")
        builder.append(value)
    }

    private fun checkedUtf8(value: String, maximumBytes: Int, label: String): ByteArray {
        require(value.isNotEmpty()) { "$label must not be empty" }
        require(isWellFormedUtf16(value)) { "$label has an invalid surrogate" }
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maximumBytes) { "$label exceeds the allowed size" }
        return bytes
    }

    private fun isWellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val unit = value[index]
            when {
                unit.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                    index += 2
                }

                unit.isLowSurrogate() -> return false
                else -> index++
            }
        }
        return true
    }

    private class Writer {
        private val output = ByteArrayOutputStream()

        fun writeVarintField(fieldNumber: Int, value: Long) {
            writeTag(fieldNumber, WIRE_VARINT)
            writeVarint(value)
        }

        fun writeLengthDelimited(fieldNumber: Int, value: ByteArray) {
            writeTag(fieldNumber, WIRE_LENGTH_DELIMITED)
            writeVarint(value.size.toLong())
            output.write(value)
        }

        fun toByteArray(): ByteArray {
            val bytes = output.toByteArray()
            require(bytes.isNotEmpty() && bytes.size <= MAX_COMMAND_BYTES) {
                "Mozc command exceeds the allowed size"
            }
            return bytes
        }

        private fun writeTag(fieldNumber: Int, wireType: Int) {
            require(fieldNumber in 1..0x1fff_ffff) { "Invalid protobuf field" }
            require(wireType in WIRE_VARINT..WIRE_FIXED32) { "Invalid protobuf wire type" }
            writeVarint((fieldNumber.toLong() shl 3) or wireType.toLong())
        }

        private fun writeVarint(value: Long) {
            var remaining = value
            do {
                val next = (remaining and 0x7fL).toInt()
                remaining = remaining ushr 7
                output.write(if (remaining == 0L) next else next or 0x80)
            } while (remaining != 0L)
        }
    }

    private data class Tag(
        val fieldNumber: Int,
        val wireType: Int,
    )

    private class ParseBudget(
        private var remainingFields: Int = MAX_FIELDS,
    ) {
        fun consumeField() {
            protocolRequire(remainingFields > 0, "Mozc protobuf has too many fields")
            remainingFields--
        }
    }

    private class Reader(
        private val bytes: ByteArray,
        private var position: Int = 0,
        private val limit: Int = bytes.size,
        private val depth: Int = 0,
        private val budget: ParseBudget = ParseBudget(),
    ) {
        init {
            protocolRequire(position in 0..limit && limit <= bytes.size, "Invalid Mozc protobuf bounds")
            protocolRequire(depth <= MAX_NESTING, "Mozc protobuf is nested too deeply")
        }

        val isAtEnd: Boolean
            get() = position == limit

        fun readTag(): Tag {
            protocolRequire(!isAtEnd, "Unexpected end of Mozc protobuf")
            budget.consumeField()
            val encoded = readVarint()
            val fieldNumber = (encoded ushr 3).toInt()
            val wireType = (encoded and 7L).toInt()
            protocolRequire(fieldNumber > 0, "Mozc protobuf has an invalid field number")
            protocolRequire(wireType in WIRE_VARINT..WIRE_FIXED32, "Mozc protobuf has an invalid wire type")
            return Tag(fieldNumber, wireType)
        }

        fun readUInt32(): Int {
            val value = readVarint()
            protocolRequire(value >= 0 && value <= 0xffff_ffffL, "Mozc protobuf integer is out of range")
            protocolRequire(value <= Int.MAX_VALUE.toLong(), "Mozc protobuf integer exceeds app limits")
            return value.toInt()
        }

        fun readInt32(): Int = readVarint().toInt()

        fun readSubMessage(): Reader {
            val length = readLength()
            protocolRequire(depth < MAX_NESTING, "Mozc protobuf is nested too deeply")
            val start = position
            position += length
            return Reader(bytes, start, start + length, depth + 1, budget)
        }

        fun readString(maximumBytes: Int, label: String): String {
            val length = readLength()
            protocolRequire(length <= maximumBytes, "$label exceeds the allowed size")
            val start = position
            position += length
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, length))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw MozcWireFormatException("$label is not valid UTF-8")
            }
        }

        fun skipValue(tag: Tag) {
            when (tag.wireType) {
                WIRE_VARINT -> readVarint()
                WIRE_FIXED64 -> skipBytes(8)
                WIRE_LENGTH_DELIMITED -> skipBytes(readLength())
                WIRE_START_GROUP -> skipGroup(tag.fieldNumber, groupDepth = 1)
                WIRE_END_GROUP -> throw MozcWireFormatException("Unexpected protobuf end-group")
                WIRE_FIXED32 -> skipBytes(4)
                else -> throw MozcWireFormatException("Mozc protobuf has an invalid wire type")
            }
        }

        private fun skipGroup(expectedEndField: Int, groupDepth: Int) {
            protocolRequire(depth + groupDepth <= MAX_NESTING, "Mozc protobuf is nested too deeply")
            while (!isAtEnd) {
                val tag = readTag()
                if (tag.wireType == WIRE_END_GROUP) {
                    protocolRequire(tag.fieldNumber == expectedEndField, "Mozc protobuf group end does not match")
                    return
                }
                if (tag.wireType == WIRE_START_GROUP) {
                    skipGroup(tag.fieldNumber, groupDepth + 1)
                } else {
                    skipValue(tag)
                }
            }
            throw MozcWireFormatException("Mozc protobuf group is unterminated")
        }

        private fun readLength(): Int {
            val rawLength = readVarint()
            protocolRequire(rawLength >= 0 && rawLength <= Int.MAX_VALUE.toLong(), "Mozc protobuf length is invalid")
            val length = rawLength.toInt()
            protocolRequire(length <= limit - position, "Mozc protobuf length exceeds its message")
            return length
        }

        private fun skipBytes(count: Int) {
            protocolRequire(count >= 0 && count <= limit - position, "Mozc protobuf is truncated")
            position += count
        }

        private fun readVarint(): Long {
            var value = 0L
            for (byteIndex in 0 until 10) {
                protocolRequire(position < limit, "Mozc protobuf is truncated")
                val next = bytes[position++].toInt() and 0xff
                if (byteIndex == 9) {
                    protocolRequire((next and 0xfe) == 0, "Mozc protobuf varint is too large")
                }
                value = value or ((next and 0x7f).toLong() shl (byteIndex * 7))
                if ((next and 0x80) == 0) return value
            }
            throw MozcWireFormatException("Mozc protobuf varint is too large")
        }
    }

    private fun protocolRequire(condition: Boolean, message: String) {
        if (!condition) throw MozcWireFormatException(message)
    }
}

/** A malformed or out-of-contract Mozc protobuf response. */
internal class MozcWireFormatException(message: String) : IllegalArgumentException(message)
