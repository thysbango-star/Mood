package com.rovian.mood.brand.launch

import com.rovian.mood.brand.MoodLogoGeometry
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Where the wordmark sits on a canvas: screen = origin + logoUnits * scale. */
internal data class LogoLayout(val originX: Float, val originY: Float, val scale: Float) {
    val centerX: Float get() = originX + MoodLogoGeometry.WIDTH * scale / 2f
    val centerY: Float get() = originY + MoodLogoGeometry.HEIGHT * scale / 2f
    fun x(logoX: Float) = originX + logoX * scale
    fun y(logoY: Float) = originY + logoY * scale
}

/** One rendered moment of the animation, in canvas pixels. */
internal data class LaunchFrame(
    val layout: LogoLayout,
    /** Alpha of the black lettering and capsule outline. */
    val letterAlpha: Float,
    /** Uniform scale of the whole logo group about the logo centre. */
    val logoScale: Float,
    val pillLeft: Float,
    val pillTop: Float,
    val pillRight: Float,
    val pillBottom: Float,
    val pillCornerRadius: Float,
    val pillAlpha: Float,
    /** false while the capsule travels *behind* the strokes; true once it leaves the logo. */
    val pillAboveLetters: Boolean,
    /** Exit fade of the logo group (lettering + capsule). */
    val logoAlpha: Float,
    /** Alpha of the paper background; the app shows through as it falls to 0. */
    val overlayAlpha: Float,
) {
    val pillWidth: Float get() = pillRight - pillLeft
    val pillHeight: Float get() = pillBottom - pillTop
}

/**
 * Pure, deterministic description of the launch animation: (time, canvas size) → frame.
 * Rendering lives in [MoodLaunchAnimation]; keeping the maths here makes it unit-testable.
 */
internal object MoodLaunchChoreography {
    /** Wordmark width as a fraction of canvas width (before the dp cap). */
    const val LOGO_WIDTH_FRACTION = 0.56f
    const val LOGO_MAX_WIDTH_DP = 360f

    /** How far the capsule draws back before it travels (logo units). */
    const val ANTICIPATION_PULL = 10f
    /** Gap between the 'd' and the capsule's left edge where travel ends (logo units). */
    const val TRAVEL_CLEARANCE = 20f
    /** Peak horizontal stretch while travelling — a sense of speed, not a cartoon smear. */
    const val TRAVEL_STRETCH = 0.26f
    /** Grow past the canvas edges so anti-aliasing never shows a seam. */
    const val BLEED_PX = 2f

    fun layout(width: Float, height: Float, density: Float): LogoLayout {
        val logoWidth = min(width * LOGO_WIDTH_FRACTION, LOGO_MAX_WIDTH_DP * density)
        val scale = logoWidth / MoodLogoGeometry.WIDTH
        return LogoLayout(
            originX = (width - logoWidth) / 2f,
            originY = (height - MoodLogoGeometry.HEIGHT * scale) / 2f,
            scale = scale,
        )
    }

    /** Centre x of the capsule when its travel ends, in logo units. */
    val travelEndCenterX: Float
        get() = MoodLogoGeometry.INK_RIGHT + TRAVEL_CLEARANCE + MoodLogoGeometry.pillRest.width / 2f

