package com.agentic.binning.move

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RealMoveFilesTest {
    private val root: File = Files.createTempDirectory("move-files").toFile()
    private val files = RealMoveFiles { Files.getFileStore(it.toPath()) }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun write(rel: String, text: String): String {
        val f = File(root, rel)
        f.parentFile.mkdirs()
        f.writeText(text)
        return f.path
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun rename_neverReplaces() {
        val a = write("DCIM/a.jpg", "aaa")
        val b = write("Beach/b.jpg", "bbb")
        assertFalse(files.renameNoReplace(a, b))
        assertEquals("aaa", File(a).readText())
        assertEquals("bbb", File(b).readText())
    }

    @Test fun rename_movesFolderAndName() {
        val a = write("DCIM/a.jpg", "aaa")
        File(a).setLastModified(1_000_000L)
        val dest = File(root, "Beach/Morning.jpg").path
        files.mkdirs(File(dest).parent)
        assertTrue(files.renameNoReplace(a, dest))
        assertFalse(files.exists(a))
        assertEquals("aaa", File(dest).readText())
        assertEquals(1_000_000L, files.lastModified(dest))
    }

    @Test fun copyHashing_returnsTheSourceHash() {
        val a = write("DCIM/a.jpg", "hello")
        val tmp = File(root, ".binning/moving/1.tmp").path
        files.mkdirs(File(tmp).parent)
        assertEquals(sha("hello"), files.copyHashing(a, tmp))
        assertEquals(sha("hello"), files.sha256(tmp))
        assertEquals(5L, files.size(tmp))
        assertTrue(files.exists(a))
    }

    @Test fun copyHashing_refusesAnExistingTarget() {
        val a = write("a.jpg", "new")
        val b = write("b.jpg", "old")
        try {
            files.copyHashing(a, b)
            throw AssertionError("expected an IOException")
        } catch (_: java.io.IOException) {
        }
        assertEquals("old", File(b).readText())
    }

    @Test fun removeEmptyDir_keepsANonEmptyFolder() {
        write("Beach/.nomedia", "")
        File(root, "Empty").mkdirs()
        assertFalse(files.removeEmptyDir(File(root, "Beach").path))
        assertTrue(File(root, "Beach/.nomedia").exists())
        assertTrue(files.removeEmptyDir(File(root, "Empty").path))
        assertFalse(File(root, "Empty").exists())
        assertFalse(files.removeEmptyDir(File(root, "Nowhere").path))
    }

    @Test fun listFiles_isRecursive() {
        write("Beach/a.jpg", "a")
        write("Beach/sub/b.jpg", "b")
        assertEquals(listOf("Beach/a.jpg", "Beach/sub/b.jpg"), files.listFiles(File(root, "Beach").path).map { it.removePrefix(root.path + "/") }.sorted())
        assertEquals(emptyList<String>(), files.listFiles(File(root, "Nowhere").path))
    }

    @Test fun sameVolume_onOneTempDir_evenForAFolderNotYetMade() {
        val a = write("DCIM/a.jpg", "a")
        assertTrue(files.sameVolume(a, File(root, "Not/Yet/Made").path))
    }

    @Test fun delete_onlyFiles() {
        val a = write("a.jpg", "a")
        File(root, "dir").mkdirs()
        assertTrue(files.delete(a))
        assertFalse(files.delete(File(root, "dir").path))
        assertTrue(File(root, "dir").exists())
    }
}
