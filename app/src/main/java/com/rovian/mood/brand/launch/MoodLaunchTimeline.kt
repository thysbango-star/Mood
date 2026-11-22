package com.rovian.mood.brand.launch

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable

/** A span of the launch timeline, in milliseconds from the first frame. */
@Immutable
data class Beat(val start: Int, val end: Int) {
    init {
        require(end > start) { "Beat must have positive length: $start..$end" }
    }

    /** Linear 0..1 progress of [timeMs] through this beat. */
    fun raw(timeMs: Float): Float = ((timeMs - start) / (end - start)).coerceIn(0f, 1f)

    fun eased(timeMs: Float, easing: Easing): Float = easing.transform(raw(timeMs))
}

/**
 * Choreography of the official Mood launch animation.
 *
 * Phase 1  wordmarkIn, pillIn            — wordmark fades up; gradient capsule
 *                                          grows from a dot inside the 'o'
 * Phase 2  anticipation, travel          — capsule draws back, then travels
 *                                          right through the letters
 * Phase 3  expandWidth, expandHeight,    — clear of the logo it stretches
 *          squareCorners                   horizontally, then fills the screen
 *                                          and its ends square into a rectangle
 * Phase 4  collapseHeight, roundCorners, — reverse: back to a long capsule,
 *          returnToSlot                    then home into the outlined 'o'
 * Phase 5  holdUntil, exitDuration       — completed logo holds, then the
 *                                          overlay dissolves into the app
 *
 * Kept as data so the brand team can tune timing without touching rendering.
 */
@Immutable
data class MoodLaunchTimeline(
    val wordmarkIn: Beat = Beat(0, 520),
    val pillIn: Beat = Beat(240, 760),
    val anticipation: Beat = Beat(860, 1020),
    val travel: Beat = Beat(1020, 1480),
    val expandWidth: Beat = Beat(1420, 1880),
    val expandHeight: Beat = Beat(1720, 2240),
    val squareCorners: Beat = Beat(2020, 2300),
    val collapseHeight: Beat = Beat(2440, 2860),
    val roundCorners: Beat = Beat(2440, 2680),
    val returnToSlot: Beat = Beat(2640, 3260),
    /** The main timeline ends here; the finished logo holds until then. */
    val holdUntil: Int = 3620,
    /** Overlay dissolve into the app, after [holdUntil] and app readiness. */
    val exitDuration: Int = 460,
    /** Never hold the logo longer than this waiting for startup work. */
    val maxReadyWaitMillis: Long = 1500,
) {
    init {
        require(travel.start >= anticipation.end) { "travel must start after anticipation" }
        require(collapseHeight.start >= squareCorners.end) { "collapse must follow the full-screen hold" }
        require(holdUntil >= returnToSlot.end) { "hold must come after the capsule is home" }
    }

    companion object {
        val Default = MoodLaunchTimeline()
    }
}

/** Easing curves used by the choreography. No overshoot anywhere: precise, not bouncy. */
internal object MoodEasing {
    /** Material 3 emphasized-decelerate: confident arrival. */
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    /** Material 3 standard. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    /** Gentle draw-back before the travel. */
    val Anticipate: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    /** Travel: builds speed, then glides to a stop clear of the letters. */
    val Travel: Easing = CubicBezierEasing(0.55f, 0f, 0.15f, 1f)
    /** Horizontal expansion: slow start, decisive sweep. */
    val ExpandWidth: Easing = CubicBezierEasing(0.7f, 0f, 0.2f, 1f)
    val ExpandHeight: Easing = CubicBezierEasing(0.65f, 0f, 0.25f, 1f)
    val CollapseHeight: Easing = CubicBezierEasing(0.6f, 0f, 0.3f, 1f)
    /** Homing into the slot: long, soft deceleration. */
    val Return: Easing = CubicBezierEasing(0.5f, 0f, 0f, 1f)
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 0.1f, 1f)
}