    fun frame(
        timeMs: Float,
        exitProgress: Float,
        width: Float,
        height: Float,
        density: Float,
        timeline: MoodLaunchTimeline = MoodLaunchTimeline.Default,
    ): LaunchFrame {
        val layout = layout(width, height, density)
        val rest = MoodLogoGeometry.pillRest
        val restCx = layout.x(rest.center.x)
        val restCy = layout.y(rest.center.y)
        val restW = rest.width * layout.scale
        val restH = rest.height * layout.scale
        val fullW = width + 2 * BLEED_PX
        val fullH = height + 2 * BLEED_PX

        // Phase 1 — appearance
        val wordIn = timeline.wordmarkIn.eased(timeMs, MoodEasing.EmphasizedDecelerate)
        val letterAlpha = wordIn
        val entryScale = lerp(0.965f, 1f, wordIn)
        val pillGrow = timeline.pillIn.eased(timeMs, MoodEasing.EmphasizedDecelerate)
        val pillAlpha = (timeline.pillIn.raw(timeMs) / 0.35f).coerceIn(0f, 1f)

        // Phase 2 — draw back, then travel through the letters
        val pull = timeline.anticipation.eased(timeMs, MoodEasing.Anticipate)
        val travel = timeline.travel.eased(timeMs, MoodEasing.Travel)
        val travelRaw = timeline.travel.raw(timeMs)
        val pullPx = ANTICIPATION_PULL * layout.scale
        val travelPx = (travelEndCenterX - rest.center.x) * layout.scale
        val travelCx = restCx - pullPx * pull + (travelPx + pullPx) * travel
        val stretch = 1f + TRAVEL_STRETCH * sin(PI.toFloat() * travelRaw)
        val inlineW = lerp(restH, restW, pillGrow) * stretch
        val inlineH = restH

        // Phase 5 — exit: the logo lifts away first, then the paper dissolves,
        // so the hand-off never passes through a muddy grey with the mark in it.
        val e = exitProgress.coerceIn(0f, 1f)
        val logoExit = MoodEasing.Exit.transform((e / 0.55f).coerceIn(0f, 1f))
        val paperExit = MoodEasing.Exit.transform(((e - 0.25f) / 0.75f).coerceIn(0f, 1f))
        val logoScale = entryScale * lerp(1f, 1.045f, logoExit)
        val logoAlpha = 1f - logoExit
        val overlayAlpha = 1f - paperExit

        val cx: Float
        val cy: Float
        val w: Float
        val h: Float
        val radius: Float
        val above: Boolean
        when {
            timeMs < timeline.expandWidth.start -> {
                cx = travelCx; cy = restCy; w = inlineW; h = inlineH
                radius = h / 2f
                above = false
            }
            timeMs < timeline.collapseHeight.start -> {
                // Phase 3 — expansion: width first, then height, then square ends
                val ew = timeline.expandWidth.eased(timeMs, MoodEasing.ExpandWidth)
                val eh = timeline.expandHeight.eased(timeMs, MoodEasing.ExpandHeight)
                val sq = timeline.squareCorners.eased(timeMs, MoodEasing.Standard)
                cx = lerp(travelCx, width / 2f, ew)
                cy = lerp(restCy, height / 2f, eh)
                w = lerp(inlineW, fullW, ew)
                h = lerp(inlineH, fullH, eh)
                radius = h / 2f * (1f - sq)
                above = true
            }
            else -> {
                // Phase 4 — contraction and return to the slot
                val ch = timeline.collapseHeight.eased(timeMs, MoodEasing.CollapseHeight)
                val rc = timeline.roundCorners.eased(timeMs, MoodEasing.Standard)
                val rt = timeline.returnToSlot.eased(timeMs, MoodEasing.Return)
                cx = lerp(width / 2f, restCx, rt)
                cy = lerp(height / 2f, restCy, ch)
                w = lerp(fullW, restW, rt)
                h = lerp(fullH, restH, ch)
                radius = h / 2f * rc
                above = true
            }
        }

        return LaunchFrame(
            layout = layout,
            letterAlpha = letterAlpha,
            logoScale = logoScale,
            pillLeft = cx - w / 2f,
            pillTop = cy - h / 2f,
            pillRight = cx + w / 2f,
            pillBottom = cy + h / 2f,
            pillCornerRadius = min(radius, min(w, h) / 2f),
            pillAlpha = pillAlpha,
            pillAboveLetters = above,
            logoAlpha = logoAlpha,
            overlayAlpha = overlayAlpha,
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
}
