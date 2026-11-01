package com.agentic.binning.projectmedia

import com.agentic.binning.sorting.SortValidator
import com.agentic.binning.sorting.cleanName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CategoryNamesTest {
    @Test fun key_ignoresCaseSpacingPunctuationAndPlurals() {
        val k = CategoryNames.key("Sea Lion")
        assertEquals(k, CategoryNames.key("sea lions"))
        assertEquals(k, CategoryNames.key("Sea-lions!"))
        assertEquals(CategoryNames.key("Turtle Beach"), CategoryNames.key("turtle beaches"))
        assertEquals(CategoryNames.key("Berry"), CategoryNames.key("Berries"))
        assertEquals(CategoryNames.key("Fox"), CategoryNames.key("Foxes"))
    }

    @Test fun key_treatsBothApostrophesAlike() {
        assertEquals(CategoryNames.key("Mama’s Fish House"), CategoryNames.key("Mama's Fish House"))
    }

    @Test fun key_keepsDoubleS() {
        assertEquals("glass", CategoryNames.key("Glass"))
    }

    /** Editing uses the sorter's key, so the two can never disagree about a clash. */
    @Test fun key_isTheSortersNormalize() {
        for (name in listOf("Sea Lions", "The Beaches", "Café Visits", "Mama's Fish House", "Bus", "x")) {
            assertEquals(SortValidator.normalize(name), CategoryNames.key(name))
        }
    }

    @Test fun key_dropsArticlesAndDiacriticsLikeTheSorter() {
        assertEquals(CategoryNames.key("Beach"), CategoryNames.key("The Beach"))
        assertEquals(CategoryNames.key("Cafe Visit"), CategoryNames.key("Café Visits"))
    }

    /**
     * Pinned on purpose: the Oct 31 draft's own key treated these as the same name; 2a's normalize
     * does not, and editing follows 2a (Task 6, open question P3).
     */
    @Test fun key_differencesFromTheDraftKey_followThe2aSorter() {
        assertNotEquals(CategoryNames.key("Sea Lion"), CategoryNames.key("SEALIONS"))
        assertNotEquals(CategoryNames.key("Mama's Fish House"), CategoryNames.key("Mamas Fish House"))
        assertNotEquals(CategoryNames.key("Sea Lion Beach"), CategoryNames.key("Sea Lions Beach"))
    }

    @Test fun clash_findsTheExistingName() {
        val existing = listOf("Turtle Beach", "Beach Scenes", "Wedding Events")
        assertEquals("Turtle Beach", CategoryNames.clash("turtle beaches", existing))
        assertNull(CategoryNames.clash("Snorkeling", existing))
    }

    @Test fun clash_ignoresTheCategoryBeingRenamed() {
        val existing = listOf("Beach Scene", "Wedding Events")
        assertNull(CategoryNames.clash("Beach Scenes", existing, except = "Beach Scene"))
        assertEquals("Wedding Events", CategoryNames.clash("wedding event", existing, except = "Beach Scene"))
    }

    @Test fun clash_blankNameNeverClashes() {
        assertNull(CategoryNames.clash(" !! ", listOf("A")))
    }

    @Test fun clean_trimsCollapsesAndCapsAtTheSortersLimit() {
        assertEquals("Sea Turtles", CategoryNames.clean("  Sea   Turtles "))
        assertEquals(SortValidator.MAX_CATEGORY_NAME, CategoryNames.MAX_LENGTH)
        assertEquals(CategoryNames.MAX_LENGTH, CategoryNames.clean("x".repeat(200)).length)
    }

    @Test fun clean_isTheSortersCleanName() {
        assertEquals("Beach Sunset", CategoryNames.clean("Beach/Sunset?"))
        assertEquals("Trip", CategoryNames.clean("Trip..."))
        for (name in listOf("  a  b ", "x".repeat(50), "A:B*C", "Name. ")) {
            assertEquals(cleanName(name, SortValidator.MAX_CATEGORY_NAME), CategoryNames.clean(name))
        }
    }
}
