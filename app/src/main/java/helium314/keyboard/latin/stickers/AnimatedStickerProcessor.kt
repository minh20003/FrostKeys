package helium314.keyboard.latin.stickers

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.core.graphics.createBitmap
import com.aureusapps.android.webpandroid.CodecException
import com.aureusapps.android.webpandroid.CodecResult
import com.aureusapps.android.webpandroid.decoder.WebPDecodeListener
import com.aureusapps.android.webpandroid.decoder.WebPDecoder
import com.aureusapps.android.webpandroid.decoder.WebPInfo
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoder
import com.aureusapps.android.webpandroid.encoder.WebPConfig
import com.aureusapps.android.webpandroid.encoder.WebPPreset
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import helium314.keyboard.keyboard.KlipyMediaSafety
import helium314.keyboard.latin.utils.Log

class AnimatedStickerProcessor(private val context: Context) {

    companion object {
        private const val TAG = "StickerProcessor"
        private const val TARGET_SIZE = 512
        private const val OVERSIZED_MARKER_PREFIX = "oversized_v1_"
        private val PROCESSING_LOCK = Any()
    }

    fun createWhatsAppAnimatedSticker(
        sourceFile: File,
        isCancellationRequested: () -> Boolean = { false },
    ): File? {
        return synchronized(PROCESSING_LOCK) {
            createWhatsAppAnimatedStickerLocked(sourceFile, isCancellationRequested)
        }
    }

    private fun createWhatsAppAnimatedStickerLocked(
        sourceFile: File,
        isCancellationRequested: () -> Boolean,
    ): File? {
        if (isCancellationRequested()) return null
        if (!sourceFile.isFile || sourceFile.length() !in 1..KlipyMediaSafety.MAX_DOWNLOAD_BYTES) {
            Log.w(TAG, "Rejected missing or oversized sticker source")
            return null
        }
        val outputDir = File(context.filesDir, "stickers/klipy").apply { if (!exists()) mkdirs() }
        val outputFile = File(outputDir, "animated_${sourceFile.nameWithoutExtension}.webp")
        val oversizedMarkerFile = File(outputDir, "$OVERSIZED_MARKER_PREFIX${sourceFile.nameWithoutExtension}.marker")

        if (oversizedMarkerFile.exists()) {
            Log.d(TAG, "Skipping known oversized sticker: ${sourceFile.name}")
            return null
        }

        if (outputFile.exists()) {
            if (outputFile.length() > KlipyMediaSafety.MAX_OUTPUT_BYTES) {
                Log.w(TAG, "Deleting oversized cached sticker: ${outputFile.length()} bytes")
                outputFile.delete()
            } else {
                Log.d(TAG, "Using cached sticker")
                return outputFile
            }
        }

        Log.d(TAG, "Creating animated sticker")
        var decoder: WebPDecoder? = null
        var encoder: WebPAnimEncoder? = null
        val frames = mutableListOf<Bitmap>()
        val maxFrameCount = KlipyMediaSafety.maxFrameCount(isLowRamDevice())
        var rejectedSourceInfo = false
        val temporaryOutput = File(outputDir, ".${outputFile.name}.${UUID.randomUUID()}.partial")
        try {
            decoder = WebPDecoder(context)
            val timestamps = mutableListOf<Long>()
            var sourceFrameCount = 0

            decoder.addDecodeListener(object : WebPDecodeListener {
                override fun onInfoDecoded(info: WebPInfo) {
                    sourceFrameCount = info.frameCount
                    if (!KlipyMediaSafety.hasSafeDimensions(info.width, info.height)) {
                        rejectedSourceInfo = true
                        Log.w(TAG, "Rejected sticker dimensions outside the configured limit")
                        decoder.cancel()
                        return
                    }
                    Log.d(TAG, "Sticker source accepted with ${info.frameCount} frames")
                }

                override fun onFrameDecoded(index: Int, timestamp: Long, bitmap: Bitmap, uri: Uri) {
                    if (isCancellationRequested() || rejectedSourceInfo || frames.size >= maxFrameCount) {
                        decoder.cancel()
                        return
                    }
                    frames.add(resizeAndPadFrame(bitmap, TARGET_SIZE))
                    timestamps.add(timestamp)

                    if (frames.size >= maxFrameCount) {
                        decoder.cancel()
                    }
                }
            })

            decoder.setDataSource(Uri.fromFile(sourceFile))
            try {
                decoder.decodeFrames()
            } catch (e: CodecException) {
                if (e.codecResult != CodecResult.ERROR_USER_ABORT || frames.isEmpty()) {
                    throw e
                }
            }

            if (rejectedSourceInfo) {
                return null
            }
            if (isCancellationRequested()) return null
            if (sourceFrameCount > maxFrameCount) {
                Log.d(TAG, "Frame decode capped at $maxFrameCount of $sourceFrameCount frames")
            }
            Log.d(TAG, "Finished decoding. Total frames: ${frames.size}")

            if (frames.isEmpty()) {
                Log.e(TAG, "No frames decoded from ${sourceFile.name}")
                return null
            }

            val attempts = buildEncodeAttempts(frames.size)
            for (attempt in attempts) {
                if (isCancellationRequested()) return null
                encoder?.release()
                encoder = null
                temporaryOutput.delete()

                val frameIndices = sampleFrameIndices(frames.size, attempt.frameCount)
                encoder = WebPAnimEncoder(context, TARGET_SIZE, TARGET_SIZE).apply {
                    configure(
                        config = WebPConfig(
                            lossless = WebPConfig.COMPRESSION_LOSSY,
                            quality = attempt.quality
                        ),
                        preset = WebPPreset.WEBP_PRESET_PICTURE
                    )
                }
                frameIndices.forEach { index ->
                    if (isCancellationRequested()) return null
                    encoder.addFrame(timestamps[index], frames[index])
                }

                val lastTimestamp = calculateLastTimestamp(timestamps, frameIndices)
                encoder.assemble(lastTimestamp, Uri.fromFile(temporaryOutput))
                if (isCancellationRequested()) return null
                Log.d(TAG, "Sticker assembled: ${temporaryOutput.length()} bytes using ${frameIndices.size} frames at q=${attempt.quality.toInt()}")

                if (temporaryOutput.length() in 1..KlipyMediaSafety.MAX_OUTPUT_BYTES) {
                    if (!replaceOutputAtomically(temporaryOutput, outputFile)) {
                        Log.e(TAG, "Could not finalize processed sticker")
                        return null
                    }
                    return outputFile
                }

                Log.w(TAG, "Sticker attempt too large for WhatsApp: ${temporaryOutput.length()} bytes")
            }

            oversizedMarkerFile.writeText("too_large")
            Log.w(TAG, "Sticker cannot fit under WhatsApp limit after retries")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create animated sticker", e)
            return null
        } finally {
            frames.forEach { it.recycle() }
            decoder?.release()
            encoder?.release()
            if (temporaryOutput.exists()) temporaryOutput.delete()
        }
    }

