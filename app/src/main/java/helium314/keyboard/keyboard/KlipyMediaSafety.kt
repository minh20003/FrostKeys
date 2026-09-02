// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Security and resource limits shared by Klipy downloads and sticker conversion. */
internal object KlipyMediaSafety {
    const val MAX_DOWNLOAD_BYTES = 15L * 1024L * 1024L
    const val MAX_DIMENSION_PX = 2048
    const val MAX_OUTPUT_BYTES = 500L * 1024L
    const val NORMAL_MAX_FRAMES = 24
    const val LOW_RAM_MAX_FRAMES = 12

    enum class DownloadKind {
        GIF,
        STICKER_SOURCE,
    }

    /** Klipy's selected full-resolution URLs have a single, expected media type per flow. */
    fun acceptsMimeType(rawMimeType: String?, kind: DownloadKind): Boolean {
        val mimeType = rawMimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?: return false
        return when (kind) {
            DownloadKind.GIF -> mimeType == "image/gif"
            DownloadKind.STICKER_SOURCE -> mimeType == "image/webp"
        }
    }

    fun acceptsContentLength(contentLength: Long): Boolean =
        contentLength < 0L || contentLength <= MAX_DOWNLOAD_BYTES

    fun hasSafeDimensions(width: Int, height: Int): Boolean =
        width in 1..MAX_DIMENSION_PX && height in 1..MAX_DIMENSION_PX

    fun maxFrameCount(lowRamDevice: Boolean): Int =
        if (lowRamDevice) LOW_RAM_MAX_FRAMES else NORMAL_MAX_FRAMES

    /**
     * Never put an externally supplied Klipy ID in a file name. Apart from path traversal safety,
     * this gives cache names a stable, fixed length even if the provider changes its ID format.
     */
    fun cacheKey(kind: DownloadKind, itemId: String): String {
        val source = "${kind.name}:$itemId".toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(source)
            .joinToString(separator = "") { "%02x".format(it) }
            .take(32)
    }
}

/**
 * Writes a response into a same-directory temporary file and moves it into place only after every
 * byte passes the configured limit. It deliberately leaves an already cached target untouched if
 * writing, cancellation, or validation fails.
 */
internal object KlipyAtomicMediaWriter {
    @Throws(IOException::class)
    fun copyIntoPlace(
        input: InputStream,
        target: File,
        maxBytes: Long = KlipyMediaSafety.MAX_DOWNLOAD_BYTES,
        beforeChunk: () -> Unit = {},
    ): Long {
        require(maxBytes > 0L)
        val parent = target.parentFile ?: throw IOException("Klipy target has no parent directory")
        if (!parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Could not create Klipy media directory")
        }
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.partial")
        var written = 0L
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    beforeChunk()
                    val count = input.read(buffer)
                    if (count < 0) break
                    written += count.toLong()
                    if (written > maxBytes) {
                        throw IOException("Klipy media exceeds the download limit")
                    }
                    output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            moveIntoPlace(temporary, target)
            return written
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                // A best-effort cleanup failure must not hide the original I/O/cancellation error.
            }
        }
    }

    @Throws(IOException::class)
    private fun moveIntoPlace(temporary: File, target: File) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            // Never delete a known-good cache entry merely because this filesystem does not offer
            // an atomic replacement. A retry can use the old entry; replacing it non-atomically
            // would expose a missing or partial file if the process is killed between operations.
            if (target.exists()) {
                throw IOException("Atomic replacement is unavailable; preserved existing Klipy media")
            }
            if (!temporary.renameTo(target)) {
                throw IOException("Could not finalize Klipy media")
            }
        }
    }
}
