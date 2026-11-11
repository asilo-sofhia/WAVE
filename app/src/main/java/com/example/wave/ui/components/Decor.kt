package com.example.wave.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

/** Four-point concave sparkle with a soft glow, as drawn around Wavey in the mockups. */
fun DrawScope.drawSparkle(center: Offset, r: Float, color: Color, glow: Color = color.copy(alpha = 0.3f)) {
    drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center, r * 1.6f), r * 1.6f, center)
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
