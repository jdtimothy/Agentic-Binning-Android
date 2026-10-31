package com.agentic.binning.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimingTest {
    @Test fun sampleTimes_shortClipAt2fps() =
        assertEquals((0L until 16L).map { it * 500 }, VideoTiming.sampleTimesMs(8_000))

    @Test fun sampleTimes_longClipSpreadEvenly() =
        assertEquals((0L until 16L).map { 14_000 * it / 16 }, VideoTiming.sampleTimesMs(14_000))

    @Test fun sampleTimes_extremeDurations() {
        val hour = VideoTiming.sampleTimesMs(3_600_000)
        assertEquals(16, hour.size)
        assertTrue(hour.last() < 3_600_000)
        assertEquals(listOf(0L), VideoTiming.sampleTimesMs(300))
        assertEquals(listOf(0L), VideoTiming.sampleTimesMs(0))
        assertEquals(listOf(0L), VideoTiming.sampleTimesMs(-5))
    }

    @Test fun timestamps_first_andEvery5s() =
        assertEquals(setOf(0, 10), VideoTiming.timestampedIndices(VideoTiming.sampleTimesMs(8_000)))

    @Test fun format_matchesLlamaCpp() {
        assertEquals("[0m0.00s]", VideoTiming.format(0))
        assertEquals("[1m5.50s]", VideoTiming.format(65_500))
    }
}
