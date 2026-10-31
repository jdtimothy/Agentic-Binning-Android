package com.agentic.binning.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectMissingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun existingFolder_passes() {
        val dir = tmp.newFolder("A")
        requireProjectFolder(ProjectRef("A", dir.path))
    }

    @Test fun missingFolder_throws_andDoesNotCreateIt() {
        val dir = File(tmp.root, "Gone")
        val ex = assertThrows(ProjectMissingException::class.java) {
            requireProjectFolder(ProjectRef("Gone", dir.path))
        }
        assertEquals(dir.path, ex.path)
        assertFalse(dir.exists())
    }

    @Test fun plainFile_isNotAProjectFolder() {
        val file = tmp.newFile("F")
        assertThrows(ProjectMissingException::class.java) {
            requireProjectFolder(ProjectRef("F", file.path))
        }
    }
}