    /** The source and final file live on the same app-private filesystem, so use a true replace move. */
    private fun replaceOutputAtomically(temporary: File, output: File): Boolean {
        return try {
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            true
        } catch (_: AtomicMoveNotSupportedException) {
            // Do not fall back to delete + rename: an interrupted replacement must retain the
            // previous usable sticker rather than leaving the cache empty or half-written.
            false
        } catch (error: Exception) {
            Log.e(TAG, "Could not atomically replace processed sticker", error)
            false
        }
    }

    private fun isLowRamDevice(): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    private data class EncodeAttempt(val frameCount: Int, val quality: Float)

    private fun buildEncodeAttempts(availableFrames: Int): List<EncodeAttempt> {
        val candidates = listOf(
            EncodeAttempt(availableFrames, 70f),
            EncodeAttempt(45, 62f),
            EncodeAttempt(30, 56f),
            EncodeAttempt(20, 50f),
            EncodeAttempt(12, 45f)
        )
        return candidates
            .map { it.copy(frameCount = it.frameCount.coerceAtMost(availableFrames)) }
            .distinctBy { it.frameCount to it.quality }
            .filter { it.frameCount > 0 }
    }

    private fun sampleFrameIndices(totalFrames: Int, targetFrames: Int): List<Int> {
        if (targetFrames >= totalFrames) return (0 until totalFrames).toList()
        if (targetFrames <= 1) return listOf(0)

        return (0 until targetFrames).map { index ->
            Math.round(index * (totalFrames - 1).toFloat() / (targetFrames - 1)).coerceIn(0, totalFrames - 1)
        }.distinct()
    }

    private fun calculateLastTimestamp(timestamps: List<Long>, frameIndices: List<Int>): Long {
        val lastIndex = frameIndices.last()
        val lastTimestamp = timestamps[lastIndex]
        val previousTimestamp = frameIndices.dropLast(1).lastOrNull()?.let { timestamps[it] }
        val frameDuration = previousTimestamp?.let { (lastTimestamp - it).coerceAtLeast(40L) } ?: 100L
        return lastTimestamp + frameDuration
    }

    private fun resizeAndPadFrame(source: Bitmap, targetSize: Int): Bitmap {
        val padding = 16
        val maxArtworkSize = (targetSize - (padding * 2)).toFloat()

        val output = createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val scale = Math.min(maxArtworkSize / source.width, maxArtworkSize / source.height)
        val left = (targetSize - (source.width * scale)) / 2f
        val top = (targetSize - (source.height * scale)) / 2f

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        canvas.drawBitmap(source, matrix, paint)
        return output
    }
}
