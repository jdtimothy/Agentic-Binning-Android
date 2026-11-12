package com.agentic.binning.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoMomentsTest {
    @Test fun timestamp_roundsHalfUpAndUsesUsDigits() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("0:00", VideoMoments.timestamp(-1))
            assertEquals("0:00", VideoMoments.timestamp(499))
            assertEquals("0:01", VideoMoments.timestamp(500))
            assertEquals("0:59", VideoMoments.timestamp(59_499))
            assertEquals("1:00", VideoMoments.timestamp(59_500))
            assertEquals("61:01", VideoMoments.timestamp(3_660_500))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }

    @Test fun distinct_usesUniqueLongWordsAndLargerSetDenominator() {
        val short = ActionEntry("0:00", "WAVES, waves! crash")
        val expanded = ActionEntry("0:01", "waves crash across distant rugged rocky shores")
        assertEquals(listOf(short, expanded), VideoMoments.distinct(listOf(short, expanded)))
        val half = ActionEntry("0:02", "wave crash rolling shore")
        val other = ActionEntry("0:03", "lizard climbing flower")
        assertEquals(listOf(short, other), VideoMoments.distinct(listOf(short, half, other)))
        val tiny = ActionEntry("0:00", "A cat on a mat")
        assertEquals(listOf(tiny, other), VideoMoments.distinct(listOf(tiny, ActionEntry("0:01", "The dog ran"), other)))
        val unicode = ActionEntry("0:00", "ÉLÉPHANTS marchent")
        assertEquals(listOf(unicode, other), VideoMoments.distinct(listOf(unicode, ActionEntry("0:01", "éléphant marche"), other)))
    }
    @Test fun distinct_keepsFirstPhraseForEachSubjectAndRequiresMultipleMoments() {
        val opening = ActionEntry("0:00", "A green lizard perches on a pink flower bud")
        val repeated = ActionEntry("0:04", "A small green lizard perches on a pink flower bud")
        val changed = ActionEntry("0:08", "Waves crash against rocks creating splashes")
        assertEquals(listOf(opening, changed), VideoMoments.distinct(listOf(opening, repeated, changed)))
        assertEquals(emptyList<ActionEntry>(), VideoMoments.distinct(listOf(opening, repeated)))
        assertEquals(emptyList<ActionEntry>(), VideoMoments.distinct(listOf(opening)))
        assertEquals(emptyList<ActionEntry>(), VideoMoments.distinct(emptyList()))
    }
    @Test fun select_picksEarliestIntervalMaximaUsingWindowsIntegerBoundaries() {
        // Seven later frames divide as [1], [2,3], [4,5], [6,7].
        assertEquals(listOf(0, 1, 2, 5, 6), VideoMoments.select(listOf(999.0, 8.0, 20.0, 20.0, 7.9, 8.0, 9.0, 8.0)))
        assertEquals(listOf(0, 1, 3), VideoMoments.select(listOf(0.0, 20.0, 7.99, 8.0)))
        assertEquals(emptyList<Int>(), VideoMoments.select(emptyList()))
        assertEquals(emptyList<Int>(), VideoMoments.select(listOf(999.0)))
        assertEquals(emptyList<Int>(), VideoMoments.select(listOf(999.0, 7.99)))
        assertEquals(listOf(0, 1), VideoMoments.select(listOf(0.0, 8.0)))
        assertEquals(listOf(0, 1, 3), VideoMoments.select(listOf(0.0, 100.0, 39.99, 40.0)))
    }
    @Test fun change_excludesOnlyPixelsBlackInBothFrames() {
        assertEquals(170.0, VideoMoments.change(intArrayOf(0x000f0f0f, 0x00ff0000), intArrayOf(0, 0x000000ff)), 0.0)
        assertEquals(16.0 / 3, VideoMoments.change(intArrayOf(0), intArrayOf(0x00100000)), 0.0)
        assertEquals(0.0, VideoMoments.change(intArrayOf(0x000f0f0f), intArrayOf(0)), 0.0)
        assertEquals(0.0, VideoMoments.change(intArrayOf(), intArrayOf()), 0.0)
    }
    @Test fun change_measuresRgbRatherThanBrightnessAndIgnoresAlpha() {
        assertEquals(170.0, VideoMoments.change(intArrayOf(0xffff0000.toInt()), intArrayOf(0x000000ff)), 0.0)
    }
}
