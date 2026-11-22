package com.rovian.mood.brand.launch

import com.rovian.mood.brand.MoodLogoGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class MoodLaunchChoreographyTest {

    private data class Screen(val w: Float, val h: Float, val density: Float)

    private val screens = listOf(
        Screen(1080f, 2400f, 2.75f), // phone portrait
        Screen(2400f, 1080f, 2.75f), // phone landscape
        Screen(720f, 1280f, 2f), // small phone
        Screen(1920f, 1080f, 2f), // TV / tablet
        Screen(1600f, 2560f, 2f), // tablet portrait
    )
    private val tl = MoodLaunchTimeline.Default

    private fun frame(t: Float, s: Screen, exit: Float = 0f) =
        MoodLaunchChoreography.frame(t, exit, s.w, s.h, s.density, tl)

    @Test
    fun capsuleSettlesExactlyInItsSlot() = screens.forEach { s ->
        val f = frame(tl.holdUntil.toFloat(), s)
        val l = f.layout
        val rest = MoodLogoGeometry.pillRest
        val eps = 0.01f
        assertEquals(l.x(rest.left), f.pillLeft, eps)
        assertEquals(l.x(rest.right), f.pillRight, eps)
        assertEquals(l.y(rest.top), f.pillTop, eps)
        assertEquals(l.y(rest.bottom), f.pillBottom, eps)
        assertEquals(f.pillHeight / 2f, f.pillCornerRadius, eps)
        assertEquals(1f, f.letterAlpha, eps)
        assertEquals(1f, f.logoScale, eps)
    }

    @Test
    fun layerSwapHappensClearOfTheLetters() = screens.forEach { s ->
        // Just before the capsule is lifted above the strokes it must already be
        // past the 'd', otherwise the z-order change would be visible.
        val f = frame(tl.expandWidth.start - 0.5f, s)
        assertFalse(f.pillAboveLetters)
        val inkRight = f.layout.x(MoodLogoGeometry.INK_RIGHT)
        assertTrue("capsule left ${f.pillLeft} overlaps ink $inkRight on $s", f.pillLeft > inkRight)
        assertTrue(frame(tl.expandWidth.start.toFloat(), s).pillAboveLetters)
    }

    @Test
    fun fullScreenRectangleCoversTheCanvas() = screens.forEach { s ->
        val t = (tl.squareCorners.end + tl.collapseHeight.start) / 2f
        val f = frame(t, s)
        assertTrue(f.pillLeft <= 0f && f.pillTop <= 0f)
        assertTrue(f.pillRight >= s.w && f.pillBottom >= s.h)
        assertEquals(0f, f.pillCornerRadius, 0.01f)
    }

    @Test
    fun motionIsContinuousAcrossEveryPhaseBoundary() = screens.forEach { s ->
        // At 1 ms steps no edge may jump more than 3% of the screen's long side;
        // a dropped keyframe or bad hand-off between phases would.
        val limit = 0.03f * max(s.w, s.h)
        var prev = frame(0f, s)
        var t = 1f
        while (t <= tl.holdUntil) {
            val f = frame(t, s)
            val jump = maxOf(
                abs(f.pillLeft - prev.pillLeft), abs(f.pillRight - prev.pillRight),
                abs(f.pillTop - prev.pillTop), abs(f.pillBottom - prev.pillBottom),
                abs(f.pillCornerRadius - prev.pillCornerRadius),
            )
            assertTrue("jump of $jump px at t=$t on $s", jump <= limit)
            prev = f
            t += 1f
        }
    }

    @Test
    fun startsInvisibleAndExitsFully() = screens.forEach { s ->
        val first = frame(0f, s)
        assertEquals(0f, first.letterAlpha, 0.001f)
        assertEquals(0f, first.pillAlpha, 0.001f)
        assertEquals(1f, first.overlayAlpha, 0.001f)
        val last = frame(tl.holdUntil.toFloat(), s, exit = 1f)
        assertEquals(0f, last.overlayAlpha, 0.001f)
        assertEquals(0f, last.logoAlpha, 0.001f)
    }
}
