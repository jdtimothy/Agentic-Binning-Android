package com.agentic.binning.ui.analyze

import androidx.compose.animation.core.CubicBezierEasing
import com.agentic.binning.ui.theme.GlassTokens

/** Where one carousel card sits, from the mockup's CSS transforms. */
data class CardPose(
    val translationXDp: Float,
    val rotationYDeg: Float,
    val scale: Float,
    val alpha: Float,
    /** RGB multiplier for the card's content (1 = unchanged). */
    val brightness: Float,
    val z: Float,
)

/** Cards move between poses over this long, with [CardPoseEasing]. */
const val CARD_POSE_MILLIS = 950

/** The mockup's `cubic-bezier(.22,.8,.24,1)`. */
val CardPoseEasing = CubicBezierEasing(0.22f, 0.8f, 0.24f, 1f)

/**
 * The pose of the card at [offset] from the current one (positive: still to come, on the right;
 * negative: done, on the left). The carousel does not wrap, so [count] does not change the pose;
 * cards two or more away are parked off-screen and fully transparent.
 */
fun cardPose(offset: Int, @Suppress("UNUSED_PARAMETER") count: Int, tokens: GlassTokens): CardPose = when {
    offset == 0 -> CardPose(0f, 0f, 1f, 1f, 1f, 5f)
    offset == 1 -> CardPose(200f, -38f, 0.84f, 1f, tokens.dimNext, 4f)
    offset == -1 -> CardPose(-200f, 38f, 0.84f, 1f, tokens.dimPrev, 4f)
    offset > 1 -> CardPose(520f, -70f, 0.7f, 0f, tokens.dimNext, 1f)
    else -> CardPose(-520f, 70f, 0.7f, 0f, tokens.dimPrev, 1f)
}
