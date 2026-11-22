package com.rovian.mood.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Mood's glass material: a translucent body, a soft specular sheen across the
 * top, and a light-catching rim that is brighter where light "hits" (top-left)
 * than on the far edge. Content behind shows through, so it reads as a pane
 * floating over the artwork rather than a flat grey card.
 */
fun Modifier.liquidGlass(shape: Shape, strength: Float = 1f): Modifier = this
    .clip(shape)
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.16f * strength), Color.White.copy(alpha = 0.06f * strength)),
        ),
    )
    .drawWithContent {
        drawContent()
        // Specular sheen in the upper third.
        drawRect(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.10f * strength), Color.Transparent),
                startY = 0f,
                endY = size.height * 0.45f,
            ),
        )
    }
    .border(
        1.dp,
        Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.55f * strength),
                Color.White.copy(alpha = 0.08f * strength),
                Color.White.copy(alpha = 0.22f * strength),
            ),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
        shape,
    )
