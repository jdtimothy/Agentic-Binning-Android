package com.agentic.binning.ui.project

import com.agentic.binning.project.ProjectRef
import com.agentic.binning.ui.theme.DarkGlass
import com.agentic.binning.ui.theme.LightGlass
import com.agentic.binning.ui.theme.contrastRatio
import com.agentic.binning.ui.theme.over
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectSheetModelTest {
    private val trip = ProjectRef("Trip", "/storage/emulated/0/Agentic Binning/Trip")
    private val maui = ProjectRef("Maui", "/storage/emulated/0/Agentic Binning/Maui")
    private val beach = ProjectRef("Beach", "/storage/emulated/0/Agentic Binning/Beach")

    @Test
    fun countsLineShowsItemsAndWhatIsLeft() {
        assertEquals("136 items · 31 left", countsLine(total = 136, left = 31, locale = Locale.US))
    }

    @Test
    fun countsLineDropsLeftWhenNothingIsLeft() {
        assertEquals("136 items", countsLine(total = 136, left = 0, locale = Locale.US))
    }

    @Test
    fun countsLineUsesSingularForOneItem() {
        assertEquals("1 item · 1 left", countsLine(total = 1, left = 1, locale = Locale.US))
    }

    @Test
    fun countsLineGroupsThousands() {
        assertEquals("1,204 items · 1,000 left", countsLine(total = 1204, left = 1000, locale = Locale.US))
    }

    @Test
    fun otherProjectsDropsTheCurrentOneByPathAndKeepsOrder() {
        val renamedCurrent = ProjectRef("Renamed", maui.path)
        assertEquals(listOf(trip, beach), otherProjects(listOf(trip, maui, beach), renamedCurrent))
    }

    @Test
    fun otherProjectsWithoutCurrentIsEveryProject() {
        assertEquals(listOf(trip, maui, beach), otherProjects(listOf(trip, maui, beach), null))
    }

    @Test
    fun createButtonLabelDependsOnTheSelection() {
        assertEquals("Create project", createButtonLabel(null))
        assertEquals("Create and analyze", createButtonLabel(MediaChoice.Folder("DCIM/Beach")))
        assertEquals("Create and analyze", createButtonLabel(MediaChoice.Files(3)))
    }

    @Test
    fun selectionLabelCountsFiles() {
        assertEquals("1 photo or clip", selectionLabel(MediaChoice.Files(1), Locale.US))
        assertEquals("24 photos and clips", selectionLabel(MediaChoice.Files(24), Locale.US))
    }

    @Test
    fun selectionLabelShowsTheFolderPath() {
        assertEquals("DCIM/Beach", selectionLabel(MediaChoice.Folder("DCIM/Beach"), Locale.US))
    }

    @Test
    fun sharedStoragePathIsRelativeToInternalStorage() {
        assertEquals("DCIM/Beach", sharedStoragePath("/storage/emulated/0/DCIM/Beach"))
        assertEquals("Movies/avb-plan1", sharedStoragePath("/storage/emulated/0/Movies/avb-plan1/"))
    }

    @Test
    fun sharedStoragePathNamesTheRootAndOtherVolumes() {
        assertEquals("Internal storage", sharedStoragePath("/storage/emulated/0"))
        assertEquals("1234-ABCD/DCIM", sharedStoragePath("/storage/1234-ABCD/DCIM"))
    }

    @Test
    fun startPageIsMainEvenWithoutAProject() {
        // Plan 2b P7d: with no projects the main page shows Open project and New project.
        assertEquals(SheetPage.Main, startPage(null))
        assertEquals(SheetPage.Main, startPage(trip))
    }

    @Test
    fun openProjectPickIsRoutedToOpenProject() {
        assertEquals(PickRoute.OpenProject, routePick(PickFor.OpenProject, hasResult = true))
        assertEquals(PickRoute.Ignore, routePick(PickFor.OpenProject, hasResult = false))
    }

    // ---- Current projects (plan 2b Task 6A) ----

    private fun ui(
        projects: List<ProjectRef> = listOf(maui, trip, beach),
        current: ProjectRef? = trip,
        missing: Set<String> = emptySet(),
        editing: Boolean = false,
        busy: Boolean = false,
    ) = currentProjectsUi(projects, current, "136 items · 31 left", missing, editing, busy)

    @Test
    fun theOpenProjectIsListedFirstWithItsCounts() {
        val u = ui()
        assertEquals(listOf(trip, maui, beach), u.rows.map { it.ref })
        assertEquals(listOf(true, false, false), u.rows.map { it.isOpen })
        assertEquals("136 items · 31 left", u.rows[0].subline)
        assertEquals(listOf(RowEnd.None, RowEnd.Chevron, RowEnd.Chevron), u.rows.map { it.end })
        assertEquals(listOf(false, true, true), u.rows.map { it.switches })
        assertTrue(u.showButtons)
        assertEquals("Edit", u.editLabel)
        assertEquals(null, u.emptyText)
    }

    @Test
    fun edit_showsRemoveOnEveryRowAndHidesTheTwoButtons() {
        val u = ui(editing = true)
        assertEquals(listOf(RowEnd.Remove, RowEnd.Remove, RowEnd.Remove), u.rows.map { it.end })
        assertTrue(u.rows.none { it.switches })
        assertEquals(false, u.showButtons)
        assertEquals("Done", u.editLabel)
    }

    @Test
    fun edit_disabledWhileRegisteringOrCreating() {
        assertEquals(false, ui(busy = true).editEnabled)
        assertEquals("Done stays usable", true, ui(busy = true, editing = true).editEnabled)
        assertEquals(true, ui().editEnabled)
    }

    @Test
    fun edit_endsWhenTheListEmpties() {
        assertEquals(false, editingAfterRemoval(editing = true, remaining = 0))
        assertEquals(true, editingAfterRemoval(editing = true, remaining = 2))
        assertEquals(false, editingAfterRemoval(editing = false, remaining = 2))
        val empty = ui(projects = emptyList(), current = null, editing = true)
        assertEquals(true, empty.showButtons)
        assertEquals(null, empty.editLabel)
    }

    @Test
    fun noProjects_showsTheTwoButtons() {
        val u = ui(projects = emptyList(), current = null)
        assertEquals(emptyList<ProjectRowUi>(), u.rows)
        assertTrue(u.showButtons)
        assertEquals("No projects yet", u.emptyText)
        assertEquals(null, u.editLabel)
    }

    @Test
    fun missingFolder_isMarkedAndStillRemovable() {
        val u = ui(missing = setOf(beach.path), editing = true)
        val row = u.rows.single { it.ref == beach }
        assertEquals("Folder missing", row.subline)
        assertTrue(row.warn)
        assertEquals(RowEnd.Remove, row.end)
    }

    @Test
    fun noOpenProject_listsEveryProjectAsSwitchable() {
        val u = ui(current = null)
        assertEquals(listOf(maui, trip, beach), u.rows.map { it.ref })
        assertTrue(u.rows.all { it.switches && !it.isOpen })
    }

    @Test
    fun removeCopyIsShort() {
        assertEquals("Stop analysis and remove Trip?", removeConfirmTitle("Trip"))
        assertEquals("Removed Trip", removedToastText("Trip"))
    }

    @Test
    fun undoToastReservesSpaceSoRowsSitAboveIt() {
        assertEquals(0, sheetBottomInsetForUndo(showingUndo = false))
        // Toast min height 52 + 16 dp padding above and below; the list inset must cover that
        // so an × is never under the toast (phone check: Undo tap hit the last row's ×).
        assertTrue(sheetBottomInsetForUndo(showingUndo = true) >= 84)
    }

    @Test
    fun pickFromTheMainPageIsAddedToTheCurrentProject() {
        assertEquals(PickRoute.AddToCurrent, routePick(PickFor.Current, hasResult = true))
    }

    @Test
    fun pickFromTheNewProjectPageIsHeld() {
        assertEquals(PickRoute.HoldForNewProject, routePick(PickFor.NewProject, hasResult = true))
    }

    @Test
    fun pickWithoutARecordedTargetIsIgnoredNeverAdded() {
        assertEquals(PickRoute.Ignore, routePick(null, hasResult = true))
    }

    @Test
    fun cancelledPickIsIgnored() {
        assertEquals(PickRoute.Ignore, routePick(PickFor.Current, hasResult = false))
        assertEquals(PickRoute.Ignore, routePick(PickFor.NewProject, hasResult = false))
    }

    @Test
    fun darkOutlineIsTheBorderToken() {
        assertEquals(DarkGlass.border, sheetOutline(DarkGlass))
    }

    @Test
    fun lightOutlineIsVisibleOnTheSheet() {
        val surface = LightGlass.glassStrong.over(LightGlass.bg)
        assertTrue(contrastRatio(sheetOutline(LightGlass).over(surface), surface) >= 3.0)
    }
}
