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
    fun startPageIsNewProjectWithoutACurrentProject() {
        assertEquals(SheetPage.NewProject, startPage(null))
        assertEquals(SheetPage.Main, startPage(trip))
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
