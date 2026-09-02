// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import java.io.File
import java.io.RandomAccessFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JniUtilsTest {
    @Test
    fun validArm64SharedObjectHeaderIsRecognizedButNotTrustedWithoutPinnedHash() {
        val library = writeTemporaryElf()
        try {
            assertTrue(JniUtils.isValidArm64Elf(library))
            assertFalse(JniUtils.isTrustedImportedGestureLibrary(library))
        } finally {
            library.delete()
        }
    }

    @Test
    fun invalidArchitectureAndOversizedLibrariesAreRejected() {
        val wrongArchitecture = writeTemporaryElf(machine = 40) // EM_ARM, not EM_AARCH64
        val oversized = writeTemporaryElf()
        try {
            assertFalse(JniUtils.isValidArm64Elf(wrongArchitecture))
            RandomAccessFile(oversized, "rw").use {
                it.setLength(JniUtils.MAX_IMPORTED_GESTURE_LIBRARY_BYTES + 1)
            }
            assertFalse(JniUtils.isValidArm64Elf(oversized))
        } finally {
            wrongArchitecture.delete()
            oversized.delete()
        }
    }

    @Test
    fun onlyTheBuildPinnedChecksumIsTrusted() {
        assertTrue(JniUtils.isTrustedGestureChecksum(JniUtils.expectedDefaultChecksum()))
        assertFalse(JniUtils.isTrustedGestureChecksum(JniUtils.expectedDefaultChecksum().replaceFirst('b', 'c')))
        assertFalse(JniUtils.isTrustedGestureChecksum(""))
    }

    private fun writeTemporaryElf(machine: Int = 183): File {
        val header = ByteArray(64 + 56)
        header[0] = 0x7f
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2 // ELFCLASS64
        header[5] = 1 // ELFDATA2LSB
        header[6] = 1 // EV_CURRENT
        putU16LE(header, 16, 3) // ET_DYN
        putU16LE(header, 18, machine)
        putU32LE(header, 20, 1)
        putU64LE(header, 32, 64)
        putU16LE(header, 52, 64)
        putU16LE(header, 54, 56)
        putU16LE(header, 56, 1)

        return File.createTempFile("frostkeys-gesture-", ".so").also { it.writeBytes(header) }
    }

    private fun putU16LE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32LE(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun putU64LE(bytes: ByteArray, offset: Int, value: Long) {
        repeat(8) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }
}
