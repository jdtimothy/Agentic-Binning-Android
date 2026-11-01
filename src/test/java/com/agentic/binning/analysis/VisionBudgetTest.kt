package com.agentic.binning.analysis

import org.junit.Assert.assertEquals
import org.junit.Test

class VisionBudgetTest {
    @Test fun contextFor_16FramesStaysAt16384() = assertEquals(16384, VisionBudget.contextFor(16, 256))

    @Test fun contextFor_64Frames() = assertEquals(20480, VisionBudget.contextFor(64, 256))

    @Test fun contextFor_neverBelow16384() {
        assertEquals(16384, VisionBudget.contextFor(1, 64))
        assertEquals(16384, VisionBudget.contextFor(24, 256))
    }

    @Test fun contextFor_roundsUpTo4096() {
        // 32 * 268 + 2048 = 10624 -> floor of 16384; 48 * 268 + 2048 = 14912 -> floor.
        assertEquals(16384, VisionBudget.contextFor(48, 256))
        // A bigger tower: 64 * (576 + 12) + 2048 = 39680 -> 40960.
        assertEquals(40960, VisionBudget.contextFor(64, 576))
    }

    @Test fun hint_32Frames() = assertEquals("1-minute clip: 32 frames, one every 1.9 s", VisionBudget.hint(32))

    @Test fun hint_16Frames() = assertEquals("1-minute clip: 16 frames, one every 3.8 s", VisionBudget.hint(16))

    @Test fun hint_capAbove120IsLimitedToTwoFps() = assertEquals("1-minute clip: 120 frames, one every 0.5 s", VisionBudget.hint(200))
}
