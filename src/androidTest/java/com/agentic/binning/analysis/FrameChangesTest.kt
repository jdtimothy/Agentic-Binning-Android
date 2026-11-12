package com.agentic.binning.analysis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameChangesTest {
    @Test fun preparedVideo_hasChangesAlignedWithItsActualFrames() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "frame-changes-${System.nanoTime()}").apply { mkdirs() }
        val source = File("/sdcard/Movies/action-times-step1/20260920_174506.mp4")
        org.junit.Assume.assumeTrue("Step 1 phone fixture must exist", source.isFile)
        val item = ItemEntity(1, "fixture", source.path, source.name, source.path, ItemKind.Video,
            null, 47362, null, null, ItemStatus.Pending, addedAt = 0)
        try {
            val media = MediaPreparer(File(root, "work")).prepare(item, File(root, "thumbs"), 512, 16)
            assertEquals(media.imagePaths.size, media.frameChanges.size)
            assertEquals(media.frameTimesMs.size, media.frameChanges.size)
            assertEquals(0.0, media.frameChanges.first(), 0.0)
            assertTrue(media.frameChanges.drop(1).any { it > 0 })
        } finally {
            root.deleteRecursively()
        }
    }
}
