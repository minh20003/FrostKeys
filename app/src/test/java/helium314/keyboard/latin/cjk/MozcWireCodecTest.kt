// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cjk

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MozcWireCodecTest {
    @Test
    fun encodesOnlyReviewedSendKeyAndSessionCommands() {
        assertEquals(
            "0a0708031a032a0161",
            MozcWireCodec.sendKey("a").hex(),
        )
        assertEquals(
            "0a0608031a02180c",
            MozcWireCodec.backspace().hex(),
        )
        assertEquals(
            "0a06080522020802",
            MozcWireCodec.submit().hex(),
        )
        assertEquals(
            "0a06080522020801",
            MozcWireCodec.revert().hex(),
        )
        assertEquals(
            "0a08080522040803102a",
            MozcWireCodec.selectCandidate(42).hex(),
        )
        assertEquals(
            "0a06080522020814",
            MozcWireCodec.previousPage().hex(),
        )
        assertEquals(
            "0a06080522020815",
            MozcWireCodec.nextPage().hex(),
        )
        assertEquals(
            "0a080805220408161801",
            MozcWireCodec.activateHiragana().hex(),
        )
        assertEquals(
            "0a080805220408161802",
            MozcWireCodec.activate(MozcInputMode.KATAKANA).hex(),
        )
        assertEquals(
            "0a080805220408161803",
            MozcWireCodec.activate(MozcInputMode.LATIN).hex(),
        )
    }

    @Test
    fun candidateSelectionPreservesMozcsSignedCandidateId() {
        // `SessionCommand.id` is int32. A negative id must use the protobuf ten-byte signed
        // varint form rather than becoming an unrelated positive list index.
        assertEquals(
            "0a110805220d080310ffffffffffffffffff01",
            MozcWireCodec.selectCandidate(-1).hex(),
        )
    }

    @Test
    fun decodesPreeditResultCandidatesAndCandidatePage() {
        val response = MozcTestProto.commandResponse(
            preedit = "かな",
            result = "仮名",
            candidates = listOf(
                MozcTestProto.Candidate(id = 91, index = 9, value = "仮名"),
                MozcTestProto.Candidate(id = -8, index = 10, value = "カナ"),
            ),
            totalCandidateCount = 20,
            pageSize = 9,
            includeUnknownTopLevelField = true,
        )

        val output = MozcWireCodec.decodeOutput(response)

        assertEquals("かな", output.preedit)
        assertEquals(0, output.preeditCursor)
        assertEquals("仮名", output.resultText)
        assertEquals(1, output.page)
        assertTrue(output.canPageBackward)
        assertTrue(output.canPageForward)
        assertEquals(listOf("仮名", "カナ"), output.candidates.map { it.value })
        assertEquals(listOf(91, -8), output.candidates.map { it.id })
        assertEquals(listOf(9, 10), output.candidates.map { it.index })
    }

    @Test
    fun resultNoneDoesNotMasqueradeAsCommittedText() {
        val response = MozcTestProto.commandResponse(result = "", resultType = 0)
        assertNull(MozcWireCodec.decodeOutput(response).resultText)
    }

    @Test
    fun rejectsTruncatedOrIncompleteGroupsBeforeTheyReachTheEngine() {
        val incompletePreedit = MozcTestProto.field(
            fieldNumber = 2,
            value = MozcTestProto.field(5, MozcTestProto.preeditWithoutSegmentEnd("あ")),
        )

        assertFailsWith<MozcWireFormatException> {
            MozcWireCodec.decodeOutput(incompletePreedit)
        }
        assertFailsWith<IllegalArgumentException> {
            MozcWireCodec.sendKey("\ud800")
        }
        assertFailsWith<IllegalArgumentException> {
            MozcWireCodec.sendKey("a".repeat(8 * 1024 + 1))
        }
    }

    private fun ByteArray.hex(): String = joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
}

/** Tiny writer for protocol-conformance test fixtures; production code has no generic raw API. */
internal object MozcTestProto {
    data class Candidate(
        val id: Int?,
        val index: Int,
        val value: String,
    )

    fun commandResponse(
        preedit: String? = null,
        result: String? = null,
        resultType: Int = 1,
        candidates: List<Candidate> = emptyList(),
        totalCandidateCount: Int = candidates.maxOfOrNull { it.index + 1 } ?: 0,
        pageSize: Int = 9,
        includeUnknownTopLevelField: Boolean = false,
    ): ByteArray {
        val output = ByteArrayOutputStream().apply {
            if (result != null) write(field(4, result(result, resultType)))
            if (preedit != null) write(field(5, preedit(preedit)))
            if (candidates.isNotEmpty()) {
                write(field(6, candidateWindow(candidates, totalCandidateCount, pageSize)))
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            if (includeUnknownTopLevelField) write(varintField(7, 123))
            write(field(2, output))
        }.toByteArray()
    }

    fun preeditWithoutSegmentEnd(value: String): ByteArray = ByteArrayOutputStream().apply {
        write(varintField(1, 0))
        writeTag(2, wireType = 3)
        write(varintField(3, 1))
        write(field(4, value.toByteArray(Charsets.UTF_8)))
        write(varintField(5, value.codePointCount(0, value.length).toLong()))
        // No field-2 end group: this is intentionally malformed.
    }.toByteArray()

    fun field(fieldNumber: Int, value: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        writeTag(fieldNumber, wireType = 2)
        writeVarint(value.size.toLong())
        write(value)
    }.toByteArray()

    private fun result(value: String, type: Int): ByteArray = ByteArrayOutputStream().apply {
        write(varintField(1, type.toLong()))
        write(field(2, value.toByteArray(Charsets.UTF_8)))
    }.toByteArray()

    private fun preedit(value: String): ByteArray = ByteArrayOutputStream().apply {
        write(varintField(1, 0))
        writeTag(2, wireType = 3)
        write(varintField(3, 1))
        write(field(4, value.toByteArray(Charsets.UTF_8)))
        write(varintField(5, value.codePointCount(0, value.length).toLong()))
        writeTag(2, wireType = 4)
    }.toByteArray()

    private fun candidateWindow(
        candidates: List<Candidate>,
        totalCandidateCount: Int,
        pageSize: Int,
    ): ByteArray = ByteArrayOutputStream().apply {
        write(varintField(2, totalCandidateCount.toLong()))
        candidates.forEach { candidate ->
            writeTag(3, wireType = 3)
            write(varintField(4, candidate.index.toLong()))
            write(field(5, candidate.value.toByteArray(Charsets.UTF_8)))
            candidate.id?.let { write(varintField(9, it.toLong())) }
            writeTag(3, wireType = 4)
        }
        write(varintField(6, 0))
        write(varintField(18, pageSize.toLong()))
    }.toByteArray()

    private fun varintField(fieldNumber: Int, value: Long): ByteArray = ByteArrayOutputStream().apply {
        writeTag(fieldNumber, wireType = 0)
        writeVarint(value)
    }.toByteArray()

    private fun ByteArrayOutputStream.writeTag(fieldNumber: Int, wireType: Int) {
        writeVarint((fieldNumber.toLong() shl 3) or wireType.toLong())
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var remaining = value
        do {
            val next = (remaining and 0x7fL).toInt()
            remaining = remaining ushr 7
            write(if (remaining == 0L) next else next or 0x80)
        } while (remaining != 0L)
    }
}
