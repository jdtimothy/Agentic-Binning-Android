package com.agentic.binning.media

import com.agentic.binning.db.ItemKind

/** Pure mapping from Storage Access Framework document ids to file-system paths. */
object DocumentPaths {
    private const val STORAGE = "/storage"
    private const val PRIMARY_VOLUME = "primary"

    /**
     * Maps an `externalstorage` document id (`<volume>:<relative path>`) to the absolute path, or
     * null if it is not of that form. `primary` is the shared internal storage.
     */
    fun treeDocIdToPath(docId: String): String? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val volume = docId.substring(0, colon)
        val relative = docId.substring(colon + 1).trim('/')
        val base = if (volume == PRIMARY_VOLUME) "$STORAGE/emulated/0" else "$STORAGE/$volume"
        return if (relative.isEmpty()) base else "$base/$relative"
    }

    /** Parses a MediaStore document id such as `video:1234` into its kind and row id. */
    fun mediaDocId(docId: String): Pair<ItemKind, Long>? {
        val colon = docId.indexOf(':')
        if (colon <= 0) return null
        val kind = when (docId.substring(0, colon)) {
            "image" -> ItemKind.Photo
            "video" -> ItemKind.Video
            else -> return null
        }
        val id = docId.substring(colon + 1).toLongOrNull() ?: return null
        return kind to id
    }

    /**
     * The MediaStore row id behind a photo picker URI's path segments (`picker/<user>/<provider>/media/<id>`),
     * or null if the provider is not the local one: cloud items have no file on the phone.
     */
    fun pickerMediaId(segments: List<String>): Long? {
        if (segments.getOrNull(2)?.endsWith("media.photopicker") != true) return null
        return segments.last().toLongOrNull()
    }
}
