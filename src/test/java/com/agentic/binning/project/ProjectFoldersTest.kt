package com.agentic.binning.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ProjectFoldersTest {
    @Test fun folderName_replacesIllegalCharacters() {
        assertEquals("La Jolla Trip", ProjectFolders.folderNameFor("  La Jolla Trip "))
        assertEquals("Trip_ 2026_07", ProjectFolders.folderNameFor("Trip/ 2026:07"))
        assertEquals("Trip", ProjectFolders.folderNameFor("Trip..."))
    }

    @Test fun folderName_capsAtEightyCharacters() {
        assertEquals(80, ProjectFolders.folderNameFor("x".repeat(200)).length)
    }

    @Test fun layoutPaths() {
        val dir = File("p")
        assertEquals(File(dir, ".binning"), ProjectFolders.binningDir(dir))
        assertEquals(File(dir, ".binning/project.db"), ProjectFolders.databaseFile(dir))
        assertEquals(File(dir, ".binning/thumbs"), ProjectFolders.thumbsDir(dir))
    }
}
