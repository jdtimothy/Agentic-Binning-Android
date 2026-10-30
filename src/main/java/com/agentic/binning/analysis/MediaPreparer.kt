package com.agentic.binning.analysis

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.math.roundToInt

/** What the vision model reads for one item: absolute JPEG paths in prompt order. */
data class PreparedMedia(
    val kind: ItemKind,
    val imagePaths: List<String>,
    /** One per image for a video, in the same order; empty for a photo. */
    val frameTimesMs: List<Long>,
    val thumbnailPath: String,
)

class MediaDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The app's own work files or thumbnails could not be written (typically a full disk). Not a
 * [FileNotFoundException] even when the cause is one, so it is never mistaken for a missing item.
 */
class WorkFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Decodes a photo or samples a video into square JPEGs under `workDir/<itemId>/` (the native side
 * reads absolute file paths only) and writes a 512 px thumbnail. Blocking: call off the main thread.
 */
class MediaPreparer(private val workDir: File) {
    /**
     * [onFrame] is called after each video frame is written: (frames written, frames to sample).
     *
     * @throws FileNotFoundException if [item]'s file is gone.
     * @throws MediaDecodeException if it cannot be decoded.
     * @throws WorkFileException if a work file or the thumbnail cannot be written.
     */
    fun prepare(
        item: ItemEntity,
        thumbsDir: File,
        targetEdge: Int,
        onFrame: (ready: Int, total: Int) -> Unit = { _, _ -> },
    ): PreparedMedia {
        val source = File(item.currentPath)
        if (!source.isFile) throw FileNotFoundException(item.currentPath)
        val dir = File(workDir, item.id.toString())
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw WorkFileException("Cannot create ${dir.absolutePath}")
        if (!thumbsDir.isDirectory && !thumbsDir.mkdirs()) throw WorkFileException("Cannot create ${thumbsDir.absolutePath}")
        val thumb = File(thumbsDir, "${item.id}.jpg")
        return when (item.kind) {
            ItemKind.Photo -> preparePhoto(source, dir, thumb, targetEdge)
            ItemKind.Video -> prepareVideo(source, item.durationMs, dir, thumb, targetEdge, onFrame)
        }
    }

    fun cleanup(itemId: Long) {
        File(workDir, itemId.toString()).deleteRecursively()
    }

    private fun preparePhoto(source: File, dir: File, thumb: File, targetEdge: Int): PreparedMedia {
        // ALLOCATOR_SOFTWARE plus a 1,536 px cap applies EXIF rotation and avoids OOM on 200 MP photos.
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > MAX_DECODE_EDGE) {
                    val scale = MAX_DECODE_EDGE.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        } catch (e: Exception) {
            throw MediaDecodeException("Cannot decode ${source.name}: ${e.message}", e)
        }
        try {
            saveThumbnail(decoded, thumb)
            val image = File(dir, "image.jpg")
            saveSquare(decoded, targetEdge, image)
            return PreparedMedia(ItemKind.Photo, listOf(image.absolutePath), emptyList(), thumb.absolutePath)
        } finally {
            decoded.recycle()
        }
    }

    private fun prepareVideo(
        source: File,
        durationMs: Long?,
        dir: File,
        thumb: File,
        targetEdge: Int,
        onFrame: (ready: Int, total: Int) -> Unit,
    ): PreparedMedia {
        val retriever = MediaMetadataRetriever()
        try {
            try {
                retriever.setDataSource(source.absolutePath)
            } catch (e: Exception) {
                throw MediaDecodeException("Cannot open ${source.name}: ${e.message}", e)
            }
            val duration = durationMs?.takeIf { it > 0 }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: 0L
            val paths = ArrayList<String>()
            val times = ArrayList<Long>()
            val sampleTimes = VideoTiming.sampleTimesMs(duration)
            for (t in sampleTimes) {
                // OPTION_CLOSEST decodes the exact frame; CLOSEST_SYNC snaps to keyframes and can
                // collapse every sample onto frame 0.
                val frame = try {
                    retriever.getScaledFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, MAX_DECODE_EDGE, MAX_DECODE_EDGE)
                } catch (_: Exception) {
                    null
                } ?: continue
                try {
                    if (paths.isEmpty()) saveThumbnail(frame, thumb)
                    val file = File(dir, "f_$t.jpg")
                    saveSquare(frame, targetEdge, file)
                    paths += file.absolutePath
                    times += t
                    onFrame(paths.size, sampleTimes.size)
                } finally {
                    frame.recycle()
                }
            }
            if (paths.isEmpty()) throw MediaDecodeException("No frames could be read from ${source.name}")
            return PreparedMedia(ItemKind.Video, paths, times, thumb.absolutePath)
        } finally {
            retriever.release()
        }
    }

    /** Letterboxes [src] onto a black square of [edge] px (pad, not crop: the edges matter) and saves it. */
    private fun saveSquare(src: Bitmap, edge: Int, file: File) {
        val out = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        try {
            val scale = edge.toFloat() / maxOf(src.width, src.height)
            val w = (src.width * scale).roundToInt().coerceIn(1, edge)
            val h = (src.height * scale).roundToInt().coerceIn(1, edge)
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            Canvas(out).apply {
                drawColor(Color.BLACK)
                drawBitmap(scaled, (edge - w) / 2f, (edge - h) / 2f, null)
            }
            if (scaled !== src) scaled.recycle()
            writeJpeg(out, file, IMAGE_QUALITY)
        } finally {
            out.recycle()
        }
    }

    /** Unpadded, longest edge [THUMB_EDGE] px (never upscaled). */
    private fun saveThumbnail(src: Bitmap, file: File) {
        val scale = minOf(1f, THUMB_EDGE.toFloat() / maxOf(src.width, src.height))
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (w == src.width && h == src.height) src else Bitmap.createScaledBitmap(src, w, h, true)
        try {
            writeJpeg(scaled, file, THUMB_QUALITY)
        } finally {
            if (scaled !== src) scaled.recycle()
        }
    }

    /** Encoding a decoded bitmap only fails when the write does, so every failure here is a [WorkFileException]. */
    private fun writeJpeg(bitmap: Bitmap, file: File, quality: Int) {
        val ok = try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        } catch (e: IOException) {
            throw WorkFileException("Cannot write ${file.absolutePath}: ${e.message}", e)
        }
        if (!ok) throw WorkFileException("Cannot write ${file.absolutePath}")
    }

    private companion object {
        const val MAX_DECODE_EDGE = 1536
        const val THUMB_EDGE = 512
        const val IMAGE_QUALITY = 90
        const val THUMB_QUALITY = 85
    }
}
