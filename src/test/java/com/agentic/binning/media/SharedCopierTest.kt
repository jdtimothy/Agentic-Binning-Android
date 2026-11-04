package com.agentic.binning.media

import com.agentic.binning.move.FakeMoveFiles
import com.agentic.binning.move.MoveEngine
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8: files another project holds are copied in, checked, and never touched where they are. */
class SharedCopierTest {
    private val p = "/storage/emulated/0/Agentic Binning/Trip"
    private val other = "/storage/emulated/0/Agentic Binning/Maui"
    private val fs = FakeMoveFiles()
    private val registered = ArrayList<String>()
    private val known = HashSet<String>()

    private val copier = SharedCopier(fs, p)

    private suspend fun copy(vararg held: HeldFile, onProgress: (Int, Int) -> Unit = { _, _ -> }) = copier.copy(
        held.toList(),
        isKnown = { it in known },
        register = { path -> registered += path; true },
        onProgress = onProgress,
    )

    private fun held(path: String, project: String = "Maui", content: String = "data:$path"): HeldFile {
        fs.put(path, content, mtime = 777L)
        return HeldFile(path, project, listed = true)
    }

    @Test fun copiesIntoACopiedFromFolderAndRegistersTheCopy() = runTest {
        val h = held("$other/Beach/Sunrise.jpg")

        val r = copy(h)

        assertEquals(1, r.copied)
        assertEquals("data:$other/Beach/Sunrise.jpg", fs.content("$p/Copied from Maui/Sunrise.jpg"))
        assertEquals(listOf("$p/Copied from Maui/Sunrise.jpg"), registered)
        assertEquals(listOf("Copied from Maui"), r.folders)
    }

    /** "The other projects' files stay exactly where they are." */
    @Test fun neverTouchesTheOriginal() = runTest {
        val h = held("$other/Beach/Sunrise.jpg")
        copy(h)
        assertEquals("data:$other/Beach/Sunrise.jpg", fs.content(h.path))
        assertTrue(fs.log.none { it.startsWith("delete $other") || it.startsWith("rename $other") })
    }

    @Test fun keepsTheFilesDate() = runTest {
        copy(held("$other/Beach/Sunrise.jpg"))
        assertEquals(777L, fs.lastModified("$p/Copied from Maui/Sunrise.jpg"))
    }

    @Test fun aTakenNameGetsANumber_nothingIsOverwritten() = runTest {
        fs.put("$p/Copied from Maui/Sunrise.jpg", "already here")
        copy(held("$other/Beach/Sunrise.jpg"))
        assertEquals("already here", fs.content("$p/Copied from Maui/Sunrise.jpg"))
        assertEquals("data:$other/Beach/Sunrise.jpg", fs.content("$p/Copied from Maui/Sunrise 2.jpg"))
    }

    @Test fun eachProjectGetsItsOwnFolder() = runTest {
        val r = copy(held("$other/a.jpg"), held("/storage/emulated/0/Agentic Binning/Sort Test/b.jpg", "Sort Test"))
        assertEquals(2, r.copied)
        assertTrue(fs.exists("$p/Copied from Maui/a.jpg"))
        assertTrue(fs.exists("$p/Copied from Sort Test/b.jpg"))
        assertEquals(listOf("Copied from Maui", "Copied from Sort Test"), r.folders)
    }

    @Test fun aProjectNameIsMadeSafeForAFolder() = runTest {
        copy(held("$other/a.jpg", "Maui: 2026/July"))
        assertEquals(1, registered.size)
        assertEquals("the name makes one folder, not two", 1, registered.single().removePrefix("$p/").count { it == '/' })
        assertTrue(registered.single().startsWith("$p/Copied from "))
    }

    /** B4's rule: a copy is only trusted after it is read back. */
    @Test fun aBadCopyIsRetriedOnce() = runTest {
        fs.corruptCopies = 1
        val r = copy(held("$other/a.jpg"))
        assertEquals(1, r.copied)
        assertEquals(0, r.failed)
        assertEquals("data:$other/a.jpg", fs.content("$p/Copied from Maui/a.jpg"))
    }

    @Test fun aCopyThatNeverMatches_isNotAddedAndLeavesNothingBehind() = runTest {
        fs.corruptCopies = 2
        val h = held("$other/a.jpg")

        val r = copy(h)

        assertEquals(0, r.copied)
        assertEquals(1, r.failed)
        assertTrue(registered.isEmpty())
        assertNull(fs.content("$p/Copied from Maui/a.jpg"))
        assertTrue("no temp copy left", fs.files.keys.none { it.endsWith(".tmp") })
        assertEquals("data:$other/a.jpg", fs.content(h.path))
    }

    /** B6: a copy needs its size plus the margin free; the rest stay out, and it says how much to free. */
    @Test fun notEnoughSpace_stopsBeforeCopying() = runTest {
        fs.free = MoveEngine.COPY_MARGIN_BYTES
        val r = copy(held("$other/a.jpg"), held("$other/b.jpg"))
        assertEquals(0, r.copied)
        assertEquals(2, r.notTried)
        assertTrue(r.neededBytes > 0)
        assertTrue(fs.log.none { it.startsWith("copy") })
    }

    @Test fun spaceRunningOut_deletesTheHalfCopy() = runTest {
        fs.noSpaceDuringCopy = true
        val r = copy(held("$other/a.jpg"))
        assertEquals(0, r.copied)
        assertEquals(1, r.notTried)
        assertTrue(fs.files.keys.none { it.endsWith(".tmp") })
    }

    /** The same picture picked twice, or already in this project: no second copy. */
    @Test fun aFileThisProjectAlreadyHolds_isNotCopiedAgain() = runTest {
        val h = held("$other/a.jpg")
        known += FakeMoveFiles.hash("data:$other/a.jpg")
        val r = copy(h)
        assertEquals(0, r.copied)
        assertEquals(1, r.alreadyHere)
        assertTrue(fs.log.none { it.startsWith("copy") })
    }

    /** The app died part way through an earlier copy: its unchecked temp files are never trusted or kept. */
    @Test fun tempCopiesLeftByACrash_areRemoved() = runTest {
        fs.put("$p/.binning/moving/copy-7.tmp", "half a file")
        fs.put("$p/.binning/moving/12.tmp", "a Bin Files step's temp: the engine's own")
        copy(held("$other/a.jpg"))
        assertNull(fs.content("$p/.binning/moving/copy-7.tmp"))
        assertEquals("a Bin Files step's temp: the engine's own", fs.content("$p/.binning/moving/12.tmp"))
    }

    @Test fun reportsProgressPerFile() = runTest {
        val seen = ArrayList<Pair<Int, Int>>()
        copy(held("$other/a.jpg"), held("$other/b.jpg")) { done, total -> seen += done to total }
        assertEquals(listOf(1 to 2, 2 to 2), seen)
    }

    @Test fun aFailedRegistration_keepsTheCopyOutOfTheCount() = runTest {
        val r = copier.copy(listOf(held("$other/a.jpg")), isKnown = { false }, register = { false }, onProgress = { _, _ -> })
        assertEquals(0, r.copied)
        assertEquals(1, r.failed)
        assertFalse("an unregistered copy isn't left to trip the folder check", fs.exists("$p/Copied from Maui/a.jpg"))
    }
}
