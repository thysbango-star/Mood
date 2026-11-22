package com.rovian.mood.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rovian.mood.ui.theme.MoodColors

/** Mood's line-icon set, drawn as vectors on a 24-unit grid. */
enum class Glyph {
    Play, Pause, Next, Previous, Back10, Forward10, Shuffle, Repeat, RepeatOne,
    Volume, VolumeOff, Close, ChevronDown, Screen, Video, Photo, Music, Link, Mirror, Refresh, Stop,
    Search, Home, Info, Wifi,
}

@Composable
fun MoodIcon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = MoodColors.Ink) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        translate((this.size.width - 24 * s) / 2, (this.size.height - 24 * s) / 2) {
            scale(s, pivot = Offset.Zero) { drawGlyph(glyph, tint) }
        }
    }
}

private val line = Stroke(width = 1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun path(block: Path.() -> Unit) = Path().apply(block)

private fun DrawScope.arrowArc(c: Color, clockwise: Boolean) {
    val rect = Rect(Offset(12f, 13f), 7.5f)
    drawArc(c, if (clockwise) -60f else 240f, if (clockwise) 300f else -300f, false, rect.topLeft, rect.size, style = line)
    // arrow head at the arc's start (top)
    val tip = if (clockwise) Offset(15.8f, 6.5f) else Offset(8.2f, 6.5f)
    val dir = if (clockwise) -1f else 1f
    drawPath(path { moveTo(tip.x + 3.2f * dir, tip.y - 2.6f); lineTo(tip.x, tip.y); lineTo(tip.x + 3.4f * dir, tip.y + 2.4f) }, c, style = line)
}

private fun DrawScope.drawGlyph(g: Glyph, c: Color) {
    when (g) {
        Glyph.Play -> drawPath(path { moveTo(7.5f, 4.8f); lineTo(19.5f, 12f); lineTo(7.5f, 19.2f); close() }, c)
        Glyph.Pause -> {
            drawRoundRect(c, Offset(6.5f, 5f), Size(3.8f, 14f), CornerRadius(1.2f))
            drawRoundRect(c, Offset(13.7f, 5f), Size(3.8f, 14f), CornerRadius(1.2f))
        }
        Glyph.Stop -> drawRoundRect(c, Offset(6.5f, 6.5f), Size(11f, 11f), CornerRadius(2f))
        Glyph.Next -> {
            drawPath(path { moveTo(5.5f, 5.5f); lineTo(15.5f, 12f); lineTo(5.5f, 18.5f); close() }, c)
            drawRoundRect(c, Offset(16.3f, 5.5f), Size(2.4f, 13f), CornerRadius(1f))
        }
        Glyph.Previous -> {
            drawPath(path { moveTo(18.5f, 5.5f); lineTo(8.5f, 12f); lineTo(18.5f, 18.5f); close() }, c)
            drawRoundRect(c, Offset(5.3f, 5.5f), Size(2.4f, 13f), CornerRadius(1f))
        }
        Glyph.Back10 -> arrowArc(c, clockwise = false)
        Glyph.Forward10 -> arrowArc(c, clockwise = true)
        Glyph.Shuffle -> {
            drawPath(path { moveTo(3.5f, 7f); lineTo(7f, 7f); cubicTo(12f, 7f, 12f, 17f, 17f, 17f); lineTo(20f, 17f) }, c, style = line)
            drawPath(path { moveTo(3.5f, 17f); lineTo(7f, 17f); cubicTo(12f, 17f, 12f, 7f, 17f, 7f); lineTo(20f, 7f) }, c, style = line)
            drawPath(path { moveTo(17.5f, 4.5f); lineTo(20f, 7f); lineTo(17.5f, 9.5f) }, c, style = line)
            drawPath(path { moveTo(17.5f, 14.5f); lineTo(20f, 17f); lineTo(17.5f, 19.5f) }, c, style = line)
        }
        Glyph.Repeat, Glyph.RepeatOne -> {
            drawRoundRect(c, Offset(3.5f, 7f), Size(17f, 10f), CornerRadius(3.5f), style = line)
            drawPath(path { moveTo(14.5f, 4.5f); lineTo(17f, 7f); lineTo(14.5f, 9.5f) }, c, style = line)
            if (g == Glyph.RepeatOne) drawLine(c, Offset(12f, 10f), Offset(12f, 14f), 1.9f, StrokeCap.Round)
        }
        Glyph.Volume, Glyph.VolumeOff -> {
            drawPath(path { moveTo(4f, 9.5f); lineTo(7.5f, 9.5f); lineTo(12f, 5.5f); lineTo(12f, 18.5f); lineTo(7.5f, 14.5f); lineTo(4f, 14.5f); close() }, c)
            if (g == Glyph.Volume) {
                drawArc(c, -45f, 90f, false, Offset(10f, 8f), Size(8f, 8f), style = line)
                drawArc(c, -50f, 100f, false, Offset(8.5f, 4.5f), Size(13f, 15f), style = line)
            } else {
                drawLine(c, Offset(15f, 9f), Offset(20f, 15f), 1.9f, StrokeCap.Round)
                drawLine(c, Offset(20f, 9f), Offset(15f, 15f), 1.9f, StrokeCap.Round)
            }
        }
        Glyph.Close -> {
            drawLine(c, Offset(6f, 6f), Offset(18f, 18f), 1.9f, StrokeCap.Round)
            drawLine(c, Offset(18f, 6f), Offset(6f, 18f), 1.9f, StrokeCap.Round)
        }
        Glyph.ChevronDown -> drawPath(path { moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f) }, c, style = line)
        Glyph.Screen -> {
            drawRoundRect(c, Offset(3f, 5f), Size(18f, 12f), CornerRadius(2f), style = line)
            drawLine(c, Offset(9f, 20f), Offset(15f, 20f), 1.9f, StrokeCap.Round)
        }
        Glyph.Video -> {
            drawRoundRect(c, Offset(3f, 5f), Size(18f, 14f), CornerRadius(3f), style = line)
            drawPath(path { moveTo(10f, 9f); lineTo(15f, 12f); lineTo(10f, 15f); close() }, c)
        }
        Glyph.Photo -> {
            drawRoundRect(c, Offset(3f, 5f), Size(18f, 14f), CornerRadius(3f), style = line)
            drawPath(path { moveTo(5.5f, 17f); lineTo(10f, 11.5f); lineTo(13f, 15f); lineTo(15f, 13f); lineTo(18.5f, 17f) }, c, style = line)
            drawCircle(c, 1.5f, Offset(15.5f, 9f))
        }
        Glyph.Music -> {
            drawPath(path { moveTo(9f, 17f); lineTo(9f, 5.5f); lineTo(19f, 4f); lineTo(19f, 15.5f) }, c, style = line)
            drawCircle(c, 2.6f, Offset(6.6f, 17.2f))
            drawCircle(c, 2.6f, Offset(16.6f, 15.7f))
        }
        Glyph.Link -> {
            drawRoundRect(c, Offset(2.5f, 8.5f), Size(11f, 7f), CornerRadius(3.5f), style = line)
            drawRoundRect(c, Offset(10.5f, 8.5f), Size(11f, 7f), CornerRadius(3.5f), style = line)
        }
        Glyph.Mirror -> {
            drawRoundRect(c, Offset(2.5f, 6f), Size(13f, 9f), CornerRadius(1.6f), style = line)
            drawRoundRect(c, Offset(16f, 9f), Size(5.5f, 10f), CornerRadius(1.4f), style = line)
            drawLine(c, Offset(6f, 18.5f), Offset(12f, 18.5f), 1.9f, StrokeCap.Round)
        }
        Glyph.Refresh -> arrowArc(c, clockwise = true)
        Glyph.Search -> {
            drawCircle(c, 6.5f, Offset(10.5f, 10.5f), style = line)
            drawLine(c, Offset(15.3f, 15.3f), Offset(20f, 20f), 1.9f, StrokeCap.Round)
        }
        Glyph.Home -> {
            drawCircle(c, 8.5f, Offset(12f, 12f), style = line)
            drawPath(path { moveTo(10f, 8.3f); lineTo(15.5f, 12f); lineTo(10f, 15.7f); close() }, c)
        }
        Glyph.Info -> {
            drawCircle(c, 8.5f, Offset(12f, 12f), style = line)
            drawLine(c, Offset(12f, 11f), Offset(12f, 16f), 1.9f, StrokeCap.Round)
            drawCircle(c, 1.2f, Offset(12f, 8f))
        }
        Glyph.Wifi -> {
            drawArc(c, -135f, 90f, false, Offset(3f, 5f), Size(18f, 18f), style = line)
            drawArc(c, -135f, 90f, false, Offset(6.5f, 8.5f), Size(11f, 11f), style = line)
            drawCircle(c, 1.4f, Offset(12f, 17.5f))
        }
    }
}
