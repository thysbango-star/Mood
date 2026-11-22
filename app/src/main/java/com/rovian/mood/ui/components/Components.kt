package com.rovian.mood.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rovian.mood.ui.theme.MoodColors

val MoodGradient: Brush get() = Brush.horizontalGradient(listOf(MoodColors.Red, MoodColors.Orange))

/** Translucent "liquid glass" surface. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MoodColors.Glass)
            .border(1.dp, MoodColors.GlassEdge, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (title != null) {
            Text(title, color = MoodColors.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        content()
    }
}

/** Round glass button holding a glyph; disabled glyphs are never shown, so this is always actionable. */
@Composable
fun GlyphButton(
    glyph: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    filled: Boolean = false,
    tint: Color = MoodColors.Ink,
) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(50))
            .background(if (filled) Color.White else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        MoodIcon(glyph, size = iconSize, tint = if (filled) Color.Black else tint)
    }
}

/** A pill-shaped action: glyph + label. */
@Composable
fun ActionPill(glyph: Glyph, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .then(if (accent) Modifier.background(MoodGradient) else Modifier.background(Color(0x1AFFFFFF)))
            .border(1.dp, if (accent) Color.Transparent else MoodColors.GlassEdge, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MoodIcon(glyph, size = 20.dp)
        Box(Modifier.width(8.dp))
        Text(label, color = MoodColors.Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Subtle searching indicator: the Mood capsule breathing. */
@Composable
fun SearchingCapsule(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "search")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "p",
    )
    Canvas(modifier.size(width = 34.dp, height = 14.dp)) {
        val stroke = 1.6.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = 0.5f),
            cornerRadius = CornerRadius(size.height / 2),
            style = Stroke(stroke),
        )
        val inset = 3.dp.toPx()
        val w = (size.width - inset * 2) * (0.35f + 0.65f * p)
        val left = inset + (size.width - inset * 2 - w) * p
        drawRoundRect(
            MoodGradient,
            topLeft = Offset(left, inset),
            size = Size(w, size.height - inset * 2),
            cornerRadius = CornerRadius((size.height - inset * 2) / 2),
        )
    }
}

/**
 * The Mood capsule progress indicator. Position always comes from the receiver;
 * a local value is used only while the user's finger is scrubbing.
 */
@Composable
fun CapsuleProgress(
    positionMs: Long,
    durationMs: Long,
    seekable: Boolean,
    onSeek: (Long) -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var scrub by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val interact by rememberUpdatedState(onInteract)
    val fraction = scrub ?: (positionMs.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    val gestures = if (seekable) {
        Modifier
            .pointerInput(durationMs) {
                detectTapGestures { o ->
                    interact()
                    seek(((o.x / size.width).coerceIn(0f, 1f) * durationMs).toLong())
                }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f); interact() },
                    onDragEnd = { scrub?.let { seek((it * durationMs).toLong()) }; scrub = null },
                    onDragCancel = { scrub = null },
                ) { change, _ ->
                    change.consume()
                    scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                    interact()
                }
            }
    } else {
        Modifier
    }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .then(gestures)
            .semantics { contentDescription = "Playback position" },
    ) {
        val thumbW = 52.dp.toPx()
        val thumbH = 22.dp.toPx()
        val cy = size.height / 2
        val track = 2.5.dp.toPx()
        drawRoundRect(
            Color.White.copy(alpha = 0.85f),
            topLeft = Offset(0f, cy - track / 2),
            size = Size(size.width, track),
            cornerRadius = CornerRadius(track / 2),
        )
        val x = thumbW / 2 + (size.width - thumbW) * fraction
        val ring = 3.dp.toPx()
        drawRoundRect(
            Color(0xFF111111),
            topLeft = Offset(x - thumbW / 2 - ring, cy - thumbH / 2 - ring),
            size = Size(thumbW + ring * 2, thumbH + ring * 2),
            cornerRadius = CornerRadius(thumbH / 2 + ring),
        )
        drawRoundRect(
            Brush.horizontalGradient(listOf(MoodColors.Red, MoodColors.Orange), startX = x - thumbW / 2, endX = x + thumbW / 2),
            topLeft = Offset(x - thumbW / 2, cy - thumbH / 2),
            size = Size(thumbW, thumbH),
            cornerRadius = CornerRadius(thumbH / 2),
        )
    }
}

fun formatTime(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
