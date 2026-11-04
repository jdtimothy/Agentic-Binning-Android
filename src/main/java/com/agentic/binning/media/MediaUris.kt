package com.agentic.binning.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.agentic.binning.db.ItemKind
import java.io.File

/** Resolves URIs from the pickers to real files (all files access is held). */
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

    /**
     * The file behind a picked URI, or null if it cannot be resolved: a URI from the Android photo
     * picker, a MediaStore URI from Samsung Gallery's picker, or a document URI from `OpenMultipleDocuments`.
     */
    fun pickedUriToFile(context: Context, uri: Uri): File? = when {
        uri.authority != MediaStore.AUTHORITY -> documentUriToFile(context, uri)
        uri.pathSegments.firstOrNull()?.startsWith("picker") == true -> photoPickerPath(context, uri)?.let(::File)
        else -> mediaStorePath(context, uri)?.let(::File)
    }

    /**
     * `content://media/picker/<user>/<provider>/media/<id>`: its own `_data` is a synthetic path that
     * hides the real name and location, so a local item is looked up by id. Cloud items have no file.
     */
    private fun photoPickerPath(context: Context, uri: Uri): String? {
        val id = DocumentPaths.pickerMediaId(uri.pathSegments) ?: return null
        return mediaStorePath(context, ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id))
    }

    private fun documentUriToFile(context: Context, uri: Uri): File? {
        val docId = try {
            DocumentsContract.getDocumentId(uri)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return when (uri.authority) {
            EXTERNAL_STORAGE_AUTHORITY -> DocumentPaths.treeDocIdToPath(docId)?.let(::File)
            MEDIA_AUTHORITY -> mediaDocumentPath(context, docId)?.let(::File)
            else -> null
        }
    }

    private fun mediaDocumentPath(context: Context, docId: String): String? {
        val (kind, id) = DocumentPaths.mediaDocId(docId) ?: return null
        val collection = when (kind) {
            ItemKind.Photo -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            ItemKind.Video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return mediaStorePath(context, ContentUris.withAppendedId(collection, id))
    }

    private fun mediaStorePath(context: Context, uri: Uri): String? =
        try {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        } catch (_: SecurityException) {
            null
        }
}
