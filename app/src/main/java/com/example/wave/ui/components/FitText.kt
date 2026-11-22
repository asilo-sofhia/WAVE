package com.example.wave.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One line that shrinks instead of wrapping mid-word: SSIDs, BSSIDs, card labels, nav labels.
 * The floor is 11dp (not sp) so tight cells still fit at 200% font scale; everything else keeps full scaling.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
    fontFamily: FontFamily? = null,
) {
    val minSize = with(LocalDensity.current) { 11.dp.toSp() }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign ?: style.textAlign, fontFamily = fontFamily ?: style.fontFamily),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(
            minFontSize = minOf(minSize.value, style.fontSize.value).sp,
            maxFontSize = style.fontSize,
            stepSize = 0.5.sp,
        ),
    )
}
