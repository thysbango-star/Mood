package com.rovian.mood.brand.launch

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.rovian.mood.brand.MoodLogoGeometry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Immutable
data class MoodLaunchColors(
    val background: Color = Color(0xFFEDEEE9),
    val letters: Color = Color(0xFF121212),
    val gradientStart: Color = MoodLogoGeometry.gradientStart,
    val gradientEnd: Color = MoodLogoGeometry.gradientEnd,
) {
    companion object {
        val Default = MoodLaunchColors()
    }
}

/**
 * The official Mood launch animation, drawn natively from vector geometry.
 *
 * Place it *above* the real app UI (which should already be composing beneath
 * it): it covers the screen, blocks touches while visible, plays the brand
 * choreography, waits — at most [MoodLaunchTimeline.maxReadyWaitMillis] — for
 * [appReady], dissolves, then calls [onFinished]. It never delays startup work:
 * services start in parallel and the UI below is revealed the moment the
 * animation completes.
 *
 * To replay, remove it and add it again (e.g. wrap in `key(run) { … }`).
 *
 * Honors the system "Animator duration scale": with animations turned off in
 * accessibility settings it resolves immediately to the app.
 */
@Composable
fun MoodLaunchAnimation(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    appReady: Boolean = true,
    timeline: MoodLaunchTimeline = MoodLaunchTimeline.Default,
    colors: MoodLaunchColors = MoodLaunchColors.Default,
) {
    val clock = remember { Animatable(0f) }
    val exit = remember { Animatable(0f) }
    val ready by rememberUpdatedState(appReady)
    val finished by rememberUpdatedState(onFinished)

    LaunchedEffect(timeline) {
        clock.animateTo(
            targetValue = timeline.holdUntil.toFloat(),
            animationSpec = tween(durationMillis = timeline.holdUntil, easing = LinearEasing),
        )
        withTimeoutOrNull(timeline.maxReadyWaitMillis) {
            snapshotFlow { ready }.first { it }
        }
        exit.animateTo(1f, tween(durationMillis = timeline.exitDuration, easing = LinearEasing))
        finished()
    }

    val letters = remember { MoodLogoGeometry.letterPath() }
    val capsule = remember { MoodLogoGeometry.capsuleOutlinePath() }
    val letterStroke = remember {
        Stroke(width = MoodLogoGeometry.LETTER_STROKE, cap = StrokeCap.Butt, join = StrokeJoin.Round)
    }
    val capsuleStroke = remember { Stroke(width = MoodLogoGeometry.CAPSULE_STROKE) }

    Canvas(
        modifier
            .fillMaxSize()
            .semantics { contentDescription = "Mood" }
            .pointerInput(Unit) {
                // Swallow touches so nothing underneath reacts mid-animation.
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
    ) {
        // Animatable values are read here, in the draw phase only: each frame
        // is a redraw on the hardware-accelerated canvas, never a recomposition.
        val frame = MoodLaunchChoreography.frame(
            timeMs = clock.value,
            exitProgress = exit.value,
            width = size.width,
            height = size.height,
            density = density,
            timeline = timeline,
        )
        drawLaunchFrame(frame, letters, capsule, letterStroke, capsuleStroke, colors)
    }
}

internal fun DrawScope.drawLaunchFrame(
    frame: LaunchFrame,
    letters: Path,
    capsule: Path,
    letterStroke: Stroke,
    capsuleStroke: Stroke,
    colors: MoodLaunchColors,
) {
    val overlay = frame.overlayAlpha
    if (overlay <= 0f) return
    drawRect(colors.background, alpha = overlay)

    val layout = frame.layout
    scale(frame.logoScale, pivot = Offset(layout.centerX, layout.centerY)) {
        val logo = frame.logoAlpha
        if (!frame.pillAboveLetters) drawPill(frame, colors, logo)
        val ink = frame.letterAlpha * logo
        if (ink > 0f) {
            translate(layout.originX, layout.originY) {
                scale(layout.scale, pivot = Offset.Zero) {
                    drawPath(capsule, colors.letters, alpha = ink, style = capsuleStroke)
                    drawPath(letters, colors.letters, alpha = ink, style = letterStroke)
                }
            }
        }
        if (frame.pillAboveLetters) drawPill(frame, colors, logo)
    }
}

private fun DrawScope.drawPill(frame: LaunchFrame, colors: MoodLaunchColors, groupAlpha: Float) {
    val alpha = frame.pillAlpha * groupAlpha
    if (alpha <= 0f || frame.pillWidth <= 0f || frame.pillHeight <= 0f) return
    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(colors.gradientStart, colors.gradientEnd),
            startX = frame.pillLeft,
            endX = frame.pillRight,
        ),
        topLeft = Offset(frame.pillLeft, frame.pillTop),
        size = Size(frame.pillWidth, frame.pillHeight),
        cornerRadius = CornerRadius(frame.pillCornerRadius, frame.pillCornerRadius),
        alpha = alpha,
    )
}
