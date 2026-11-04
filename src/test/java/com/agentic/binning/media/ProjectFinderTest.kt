package com.agentic.binning.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectFinderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun project(name: String): File = File(tmp.root, name).apply { File(this, ".binning").mkdirs(); File(this, ".binning/project.db").writeText("db") }

    private val finder = ProjectFinder { db -> db.parentFile!!.parentFile!!.name + " (from its database)" }

    @Test fun folderWithDatabase_isAProject() {
        val dir = project("Sort Test")
        val found = finder.projectContaining(File(dir, "photo.jpg"))
        assertEquals(dir.path, found?.dir?.path)
        assertEquals("Sort Test (from its database)", found?.name)
    }

    @Test fun nestedInsideAProject_isFound() {
        val dir = project("Sort Test")
        assertEquals(dir.path, finder.projectContaining(File(dir, "Beach/Sub/photo.jpg"))?.dir?.path)
    }

    @Test fun aFolderWithoutADatabase_isNotAProject() {
        File(tmp.root, "DCIM/Camera").mkdirs()
        assertNull(finder.projectContaining(File(tmp.root, "DCIM/Camera/photo.jpg")))
    }

    /** A database that can't say its name: the folder's name still identifies it. */
    @Test fun anUnreadableName_usesTheFolderName() {
        val dir = project("Old Trip")
        assertEquals("Old Trip", ProjectFinder { null }.projectContaining(File(dir, "a.jpg"))?.name)
    }

    /** A scan asks about thousands of files in one folder: the database is read once. */
    @Test fun aFolderIsAskedOnce() {
        val dir = project("Sort Test")
        var reads = 0
        val counting = ProjectFinder { reads++; "Name" }
        counting.projectContaining(File(dir, "a.jpg"))
        counting.projectContaining(File(dir, "b.jpg"))
        assertEquals(1, reads)
    }
}
