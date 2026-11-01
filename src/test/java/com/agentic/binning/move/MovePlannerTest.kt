package com.agentic.binning.move

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MovePlannerTest {
    private val p = "/storage/emulated/0/Agentic Binning/Hawaii Photos"

    private fun req(id: Long, source: String, folder: String, name: String, ext: String = ".jpg") =
        MoveRequest(id, source, folder, name, ext)

    @Test fun movesIntoTheCategoryFolderUnderTheFriendlyName() {
        val plan = MovePlanner.plan(p, listOf(req(1, "/sdcard/DCIM/Camera/20260715_102000.jpg", "Turtle Beach", "Sea Turtles Resting Sand")), emptySet())
        assertEquals(listOf(PlannedMove(1, "/sdcard/DCIM/Camera/20260715_102000.jpg", "$p/Turtle Beach/Sea Turtles Resting Sand.jpg")), plan)
    }

    @Test fun neverOverwritesAFileOnDisk() {
        val existing = setOf("$p/Beach Scenes/Empty Beach.jpg")
        val plan = MovePlanner.plan(p, listOf(req(1, "/sdcard/a.jpg", "Beach Scenes", "Empty Beach")), existing)
        assertEquals("$p/Beach Scenes/Empty Beach 2.jpg", plan.single().dest)
    }

    @Test fun sameNameTwiceInOnePlan_getsNumbered() {
        val plan = MovePlanner.plan(
            p,
            listOf(req(3, "/sdcard/c.jpg", "Sunset", "Sunset"), req(1, "/sdcard/a.jpg", "Sunset", "Sunset"), req(2, "/sdcard/b.jpg", "Sunset", "Sunset")),
            emptySet(),
        )
        assertEquals(listOf("Sunset.jpg", "Sunset 2.jpg", "Sunset 3.jpg"), plan.map { it.dest.substringAfterLast('/') })
        assertEquals(listOf(1L, 2L, 3L), plan.map { it.itemId })
    }

    @Test fun namesCompareIgnoringCase() {
        val existing = setOf("$p/Sunset/SUNSET.JPG")
        val plan = MovePlanner.plan(p, listOf(req(1, "/sdcard/a.jpg", "sunset", "Sunset")), existing)
        assertEquals("$p/sunset/Sunset 2.jpg", plan.single().dest)
    }

    @Test fun fileAlreadyInPlace_isNotMoved_andKeepsItsName() {
        val inPlace = "$p/Sunset/Sunset.jpg"
        val plan = MovePlanner.plan(
            p,
            listOf(req(9, inPlace, "Sunset", "Sunset"), req(1, "/sdcard/new.jpg", "Sunset", "Sunset")),
            setOf(inPlace),
        )
        assertEquals(listOf(PlannedMove(1, "/sdcard/new.jpg", "$p/Sunset/Sunset 2.jpg")), plan)
    }

    @Test fun earlierNumberedName_isKeptWhenThePlainNameIsFree() {
        val numbered = "$p/Sunset/Sunset 2.jpg"
        val plan = MovePlanner.plan(p, listOf(req(1, numbered, "Sunset", "Sunset")), setOf(numbered))
        assertTrue(plan.isEmpty())
    }

    @Test fun caseOnlyDifference_countsAsInPlace() {
        val src = "$p/Sunset/sunset.jpg"
        assertTrue(MovePlanner.plan(p, listOf(req(1, src, "Sunset", "Sunset")), setOf(src)).isEmpty())
    }

    @Test fun renamedItem_movesWithinItsFolder() {
        val src = "$p/Green Lizard/Gecko on Wooden Wall.jpg"
        val plan = MovePlanner.plan(p, listOf(req(1, src, "Green Lizard", "Gecko on the Barn Wall")), setOf(src))
        assertEquals("$p/Green Lizard/Gecko on the Barn Wall.jpg", plan.single().dest)
    }

    @Test fun swappedNames_neverOverwrite() {
        val a = "$p/X/A.jpg"
        val b = "$p/X/B.jpg"
        val plan = MovePlanner.plan(p, listOf(req(1, a, "X", "B"), req(2, b, "X", "A")), setOf(a, b))
        assertEquals(listOf("$p/X/B 2.jpg", "$p/X/A 2.jpg"), plan.map { it.dest })
    }

    @Test fun unsafeNames_areMadeSafe_andEmptyNamesFallBack() {
        val plan = MovePlanner.plan(
            p,
            listOf(
                req(1, "/sdcard/x/20260718_175109.mp4", "Food/Drink: Lunch?", "Shrimp \"Truck\" Plates", "mp4"),
                req(2, "/sdcard/x/20260718_175110.MP4", "...", "  ", ".MP4"),
            ),
            emptySet(),
        )
        assertEquals("$p/Food_Drink_ Lunch_/Shrimp _Truck_ Plates.mp4", plan[0].dest)
        assertEquals("$p/Category/20260718_175110.MP4", plan[1].dest)
    }

    @Test fun projectDirTrailingSlash_isIgnored() {
        val plan = MovePlanner.plan("$p/", listOf(req(1, "/sdcard/a.jpg", "A", "B")), emptySet())
        assertEquals("$p/A/B.jpg", plan.single().dest)
    }
}
