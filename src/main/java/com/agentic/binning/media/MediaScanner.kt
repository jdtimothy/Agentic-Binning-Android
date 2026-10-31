package com.agentic.binning.media

import com.agentic.binning.db.ItemKind
import java.io.File
import java.util.Locale

object MediaScanner {
    val PHOTO_EXT = setOf("jpg", "jpeg", "png", "heic", "heif", "webp", "dng")
    val VIDEO_EXT = setOf("mp4", "mov", "m4v", "3gp", "mkv", "webm")

    /** The media kind of [file] judged by its extension, or null if it is not a supported type. */
    fun kindOf(file: File): ItemKind? {
        val ext = file.extension.lowercase(Locale.ROOT)
        return when (ext) {
            in PHOTO_EXT -> ItemKind.Photo
            in VIDEO_EXT -> ItemKind.Video
            else -> null
        }
    }

    /**
     * Lazily walks [dir] recursively and yields supported media files. Entries whose name starts
     * with `.` are skipped, as is any directory for which [exclude] returns true. [dir] itself is
     * never tested against [exclude]. Children are visited in name order.
     */
    fun scan(dir: File, exclude: (File) -> Boolean): Sequence<File> = sequence {
        val children = dir.listFiles()?.sortedBy { it.name } ?: return@sequence
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                if (!exclude(child)) yieldAll(scan(child, exclude))
            } else if (kindOf(child) != null) {
                yield(child)
            }
        }
    }
}
