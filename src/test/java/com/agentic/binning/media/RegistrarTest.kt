package com.agentic.binning.media

import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.ItemStore
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.project.ProjectRegistry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeItemStore : ItemStore {
    val items = mutableListOf<ItemEntity>()
    private var nextId = 1L

    override suspend fun findByHashes(hashes: List<String>) = items.filter { it.contentHash in hashes }
    override suspend fun findByPaths(paths: List<String>) = items.filter { it.currentPath in paths }

    /** Enforces the same unique indices as the real table. */
    override suspend fun insert(item: ItemEntity): Long {
        check(items.none { it.contentHash == item.contentHash || it.currentPath == item.currentPath }) {
            "UNIQUE constraint failed"
        }
        val stored = item.copy(id = nextId++)
        items += stored
        return stored.id
    }
}

private class FakeLookup(
    override val projectName: String,
    private val hashes: Set<String> = emptySet(),
    private val paths: Set<String> = emptySet(),
) : ProjectItemLookup {
    override suspend fun containsHashOrPath(hash: String, path: String) = hash in hashes || path in paths
}

class RegistrarTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var registry: ProjectRegistry
    private lateinit var thisRef: ProjectRef
    private lateinit var otherRef: ProjectRef
    private lateinit var store: FakeItemStore
    private lateinit var mediaDir: File
    private var hashCalls = 0

    private val meta = MediaMetadata(takenAt = 1_000L, durationMs = 2_000L, width = 640, height = 480)

    @Before fun setUp() {
        registry = ProjectRegistry(File(tmp.root, "projects.json"), File(tmp.root, "Agentic Binning"))
        thisRef = registry.create("This Project").getOrThrow()
        otherRef = registry.create("La Jolla Trip").getOrThrow()
        store = FakeItemStore()
        mediaDir = File(tmp.root, "media")
    }

    private fun candidate(name: String, dir: File = mediaDir, kind: ItemKind = ItemKind.Photo) =
        Candidate(File(dir, name), kind)

    private fun registrar(
        others: List<ProjectItemLookup> = emptyList(),
        hash: (File) -> String = { "hash-${it.name}" },
        clock: () -> Long = { 42L },
        findProject: (File) -> ProjectFolder? = { null },
    ) = Registrar(
        thisProject = thisRef,
        itemDao = store,
        otherProjects = others,
        registry = registry,
        findProject = findProject,
        hash = { f -> hashCalls++; hash(f) },
        metadata = { meta },
        clock = clock,
    )

    private suspend fun outcomes(r: Registrar, vararg c: Candidate) =
        r.register(c.toList()).toList().map { it.last }

    @Test fun duplicateHashInProject_isSkipped() = runTest {
        val first = candidate("a.jpg")
        val copy = candidate("copy-of-a.jpg")
        val r = registrar(hash = { "same-bytes" })
        val result = outcomes(r, first, copy)
        assertEquals(RegistrationOutcome.Added(first.file.absolutePath), result[0])
        assertEquals(RegistrationOutcome.DuplicateInProject(copy.file.absolutePath), result[1])
        assertEquals(1, store.items.size)
    }

    @Test fun duplicatePathInProject_isSkippedWithoutHashing() = runTest {
        val c = candidate("a.jpg")
        outcomes(registrar(), c)
        hashCalls = 0
        val result = outcomes(registrar(), c)
        assertEquals(listOf<RegistrationOutcome>(RegistrationOutcome.DuplicateInProject(c.file.absolutePath)), result)
        assertEquals(0, hashCalls)
    }

    @Test fun fileInsideOtherProjectFolder_isRefused_namingThatProject() = runTest {
        val c = candidate("clip.mp4", dir = File(otherRef.path), kind = ItemKind.Video)
        val result = outcomes(registrar(), c)
        assertEquals(
            listOf<RegistrationOutcome>(RegistrationOutcome.HeldByOtherProject(c.file.absolutePath, "La Jolla Trip")),
            result,
        )
        assertTrue(store.items.isEmpty())
        assertEquals(0, hashCalls)
    }

    @Test fun fileInsideOtherProjectFolder_winsOverDuplicateInThisProject() = runTest {
        val c = candidate("clip.mp4", dir = File(otherRef.path), kind = ItemKind.Video)
        store.insert(item("h", c.file.absolutePath))
        val result = outcomes(registrar(), c)
        assertTrue(result.single() is RegistrationOutcome.HeldByOtherProject)
    }

    @Test fun fileInsideThisProjectFolder_isAccepted() = runTest {
        val c = candidate("mine.jpg", dir = File(thisRef.path))
        assertEquals(
            listOf<RegistrationOutcome>(RegistrationOutcome.Added(c.file.absolutePath)),
            outcomes(registrar(), c),
        )
    }

    @Test fun hashHeldByOtherProject_isRefused_namingThatProject() = runTest {
        val c = candidate("a.jpg")
        val others = listOf(FakeLookup("Empty"), FakeLookup("La Jolla Trip", hashes = setOf("hash-a.jpg")))
        val result = outcomes(registrar(others), c)
        assertEquals(
            listOf<RegistrationOutcome>(RegistrationOutcome.HeldByOtherProject(c.file.absolutePath, "La Jolla Trip")),
            result,
        )
        assertTrue(store.items.isEmpty())
    }

    @Test fun duplicateInThisProject_winsOverOtherProjectHolding() = runTest {
        val c = candidate("a.jpg")
        store.insert(item("hash-a.jpg", "/elsewhere/a.jpg"))
        val others = listOf(FakeLookup("La Jolla Trip", hashes = setOf("hash-a.jpg")))
        assertTrue(outcomes(registrar(others), c).single() is RegistrationOutcome.DuplicateInProject)
    }

    @Test fun unreadableFile_isReported_andOthersStillAdded() = runTest {
        val a = candidate("a.jpg")
        val bad = candidate("bad.jpg")
        val c = candidate("c.jpg")
        val r = registrar(hash = { if (it.name == "bad.jpg") throw IOException("EIO") else "hash-${it.name}" })
        val result = outcomes(r, a, bad, c)
        assertEquals(RegistrationOutcome.Added(a.file.absolutePath), result[0])
        assertEquals(RegistrationOutcome.Unreadable(bad.file.absolutePath, "EIO"), result[1])
        assertEquals(RegistrationOutcome.Added(c.file.absolutePath), result[2])
        assertEquals(2, store.items.size)
    }

    @Test fun unreadableReason_fallsBackToExceptionName() = runTest {
        val r = registrar(hash = { throw IllegalStateException() })
        val result = outcomes(r, candidate("a.jpg")).single() as RegistrationOutcome.Unreadable
        assertEquals("IllegalStateException", result.reason)
    }

    @Test fun cancellation_isNotSwallowed() = runTest {
        val r = registrar(hash = { throw CancellationException("stop") })
        try {
            outcomes(r, candidate("a.jpg"))
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test fun registrar_reportsProgressForEveryFile() = runTest {
        val candidates = (1..2000).map { candidate("f$it.jpg") }
        val events = registrar().register(candidates).toList()
        assertEquals(2000, events.size)
        assertEquals((1..2000).toList(), events.map { it.done })
        assertTrue(events.all { it.total == 2000 })
        assertEquals(2000, events.last().done)
        assertEquals(2000, store.items.size)
    }

    @Test fun register_emptyList_emitsNothing() = runTest {
        assertTrue(registrar().register(emptyList()).toList().isEmpty())
    }

    @Test fun added_itemsArePendingWithMetadata() = runTest {
        val photo = candidate("IMG_1.jpg")
        val video = candidate("MOV_1.mp4", kind = ItemKind.Video)
        outcomes(registrar(clock = { 99L }), photo, video)
        val stored = store.items.first { it.originalName == "IMG_1.jpg" }
        assertEquals(ItemStatus.Pending, stored.status)
        assertEquals(ItemKind.Photo, stored.kind)
        assertEquals("hash-IMG_1.jpg", stored.contentHash)
        assertEquals(photo.file.absolutePath, stored.originalPath)
        assertEquals(photo.file.absolutePath, stored.currentPath)
        assertEquals(1_000L, stored.takenAt)
        assertEquals(2_000L, stored.durationMs)
        assertEquals(640, stored.width)
        assertEquals(480, stored.height)
        assertEquals(99L, stored.addedAt)
        assertNull(stored.categoryId)
        assertNull(stored.friendlyName)
        assertEquals(ItemKind.Video, store.items.first { it.originalName == "MOV_1.mp4" }.kind)
    }

    private fun item(hash: String, path: String) = ItemEntity(
        contentHash = hash, originalPath = path, originalName = File(path).name, currentPath = path,
        kind = ItemKind.Photo, takenAt = null, durationMs = null, width = null, height = null,
        status = ItemStatus.Pending, addedAt = 1L,
    )

    // ---------------------------------------------------------------- P8 (Plan 3 Task 15)

    /** A project taken off the list still owns its folder: found by its database, not the registry. */
    @Test fun fileInAnUnlistedProjectFolder_isHeld() = runTest {
        val folder = File(tmp.root, "Old Trip")
        val r = registrar(findProject = { f -> if (f.path.startsWith(folder.path)) ProjectFolder(folder, "Old Trip") else null })

        val outcome = r.register(listOf(candidate("a.jpg", folder))).toList().single().last

        assertEquals(RegistrationOutcome.HeldByOtherProject(File(folder, "a.jpg").absolutePath, "Old Trip", listed = false), outcome)
        assertTrue(store.items.isEmpty())
        assertEquals("no hashing for a file it won't add", 0, hashCalls)
    }

    @Test fun fileInThisProjectsOwnFolder_isNotHeld() = runTest {
        val own = File(thisRef.path)
        val r = registrar(findProject = { ProjectFolder(own, "This Project") })
        val outcome = r.register(listOf(candidate("a.jpg", own))).toList().single().last
        assertTrue("was $outcome", outcome is RegistrationOutcome.Added)
    }

    @Test fun fileHeldByAListedProject_isHeldAndListed() = runTest {
        val r = registrar(others = listOf(FakeLookup("La Jolla Trip", hashes = setOf("hash-a.jpg"))))
        val outcome = r.register(listOf(candidate("a.jpg"))).toList().single().last
        assertEquals(RegistrationOutcome.HeldByOtherProject(File(mediaDir, "a.jpg").absolutePath, "La Jolla Trip", listed = true), outcome)
    }

    @Test fun sharedFiles_registerNothingUntilCopied() = runTest {
        val r = registrar(others = listOf(FakeLookup("La Jolla Trip", hashes = setOf("hash-a.jpg"))))
        r.register(listOf(candidate("a.jpg"), candidate("b.jpg"))).toList()
        assertEquals(listOf("b.jpg"), store.items.map { it.originalName })
    }

    /** "A copy has the same hash as the other project's file, but no file of this project." */
    @Test fun aCopy_isAnOrdinaryItemThoughAnotherProjectHasTheSameHash() = runTest {
        val r = registrar(others = listOf(FakeLookup("La Jolla Trip", hashes = setOf("hash-a.jpg"))))
        val copy = candidate("a.jpg", File(thisRef.path, "Copied from La Jolla Trip"))

        assertTrue(r.registerCopy(copy))

        val item = store.items.single()
        assertEquals(copy.file.absolutePath, item.originalPath)
        assertEquals(copy.file.absolutePath, item.currentPath)
        assertEquals(ItemStatus.Pending, item.status)
    }

    /** The copier has just checked the file by SHA-256: it isn't read a fifth time. */
    @Test fun aCopy_withItsHashKnown_isNotHashedAgain() = runTest {
        val r = registrar()
        assertTrue(r.registerCopy(candidate("a.jpg", File(thisRef.path, "Copied from X")), contentHash = "known"))
        assertEquals("known", store.items.single().contentHash)
        assertEquals(0, hashCalls)
    }

    @Test fun aCopyOfSomethingThisProjectHolds_isRefused() = runTest {
        val r = registrar()
        r.register(listOf(candidate("a.jpg"))).toList()
        assertEquals(false, r.registerCopy(candidate("a.jpg", File(thisRef.path, "Copied from X"))))
        assertEquals(1, store.items.size)
    }
}
