package com.agentic.binning.media

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RegistrationSummaryTest {
    private fun summaryOf(vararg outcomes: RegistrationOutcome): RegistrationSummary =
        outcomes.fold(RegistrationSummary()) { acc, o -> acc + o }

    @Test fun progressText_usesGroupingSeparators() {
        val progress = RegistrationProgress(340, 1200, RegistrationOutcome.Added("/a"))
        assertEquals("Adding 340 of 1,200…", registrationProgressText(progress, Locale.US))
    }

    @Test fun text_listsNonZeroPartsInOrder() {
        val outcomes = buildList<RegistrationOutcome> {
            repeat(1180) { add(RegistrationOutcome.Added("/a$it")) }
            repeat(15) { add(RegistrationOutcome.DuplicateInProject("/d$it")) }
            repeat(5) { add(RegistrationOutcome.HeldByOtherProject("/h$it", "La Jolla Trip")) }
        }
        assertEquals(
            "1,180 added · 15 already in this project · 5 in another project (La Jolla Trip)",
            summaryOf(*outcomes.toTypedArray()).text(Locale.US),
        )
    }

    @Test fun text_omitsZeroParts() {
        assertEquals(
            "2 added",
            summaryOf(RegistrationOutcome.Added("/a"), RegistrationOutcome.Added("/b")).text(Locale.US),
        )
        assertEquals(
            "1 already in this project",
            summaryOf(RegistrationOutcome.DuplicateInProject("/a")).text(Locale.US),
        )
    }

    @Test fun text_includesUnreadable() {
        assertEquals(
            "1 added · 2 couldn't be read",
            summaryOf(
                RegistrationOutcome.Added("/a"),
                RegistrationOutcome.Unreadable("/b", "io"),
                RegistrationOutcome.Unreadable("/c", "io"),
            ).text(Locale.US),
        )
    }

    @Test fun text_namesEveryOtherProject() {
        assertEquals(
            "3 in other projects (A, B)",
            summaryOf(
                RegistrationOutcome.HeldByOtherProject("/a", "A"),
                RegistrationOutcome.HeldByOtherProject("/b", "B"),
                RegistrationOutcome.HeldByOtherProject("/c", "A"),
            ).text(Locale.US),
        )
    }

    @Test fun text_whenNothingFound() {
        assertEquals("No photos or videos found", RegistrationSummary().text(Locale.US))
    }

    @Test fun skipped_countsAsUnreadable() {
        assertEquals("2 couldn't be read", RegistrationSummary(skipped = 2).text(Locale.US))
    }
}
