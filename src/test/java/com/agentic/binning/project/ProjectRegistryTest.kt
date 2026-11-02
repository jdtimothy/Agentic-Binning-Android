package com.agentic.binning.project

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---- Task 6A: canonical paths, strict writes, remove and restore ----

    @Test fun setCurrent_sameFolderByAnotherPath_addsNoDuplicate() {
        val a = registry.create("A").getOrThrow()
        val other = File(root, "x/../A").path + File.separator
        val stored = registry.setCurrent(ProjectRef("Renamed", other))
        assertEquals(a, stored)
        assertEquals(listOf(a), registry.list())
        assertEquals(a, registry.current())
    }

    @Test fun find_matchesAnySpellingOfTheFolder() {
        val a = registry.create("A").getOrThrow()
        assertEquals(a, registry.find(File(root, "./A/").path))
        assertNull(registry.find(File(root, "B").path))
    }

    @Test fun remove_forgetsOnlyThatEntry() {
        val a = registry.create("A").getOrThrow()
        val b = registry.create("B").getOrThrow()
        val c = registry.create("C").getOrThrow()
        registry.setCurrent(a)
        assertEquals(RemovedProject(b, 1, wasCurrent = false), registry.remove(b.path))
        assertEquals(listOf(a, c), registry.list())
        assertEquals(a, registry.current())
    }

    @Test fun remove_current_clearsCurrent() {
        val a = registry.create("A").getOrThrow()
        registry.setCurrent(a)
        assertEquals(RemovedProject(a, 0, wasCurrent = true), registry.remove(File(root, "x/../A").path))
        assertNull(registry.current())
        assertEquals(emptyList<ProjectRef>(), registry.list())
    }

    @Test fun remove_unknownPath_changesNothing() {
        val a = registry.create("A").getOrThrow()
        val before = registryFile.readBytes()
        assertNull(registry.remove(File(root, "Nope").path))
        assertEquals(listOf(a), registry.list())
        assertTrue(before.contentEquals(registryFile.readBytes()))
    }

    @Test fun remove_unreadableRegistry_refusesAndKeepsTheFile() {
        registryFile.writeText("{not json")
        val before = registryFile.readBytes()
        assertThrows<ProjectListUnreadableException> { registry.remove(File(root, "A").path) }
        assertTrue(before.contentEquals(registryFile.readBytes()))
    }

    @Test fun create_unreadableRegistry_refusesAndKeepsTheFile() {
        registryFile.writeText("{not json")
        val before = registryFile.readBytes()
        assertTrue(registry.create("A").exceptionOrNull() is ProjectListUnreadableException)
        assertTrue(before.contentEquals(registryFile.readBytes()))
        assertFalse("no folder is created when the list can't be updated", File(root, "A").exists())
    }

    @Test fun setCurrent_unreadableRegistry_refusesAndKeepsTheFile() {
        registryFile.writeText("{not json")
        val before = registryFile.readBytes()
        assertThrows<ProjectListUnreadableException> { registry.setCurrent(ProjectRef("A", File(root, "A").path)) }
        assertTrue(before.contentEquals(registryFile.readBytes()))
    }

    @Test fun clearCurrent_unreadableRegistry_refusesAndKeepsTheFile() {
        registryFile.writeText("{not json")
        val before = registryFile.readBytes()
        assertThrows<ProjectListUnreadableException> { registry.clearCurrent() }
        assertTrue(before.contentEquals(registryFile.readBytes()))
    }

    @Test fun restore_putsTheEntryBackAtItsPosition() {
        val a = registry.create("A").getOrThrow()
        val b = registry.create("B").getOrThrow()
        val c = registry.create("C").getOrThrow()
        val removed = registry.remove(b.path)!!
        registry.restore(removed)
        assertEquals(listOf(a, b, c), registry.list())
        registry.restore(removed)
        assertEquals("restoring twice adds nothing", listOf(a, b, c), registry.list())
    }

    @Test fun restore_afterTheListShrank_appendsAtTheEnd() {
        val a = registry.create("A").getOrThrow()
        val b = registry.create("B").getOrThrow()
        val c = registry.create("C").getOrThrow()
        val removedC = registry.remove(c.path)!!
        registry.remove(b.path)
        registry.restore(removedC)
        assertEquals(listOf(a, c), registry.list())
    }

    @Test fun remove_survivesANewInstance() {
        val a = registry.create("A").getOrThrow()
        val b = registry.create("B").getOrThrow()
        registry.setCurrent(a)
        registry.remove(b.path)
        val restarted = ProjectRegistry(registryFile, root)
        assertEquals(listOf(a), restarted.list())
        assertEquals(a, restarted.current())
    }

    @Test fun remove_preservesEveryFile() {
        val a = registry.create("A").getOrThrow()
        val dir = File(a.path)
        File(dir, "Beach/sea turtle.jpg").apply { parentFile?.mkdirs(); writeBytes(ByteArray(2048) { it.toByte() }) }
        File(dir, ".binning/project.db").writeText("database bytes")
        File(dir, ".binning/project.db-journal").writeText("")
        File(dir, ".binning/thumbs/1.jpg").writeBytes(ByteArray(512) { 7 })
        val before = snapshot(dir)
        registry.setCurrent(a)
        registry.remove(a.path)
        assertEquals(before, snapshot(dir))
    }

    @Test fun remove_missingFolder_doesNotRecreateIt() {
        val a = registry.create("A").getOrThrow()
        File(a.path).deleteRecursively()
        assertEquals(0, registry.remove(a.path)?.index)
        assertFalse(File(a.path).exists())
    }

    /** Relative path -> size and SHA-256 of every file under [dir]. */
    private fun snapshot(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }.associate { f ->
        val sha = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        f.relativeTo(dir).path to "${f.length()}:$sha"
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is T) return
            throw AssertionError("Expected ${T::class.simpleName}, got $t", t)
        }
        throw AssertionError("Expected ${T::class.simpleName}, nothing was thrown")
    }
}
