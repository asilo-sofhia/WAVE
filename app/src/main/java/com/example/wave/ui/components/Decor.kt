package com.example.wave.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Four-point concave sparkle with a soft glow, as drawn around Wavey in the mockups. */
fun DrawScope.drawSparkle(center: Offset, r: Float, color: Color, glow: Color = color.copy(alpha = 0.3f)) {
    if (r <= 0f || size.minDimension <= 0f) return
    // Fade the glow to the same color at alpha 0 (fading to Color.Transparent, i.e. black, can darken it).
    drawCircle(Brush.radialGradient(listOf(glow, glow.copy(alpha = 0f)), center, r * 1.6f), r * 1.6f, center)
    val k = r * 0.27f
    val (cx, cy) = center
    val path = Path().apply {
        moveTo(cx, cy - r)
        quadraticTo(cx + k, cy - k, cx + r, cy)
        quadraticTo(cx + k, cy + k, cx, cy + r)
        quadraticTo(cx - k, cy + k, cx - r, cy)
        quadraticTo(cx - k, cy - k, cx, cy - r)
        close()
    }
    drawPath(path, color)
}
