package com.example.wave.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wave.R
import com.example.wave.model.Verdict
import com.example.wave.ui.theme.Blue400
import com.example.wave.ui.theme.BrandIcon
import com.example.wave.ui.theme.BlueTint
import com.example.wave.ui.theme.CardBorder
import com.example.wave.ui.theme.CardSurface
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WaveShapes

private val ShadowTint = Color(0xFF3B82C4)

/** Translucent white "glass" card. No real blur: Modifier.blur needs API 31, minSdk is 29. */
@Composable
fun WaveCard(
    modifier: Modifier = Modifier,
    shape: Shape = WaveShapes.card,
    padding: Dp = Spacing.l,
    color: Color = CardSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .shadow(10.dp, shape, ambientColor = ShadowTint.copy(alpha = 0.18f), spotColor = ShadowTint.copy(alpha = 0.22f))
            .background(color, shape)
            .border(1.dp, CardBorder, shape)
            .padding(padding),
        content = content,
    )
}

/** Big rounded hero panel with a soft sky gradient (Home / Monitor tops). */
@Composable
fun HeroCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .shadow(14.dp, WaveShapes.hero, ambientColor = ShadowTint.copy(alpha = 0.2f), spotColor = ShadowTint.copy(alpha = 0.25f))
            .background(
                Brush.verticalGradient(0f to Color(0xFFCDEEFE), 0.45f to Color(0xFFADE2FD), 1f to Color(0xFFC3E9FD)),
                WaveShapes.hero,
            )
            .border(1.5.dp, Color.White.copy(alpha = 0.75f), WaveShapes.hero)
            .padding(Spacing.l),
        content = content,
    )
}

/** Rounded icon tile used in rows and status cards. */
@Composable
fun IconTile(
    @DrawableRes icon: Int,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(background, WaveShapes.tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Glossy light-blue tile with a brand-blue icon (mockup tool/stat tiles). */
@Composable
fun BrandIconTile(@DrawableRes icon: Int, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(6.dp, WaveShapes.tile, ambientColor = ShadowTint.copy(alpha = 0.25f), spotColor = ShadowTint.copy(alpha = 0.3f))
            .background(Brush.verticalGradient(listOf(Color(0xFFF2F9FF), Color(0xFFDDEFFE))), WaveShapes.tile)
            .border(1.dp, Color.White, WaveShapes.tile),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = BrandIcon, modifier = Modifier.size(size * 0.54f))
    }
}

/**
 * Two-up tile: icon tile + small label + bold value (mockups 2 and 5).
 * Wide enough: icon left, label over value. Narrow (small phones): label beside the icon, value below.
 */
@Composable
fun StatusCard(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    style: StatusStyle? = null,
) {
    WaveCard(modifier = modifier.semantics(mergeDescendants = true) {}, padding = Spacing.m, color = Color.White) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 135.dp
            val valueStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusTile(icon, style, 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        FitText(label, style = MaterialTheme.typography.bodyMedium, color = Slate600)
                        FitText(value, style = valueStyle, color = style?.fg ?: Navy900)
                    }
                }
            } else {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusTile(icon, style, 40.dp)
                        Spacer(Modifier.width(Spacing.s))
                        FitText(label, style = MaterialTheme.typography.bodyMedium, color = Slate600, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(Spacing.s))
                    FitText(value, style = valueStyle, color = style?.fg ?: Navy900)
                }
            }
        }
    }
}

@Composable
private fun StatusTile(@DrawableRes icon: Int, style: StatusStyle?, size: Dp) {
    if (style == null) {
        BrandIconTile(icon, size = size)
    } else {
        IconTile(style.icon, tint = style.fg, background = style.bg, size = size)
    }
}

/** Speech bubble for Wavey's line, with a tail at the bottom-right pointing at Wavey (mockup 2). */
@Composable
fun SpeechBubble(title: String, body: String?, modifier: Modifier = Modifier) {
    val tail = 12.dp
    Column(
        modifier = modifier
            .drawBehind {
                val r = 22.dp.toPx()
                val t = tail.toPx()
                val h = size.height - t
                val path = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, size.width, h, CornerRadius(r)))
                    // Tail: a small wedge under the right side, pointing down at Wavey.
                    moveTo(size.width * 0.70f, h - 1f)
                    lineTo(size.width * 0.86f, h - 1f)
                    lineTo(size.width * 0.84f, size.height)
                    close()
                }
                drawPath(path, Color(0x223B82C4), style = Stroke(width = 6.dp.toPx()))
                drawPath(path, Color.White)
            }
            .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp + tail),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = Navy900)
        if (body != null) {
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Slate600)
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = Spacing.s, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp), color = Navy900)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyLarge, color = Slate600)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFE8F5FF)
@Composable
private fun SurfacesPreview() = WAVETheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SpeechBubble("I'm ready to scan!", "Let's check your Wi-Fi.")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusCard(R.drawable.ic_wifi, "Wi-Fi", "BATSTATEU-ICT", Modifier.weight(1f))
            StatusCard(R.drawable.ic_shield, "Health", "Warning", Modifier.weight(1f), Verdict.WARNING.style())
        }
        SectionHeader("Security tools", trailing = "2 tools")
    }
}
