package com.agentic.binning.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.agentic.binning.db.ItemKind
import java.io.File

/** Resolves Storage Access Framework URIs from the system pickers to real files (all files access is held). */
object MediaUris {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val MEDIA_AUTHORITY = "com.android.providers.media.documents"

    /** The directory behind a tree URI from `OpenDocumentTree`, or null if it is not on shared storage. */
    fun treeUriToDir(uri: Uri): File? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return DocumentPaths.treeDocIdToPath(docId)?.let(::File)
    }

    /** The file behind a document URI from `OpenMultipleDocuments`, or null if it cannot be resolved. */
    fun documentUriToFile(context: Context, uri: Uri): File? {
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return when (uri.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> DocumentPaths.treeDocIdToPath(docId)?.let(::File)
            MEDIA_AUTHORITY -> mediaStorePath(context, docId)?.let(::File)
            else -> null
        }
    }

    private fun mediaStorePath(context: Context, docId: String): String? {
        val (kind, id) = DocumentPaths.mediaDocId(docId) ?: return null
        val collection = when (kind) {
            ItemKind.Photo -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ItemKind.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return try {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DATA),
                "${MediaStore.MediaColumns._ID} = ?",
                arrayOf(id.toString()),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: SecurityException) {
            null
        }
    }
}
