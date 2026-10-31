package com.agentic.binning.ui.analyze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Task 10 ruling: a time chip past the end of the clip is hidden; the action text stays. */
class ActionTimeTest {
    @Test fun timeWithinClip_isShown() {
        assertEquals("0:03", actionTimeChip("0:03", 3400))
        assertEquals("0:00", actionTimeChip("0:00", 2000))
    }

    @Test fun timePastClipEnd_isHidden() {
        assertNull(actionTimeChip("0:04", 3400))
        assertNull(actionTimeChip("1:00", 8000))
    }

    @Test fun unknownDurationOrOddTime_isShownAsIs() {
        assertEquals("0:09", actionTimeChip("0:09", null))
        assertEquals("soon", actionTimeChip("soon", 8000))
    }

    @Test fun formatDuration_isMinutesAndSeconds() {
        assertEquals("0:08", formatDuration(8000))
        assertEquals("1:05", formatDuration(65_400))
    }
}
