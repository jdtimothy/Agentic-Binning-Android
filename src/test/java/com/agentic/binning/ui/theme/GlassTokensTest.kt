package com.agentic.binning.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTokensTest {
    @Test fun dark_bodyText_meetsAA() {
        val surface = DarkGlass.glass.over(DarkGlass.bg)
        assertTrue(contrastRatio(DarkGlass.text, surface) >= 4.5)
        assertTrue(contrastRatio(DarkGlass.sub.over(surface), surface) >= 4.5)
    }

    @Test fun light_bodyText_meetsAA() {
        val surface = LightGlass.glass.over(LightGlass.bg)
        assertTrue(contrastRatio(LightGlass.text, surface) >= 4.5)
        assertTrue(contrastRatio(LightGlass.sub, surface) >= 4.5)
    }

    @Test fun accentButtonText_meetsAA() {
        assertTrue(contrastRatio(DarkGlass.onAccent, DarkGlass.accent) >= 4.5)
        assertTrue(contrastRatio(LightGlass.onAccent, LightGlass.accent) >= 4.5)
    }
}
