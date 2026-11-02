package com.agentic.binning.project

import com.agentic.binning.db.PROJECT_DB_IDENTITY_HASHES
import com.agentic.binning.db.PROJECT_DB_VERSION
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectOpenRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val current = PROJECT_DB_VERSION
    private val hashes = PROJECT_DB_IDENTITY_HASHES
    private val good = DatabaseFacts(
        exists = true, readable = true, userVersion = current, identityHash = hashes.getValue(current),
        metaName = "Hawaii Photos", hasMeta = true, quickCheckOk = true,
    )

    private fun decide(facts: DatabaseFacts) = ProjectOpenRules.decide(facts, current, hashes)
    private fun refused(reason: OpenRefusal) = DatabaseDecision.Refused(reason)

    @Test fun currentVersion_opensWithoutBackup() =
        assertEquals(DatabaseDecision.Open(backupFromVersion = null), decide(good))

    @Test fun olderVersion_opensAfterABackup() {
        val v2 = good.copy(userVersion = 2, identityHash = hashes.getValue(2))
        assertEquals(DatabaseDecision.Open(backupFromVersion = 2), decide(v2))
    }

    @Test fun missingDatabase_isNoProject() = assertEquals(refused(OpenRefusal.NoProject), decide(DatabaseFacts(exists = false)))

    @Test fun unreadableOrDamaged_cantOpen() {
        assertEquals(refused(OpenRefusal.CantOpen), decide(DatabaseFacts(exists = true, readable = false)))
        assertEquals(refused(OpenRefusal.CantOpen), decide(good.copy(quickCheckOk = false)))
        assertEquals(refused(OpenRefusal.CantOpen), decide(good.copy(userVersion = null)))
    }

    @Test fun newerVersion_isRefusedAsNewer() =
        assertEquals(refused(OpenRefusal.Newer), decide(good.copy(userVersion = current + 1, identityHash = "anything")))

    @Test fun foreignOrMismatchedHash_cantOpen() {
        assertEquals(refused(OpenRefusal.CantOpen), decide(good.copy(identityHash = null)))
        assertEquals(refused(OpenRefusal.CantOpen), decide(good.copy(identityHash = "0000")))
        assertEquals("v3's hash on a v2 file", refused(OpenRefusal.CantOpen), decide(good.copy(userVersion = 2)))
    }

    @Test fun noMetaRow_cantOpen() = assertEquals(refused(OpenRefusal.CantOpen), decide(good.copy(hasMeta = false, metaName = null)))

    @Test fun onlyCurrentAndOlderSchemasOpen() {
        val outcomes = listOf(
            good, good.copy(userVersion = 1, identityHash = hashes.getValue(1)), DatabaseFacts(exists = false),
            DatabaseFacts(exists = true), good.copy(userVersion = current + 1), good.copy(identityHash = "x"), good.copy(hasMeta = false),
        ).map { decide(it) is DatabaseDecision.Open }
        assertEquals(listOf(true, true, false, false, false, false, false), outcomes)
    }

    @Test fun listedName_prefersTheProjectsOwnName() {
        val dir = File("/storage/emulated/0/Agentic Binning/Hawaii")
        assertEquals("Hawaii Photos", ProjectOpenRules.listedName(good, dir))
        assertEquals("Hawaii", ProjectOpenRules.listedName(good.copy(metaName = "  "), dir))
    }

    @Test fun pickingTheBinningFolder_meansItsProject() {
        val project = File(tmp.root, "Hawaii")
        assertEquals(project.absoluteFile, ProjectOpenRules.projectDir(File(project, ".binning")))
        assertEquals(project, ProjectOpenRules.projectDir(project))
    }

    @Test fun alreadyListed_opensTheEntry() {
        val ref = ProjectRef("Hawaii Photos", File(tmp.root, "Hawaii").path)
        val placement = ProjectOpenRules.placement(File(tmp.root, "x/../Hawaii/").path, listOf(ref))
        assertEquals(Placement.Listed(ref), placement)
        assertNull(ProjectOpenRules.placementMessage(placement))
    }

    @Test fun insideOrContainingAProject_isRefused() {
        val ref = ProjectRef("Hawaii Photos", File(tmp.root, "Hawaii").path)
        val inside = ProjectOpenRules.placement(File(tmp.root, "Hawaii/Beach").path, listOf(ref))
        val contains = ProjectOpenRules.placement(tmp.root.path, listOf(ref))
        assertEquals(Placement.Inside(ref), inside)
        assertEquals(Placement.Contains(ref), contains)
        assertEquals("This folder is inside Hawaii Photos", ProjectOpenRules.placementMessage(inside))
        assertEquals("This folder contains Hawaii Photos", ProjectOpenRules.placementMessage(contains))
        assertEquals(Placement.Unlisted, ProjectOpenRules.placement(File(tmp.root, "HawaiiTrip").path, listOf(ref)))
    }

    @Test fun backupFile_neverReusesAName() {
        val db = File(tmp.root, ".binning/project.db")
        val taken = mutableSetOf<File>()
        val first = ProjectOpenRules.backupFile(db, 2) { it in taken }
        assertEquals("project.db.v2.bak", first.name)
        taken += first
        val second = ProjectOpenRules.backupFile(db, 2) { it in taken }
        assertEquals("project.db.v2.1.bak", second.name)
    }

    @Test fun nextAfterRemoval_isTheFirstOpenableInListOrder() {
        val a = ProjectRef("A", "/p/A"); val b = ProjectRef("B", "/p/B"); val c = ProjectRef("C", "/p/C")
        assertEquals(c, ProjectOpenRules.nextAfterRemoval(listOf(a, b, c)) { it == c })
        assertEquals(b, ProjectOpenRules.nextAfterRemoval(listOf(a, b, c)) { it != a })
        assertNull(ProjectOpenRules.nextAfterRemoval(listOf(a, b)) { false })
        assertNull(ProjectOpenRules.nextAfterRemoval(emptyList()) { true })
    }

    @Test fun refusalMessagesStayShort() {
        assertEquals("No project in this folder", OpenRefusal.NoProject.message)
        assertEquals("Made with a newer app version. Update to open it.", OpenRefusal.Newer.message)
        OpenRefusal.entries.forEach { assert(it.message.length <= 60) { it.message } }
    }
}
