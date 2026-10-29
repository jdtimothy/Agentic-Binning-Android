package com.agentic.binning.media

import com.agentic.binning.db.ItemKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaScannerTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File

    private fun touch(path: String) {
        val f = File(root, path)
        f.parentFile!!.mkdirs()
        f.writeText("x")
    }

    @Before fun setUp() {
        root = File(tmp.root, "a").also { it.mkdirs() }
        listOf("x.JPG", "y.mp4", "notes.txt", ".hidden/z.jpg", ".binning/t.jpg", "sub/w.mov", "other/q.jpg")
            .forEach(::touch)
    }

    @Test fun scan_findsMediaRecursively_skippingHiddenAndExcluded() {
        val found = MediaScanner.scan(root, exclude = { it.name == "other" }).map { it.name }.toSet()
        assertEquals(setOf("x.JPG", "y.mp4", "w.mov"), found)
    }

    @Test fun scan_withoutExclusions_includesOtherFolder() {
        val found = MediaScanner.scan(root, exclude = { false }).map { it.name }.toSet()
        assertEquals(setOf("x.JPG", "y.mp4", "w.mov", "q.jpg"), found)
    }

    @Test fun scan_missingDirectory_isEmpty() {
        assertEquals(emptyList<File>(), MediaScanner.scan(File(root, "nope"), exclude = { false }).toList())
    }

    @Test fun kindOf_isCaseInsensitiveByExtension() {
        assertEquals(ItemKind.Photo, MediaScanner.kindOf(File("a/B.HEIC")))
        assertEquals(ItemKind.Photo, MediaScanner.kindOf(File("a.webp")))
        assertEquals(ItemKind.Video, MediaScanner.kindOf(File("a/clip.MoV")))
        assertEquals(ItemKind.Video, MediaScanner.kindOf(File("clip.3gp")))
        assertNull(MediaScanner.kindOf(File("notes.txt")))
        assertNull(MediaScanner.kindOf(File("noextension")))
    }
}
