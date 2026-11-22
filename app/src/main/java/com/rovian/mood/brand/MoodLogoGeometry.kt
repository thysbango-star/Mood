package com.rovian.mood.brand

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

/**
 * Vector geometry of the official `mood` wordmark.
 *
 * Every value was measured from the supplied master artwork and is expressed in
 * "logo units": the wordmark's bounding box is [WIDTH] x [HEIGHT], origin at
 * the top-left. Strokes are centre-lines; letters are monoline with flat
 * (butt) terminals, as in the artwork.
 *
 *   m  — three stems joined by two semicircular arches
 *   o  — the signature outlined capsule (thinner stroke) that holds the
 *        red→orange gradient capsule
 *   o  — circle
 *   d  — circular bowl sharing its right edge with the ascender stem
 *
 * Renderers scale these paths; nothing is rasterised, so the mark stays sharp
 * at any size or density.
 */
object MoodLogoGeometry {
    const val WIDTH = 453f
    const val HEIGHT = 101f

    const val LETTER_STROKE = 11f
    const val CAPSULE_STROKE = 8.5f

    private const val BASELINE = 101f

    // m
    private const val M_STEM_1 = 5.5f
    private const val M_STEM_2 = 55f
    private const val M_STEM_3 = 104.5f
    private const val M_ARCH_TOP = 33.5f // centre-line top of the arches
    private const val M_ARCH_RADIUS = 24.75f

    // Signature capsule 'o' (centre-line of its outline)
    val capsuleOutline = Rect(left = 125f, top = 34f, right = 299.5f, bottom = 97.5f)

    /** Resting position of the gradient capsule inside the outlined 'o'. */
    val pillRest = Rect(left = 139f, top = 45f, right = 285f, bottom = 86f)

    // Second o
    private val O_CENTER = Offset(345f, 63f)
    private const val O_RADIUS = 29.5f

    // d
    private val D_BOWL_CENTER = Offset(418f, 63f)
    private const val D_BOWL_RADIUS = 28.5f
    private const val D_STEM_X = 446.5f

    /** Right-most inked x of the wordmark, in logo units. */
    const val INK_RIGHT = D_STEM_X + LETTER_STROKE / 2f

    val gradientStart = Color(0xFFFF3038)
    val gradientEnd = Color(0xFFFF7738)

    /** m, the second o and d — stroked at [LETTER_STROKE]. */
    fun letterPath(): Path = Path().apply {
        val archY = M_ARCH_TOP + M_ARCH_RADIUS
        // m
        moveTo(M_STEM_1, BASELINE)
        lineTo(M_STEM_1, archY)
        arcTo(Rect(M_STEM_1, M_ARCH_TOP, M_STEM_2, M_ARCH_TOP + 2 * M_ARCH_RADIUS), 180f, 180f, false)
        lineTo(M_STEM_2, BASELINE)
        moveTo(M_STEM_2, archY)
        arcTo(Rect(M_STEM_2, M_ARCH_TOP, M_STEM_3, M_ARCH_TOP + 2 * M_ARCH_RADIUS), 180f, 180f, false)
        lineTo(M_STEM_3, BASELINE)
        // o
        addOval(Rect(center = O_CENTER, radius = O_RADIUS))
        // d
        addOval(Rect(center = D_BOWL_CENTER, radius = D_BOWL_RADIUS))
        moveTo(D_STEM_X, 0f)
        lineTo(D_STEM_X, BASELINE)
    }

    /** The outlined capsule 'o' — stroked at [CAPSULE_STROKE]. */
    fun capsuleOutlinePath(): Path = Path().apply {
        val r = capsuleOutline.height / 2f
        addRoundRect(RoundRect(capsuleOutline, CornerRadius(r, r)))
    }
}
