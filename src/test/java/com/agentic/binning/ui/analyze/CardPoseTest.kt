package com.agentic.binning.ui.analyze

import com.agentic.binning.ui.theme.DarkGlass
import com.agentic.binning.ui.theme.LightGlass
import org.junit.Assert.assertEquals
import org.junit.Test

class CardPoseTest {
    @Test fun poses_matchMockup() {
        assertEquals(CardPose(0f, 0f, 1f, 1f, 1f, 5f), cardPose(0, 5, DarkGlass))
        assertEquals(CardPose(200f, -38f, 0.84f, 1f, 0.55f, 4f), cardPose(1, 5, DarkGlass))
        assertEquals(CardPose(-200f, 38f, 0.84f, 1f, 0.42f, 4f), cardPose(-1, 5, DarkGlass))
        assertEquals(0f, cardPose(2, 5, DarkGlass).alpha); assertEquals(0f, cardPose(-3, 5, DarkGlass).alpha)
        assertEquals(0.82f, cardPose(1, 5, LightGlass).brightness)
    }
}
