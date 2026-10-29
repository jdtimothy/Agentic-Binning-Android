package com.agentic.binning.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

/** Design tokens for the "Apple-style glass" look, copied from the mockups. */
data class GlassTokens(
    val bg: Color,
    val glass: Color,
    val glassStrong: Color,
    val border: Color,
    val highlight: Color,
    val text: Color,
    val sub: Color,
    val chip: Color,
    val accent: Color,
    val onAccent: Color,
    val track: Color,
    val warn: Color,
    val tagBg: Color,
    val tagText: Color,
    val blobAlpha: Float,
    /** Brightness multiplier for the card after the current one. */
    val dimNext: Float,
    /** Brightness multiplier for the card before the current one. */
    val dimPrev: Float,
)

private fun rgba(r: Int, g: Int, b: Int, a: Float) =
    Color(red = r / 255f, green = g / 255f, blue = b / 255f, alpha = a)

val DarkGlass = GlassTokens(
    bg = Color(0xFF06070B),
    glass = rgba(24, 27, 36, 0.52f),
    glassStrong = rgba(30, 34, 45, 0.66f),
    border = rgba(255, 255, 255, 0.13f),
    highlight = rgba(255, 255, 255, 0.20f),
    text = Color(0xFFF4F6FA),
    sub = rgba(226, 232, 244, 0.72f),
    chip = rgba(255, 255, 255, 0.08f),
    accent = Color(0xFF6CB6FF),
    onAccent = Color(0xFF04121F),
    track = rgba(255, 255, 255, 0.14f),
    warn = Color(0xFFFFB547),
    tagBg = rgba(108, 182, 255, 0.14f),
    tagText = Color(0xFFD3E8FF),
    blobAlpha = 0.55f,
    dimNext = 0.55f,
    dimPrev = 0.42f,
)

val LightGlass = GlassTokens(
    bg = Color(0xFFE8ECF2),
    glass = rgba(255, 255, 255, 0.55f),
    glassStrong = rgba(255, 255, 255, 0.72f),
    border = rgba(255, 255, 255, 0.85f),
    highlight = rgba(255, 255, 255, 1.0f),
    text = Color(0xFF0E1116),
    sub = Color(0xFF4A5263),
    chip = rgba(20, 28, 45, 0.06f),
    accent = Color(0xFF0A63D1),
    onAccent = Color(0xFFFFFFFF),
    track = rgba(20, 28, 45, 0.10f),
    warn = Color(0xFFA35F00),
    tagBg = rgba(10, 99, 209, 0.09f),
    tagText = Color(0xFF0A4A9C),
    blobAlpha = 0.5f,
    dimNext = 0.82f,
    dimPrev = 0.74f,
)

val LocalGlassTokens = compositionLocalOf { DarkGlass }

/** Alpha-composites this colour over [bg]. */
fun Color.over(bg: Color): Color = this.compositeOver(bg)

/** WCAG 2.x contrast ratio between two colours (alpha is ignored; composite first with [over]). */
fun contrastRatio(fg: Color, bg: Color): Double {
    val l1 = fg.luminance().toDouble()
    val l2 = bg.luminance().toDouble()
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}
