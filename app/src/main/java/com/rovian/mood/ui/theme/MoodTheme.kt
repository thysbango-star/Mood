package com.rovian.mood.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object MoodColors {
    val Night = Color(0xFF0B0B0C)
    val Charcoal = Color(0xFF151517)
    val Ink = Color(0xFFF3F2EE)
    val InkSecondary = Color(0xFFA7A6A2)
    val Glass = Color(0x14FFFFFF)
    val GlassEdge = Color(0x1FFFFFFF)
    val Red = Color(0xFFFF3038)
    val Orange = Color(0xFFFF7738)
}

private val scheme = darkColorScheme(
    primary = MoodColors.Orange,
    onPrimary = MoodColors.Night,
    background = MoodColors.Night,
    onBackground = MoodColors.Ink,
    surface = MoodColors.Charcoal,
    onSurface = MoodColors.Ink,
    onSurfaceVariant = MoodColors.InkSecondary,
)

@Composable
fun MoodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
