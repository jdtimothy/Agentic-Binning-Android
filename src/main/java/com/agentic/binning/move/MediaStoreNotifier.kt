package com.agentic.binning.move

import android.content.Context
import android.media.MediaScannerConnection
import android.util.Log

/**
 * After each moved file, asks MediaStore to scan both paths (spec, "Storage and permissions"; B11):
 * the new path is indexed, and scanning the old path, which no longer exists, removes its row.
 * Renames through the FUSE file system usually update MediaStore on their own; this makes sure.
 * Scans are asynchronous and never block or fail a move. What Gallery and the pickers then show is
 * checked on the phone (Plan 3 Task 10).
 */
class MediaStoreNotifier(private val context: Context) : MoveNotifier {
    override fun moved(from: String?, to: String) {
        val paths = listOfNotNull(to, from).toTypedArray()
        try {
            MediaScannerConnection.scanFile(context.applicationContext, paths, null) { path, uri ->
                Log.d(TAG, "scanned $path -> $uri")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "MediaStore scan failed for $to", e)
        }
    }

    private companion object {
        const val TAG = "MoveNotifier"
    }
}
