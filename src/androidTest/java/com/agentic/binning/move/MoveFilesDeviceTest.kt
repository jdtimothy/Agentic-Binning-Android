package com.agentic.binning.move

import android.content.Context
import android.os.Environment
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Plan 3 Task 5 on real shared storage (FUSE). Needs All files access granted to the app. Works in
 * `Movies/avb-plan3-test/`, deleted afterwards. Written in the cloud; not yet run on the phone.
 */
class MoveFilesDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val root = File(Environment.getExternalStorageDirectory(), "Movies/avb-plan3-test")
    private val files = RealMoveFiles { Os.stat(it.path).st_dev }

    @Before fun setUp() {
        root.deleteRecursively()
        assertTrue(root.mkdirs())
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun rename_withinSharedStorage_keepsSizeTimeAndContent() {
        val src = File(root, "DCIM/a.jpg").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(300_000) { (it % 251).toByte() }) }
        src.setLastModified(1_600_000_000_000L)
        val hash = files.sha256(src.path)
        val dest = File(root, "Beach Scenes/Morning.jpg")
        files.mkdirs(dest.parent!!)
        assertTrue(files.sameVolume(src.path, dest.path))
        assertTrue(files.renameNoReplace(src.path, dest.path))
        assertFalse(src.exists())
        assertEquals(300_000L, files.size(dest.path))
        assertEquals(1_600_000_000_000L, files.lastModified(dest.path))
        assertEquals(hash, files.sha256(dest.path))
    }

    @Test fun rename_neverReplacesOnSharedStorage() {
        val a = File(root, "a.jpg").apply { writeText("a") }
        val b = File(root, "b.jpg").apply { writeText("b") }
        assertFalse(files.renameNoReplace(a.path, b.path))
        assertEquals("b", b.readText())
    }

    @Test fun sameVolume_sharedVersusInternal() {
        val shared = File(root, "x.jpg").apply { writeText("x") }
        assertFalse(files.sameVolume(shared.path, context.filesDir.path))
    }

    @Test fun copyHashing_toSharedStorage_matchesReadBack() {
        val src = File(context.filesDir, "copy-src.bin").apply { writeBytes(ByteArray(2_000_000) { (it % 97).toByte() }) }
        try {
            val tmp = File(root, ".binning/moving/1.tmp")
            files.mkdirs(tmp.parent!!)
            val hash = files.copyHashing(src.path, tmp.path)
            assertEquals(hash, files.sha256(tmp.path))
            assertEquals(src.length(), files.size(tmp.path))
        } finally {
            src.delete()
        }
    }
}
