package com.rovian.mood.brand

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** Static `mood` wordmark from the same vector geometry as the launch animation. */
@Composable
fun MoodWordmark(
    modifier: Modifier = Modifier,
    letterColor: Color = Color(0xFF121212),
) {
    val letters = remember { MoodLogoGeometry.letterPath() }
    val capsule = remember { MoodLogoGeometry.capsuleOutlinePath() }
    Canvas(
        modifier
            .aspectRatio(MoodLogoGeometry.WIDTH / MoodLogoGeometry.HEIGHT)
            .semantics { contentDescription = "Mood" },
    ) {
        val k = size.width / MoodLogoGeometry.WIDTH
        scale(k, pivot = Offset.Zero) {
            drawPath(capsule, letterColor, style = Stroke(MoodLogoGeometry.CAPSULE_STROKE))
            drawPath(letters, letterColor, style = Stroke(MoodLogoGeometry.LETTER_STROKE, cap = StrokeCap.Butt))
            val pill = MoodLogoGeometry.pillRest
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    listOf(MoodLogoGeometry.gradientStart, MoodLogoGeometry.gradientEnd),
                    startX = pill.left,
                    endX = pill.right,
                ),
                topLeft = pill.topLeft,
                size = pill.size,
                cornerRadius = CornerRadius(pill.height / 2f),
            )
        }
    }
}
