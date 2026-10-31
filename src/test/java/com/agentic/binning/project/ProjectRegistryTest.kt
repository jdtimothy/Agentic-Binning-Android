package com.agentic.binning.project

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectRegistryTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var registryFile: File
    private lateinit var registry: ProjectRegistry

    @Before fun setUp() {
        root = File(tmp.root, "Agentic Binning")
        registryFile = File(tmp.root, "projects.json")
        registry = ProjectRegistry(registryFile, root)
    }

    @Test fun create_makesLayout_andRegisters() {
        val ref = registry.create("La Jolla Trip").getOrThrow()
        assertTrue(File(ref.path, ".binning/thumbs").isDirectory)
        assertTrue(File(ref.path, ".binning/.nomedia").isFile)
        assertEquals(listOf(ref), registry.list())
    }

    @Test fun create_existingFolder_fails() {
        registry.create("A").getOrThrow()
        assertTrue(registry.create("A").exceptionOrNull() is ProjectExistsException)
    }

    @Test fun create_blankName_fails() {
        assertTrue(registry.create("   ").exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun current_persistsAcrossInstances() {
        val ref = registry.create("A").getOrThrow(); registry.setCurrent(ref)
        assertEquals(ref, ProjectRegistry(registryFile, root).current())
    }

    @Test fun projectContaining_matchesOnlyDescendants() {
        val a = registry.create("A").getOrThrow(); registry.create("AB").getOrThrow()
        assertEquals(a, registry.projectContaining("${a.path}/Sea lions/x.mp4"))
        assertNull(registry.projectContaining("${root.path}/ABC/x.mp4"))
    }

    @Test fun corruptRegistryFile_readsAsEmpty() {
        registryFile.writeText("{not json")
        assertEquals(emptyList<ProjectRef>(), registry.list())
        assertNull(registry.current())
    }

    @Test fun setCurrent_overwritesExistingRegistryFile() {
        val a = registry.create("A").getOrThrow()
        val b = registry.create("B").getOrThrow()
        registry.setCurrent(a); registry.setCurrent(b)
        assertEquals(b, ProjectRegistry(registryFile, root).current())
        assertEquals(listOf(a, b), ProjectRegistry(registryFile, root).list())
    }

    @Test fun clearCurrent_keepsProjects_butForgetsCurrent() {
        val a = registry.create("A").getOrThrow(); registry.setCurrent(a)
        registry.clearCurrent()
        assertNull(ProjectRegistry(registryFile, root).current())
        assertEquals(listOf(a), ProjectRegistry(registryFile, root).list())
    }

    @Test fun clearCurrent_withNoCurrent_isNoOp() {
        registry.clearCurrent()
        assertNull(registry.current())
        assertEquals(emptyList<ProjectRef>(), registry.list())
    }
}
