package com.agentic.binning.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.agentic.binning.db.ItemKind
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class MediaMetadata(val takenAt: Long?, val durationMs: Long?, val width: Int?, val height: Int?)

object MediaMetadataReader {
    /**
     * Reads capture time, duration and size. Photos use EXIF `DateTimeOriginal`, videos the
     * container date; a missing date falls back to the file's modification time. Never throws for
     * unparseable media: unreadable fields are simply null.
     */
    @Suppress("UNUSED_PARAMETER")
    fun read(context: Context, candidate: Candidate): MediaMetadata {
        val fallback = candidate.file.lastModified().takeIf { it > 0 }
        return when (candidate.kind) {
            ItemKind.Photo -> readPhoto(candidate, fallback)
            ItemKind.Video -> readVideo(candidate, fallback)
        }
    }

    private fun readPhoto(candidate: Candidate, fallback: Long?): MediaMetadata {
        var takenAt: Long? = null
        var width: Int? = null
        var height: Int? = null
        var rotated = false
        try {
            val exif = ExifInterface(candidate.file.absolutePath)
            takenAt = parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
            val w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (w > 0 && h > 0) {
                width = w
                height = h
            }
            rotated = exif.rotationDegrees % 180 != 0
        } catch (_: Exception) {
        }
        if (width == null || height == null) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(candidate.file.absolutePath, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    width = bounds.outWidth
                    height = bounds.outHeight
                }
            } catch (_: Exception) {
            }
        }
        if (rotated) {
            val swap = width
            width = height
            height = swap
        }
        return MediaMetadata(takenAt ?: fallback, null, width, height)
    }

    private fun readVideo(candidate: Candidate, fallback: Long?): MediaMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(candidate.file.absolutePath)
            val takenAt = parseVideoDate(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) {
                val swap = width
                width = height
                height = swap
            }
            return MediaMetadata(takenAt ?: fallback, duration, width, height)
        } catch (_: Exception) {
            return MediaMetadata(fallback, null, null, null)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    /** EXIF `yyyy:MM:dd HH:mm:ss`, which carries no zone, read in the device's local time. */
    internal fun parseExifDate(value: String?): Long? = parse(value, "yyyy:MM:dd HH:mm:ss", TimeZone.getDefault())

    /** MediaMetadataRetriever date, `yyyyMMdd'T'HHmmss[.SSS]'Z'` in UTC; dates before 1970 mean "unset". */
    internal fun parseVideoDate(value: String?): Long? {
        val utc = TimeZone.getTimeZone("UTC")
        val millis = parse(value, "yyyyMMdd'T'HHmmss.SSS'Z'", utc) ?: parse(value, "yyyyMMdd'T'HHmmss'Z'", utc)
        return millis?.takeIf { it > 0 }
    }

    private fun parse(value: String?, pattern: String, zone: TimeZone): Long? {
        val text = value?.trim()
        if (text.isNullOrEmpty()) return null
        val format = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = zone
            isLenient = false
        }
        val position = ParsePosition(0)
        val date = format.parse(text, position) ?: return null
        return if (position.index == text.length) date.time else null
    }
}
