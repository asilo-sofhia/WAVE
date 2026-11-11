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
import androidx.compose.ui.draw.shadow
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
                Brush.verticalGradient(listOf(Color(0xFFD7EEFF), Color(0xFFEAF6FF), Color(0xF2FFFFFF))),
                WaveShapes.hero,
            )
            .border(1.dp, CardBorder, WaveShapes.hero)
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

/** Two-up tile: icon + small label + bold value. Optional status puts a pill under the value. */
@Composable
fun StatusCard(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    style: StatusStyle? = null,
) {
    WaveCard(modifier = modifier.semantics(mergeDescendants = true) {}, padding = Spacing.m) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(
                icon = style?.icon ?: icon,
                tint = style?.fg ?: Blue400,
                background = style?.bg ?: BlueTint,
                size = 36.dp,
            )
            Spacer(Modifier.width(Spacing.s))
            FitText(label, style = MaterialTheme.typography.bodyMedium, color = Slate600, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(Spacing.s))
        FitText(value, style = MaterialTheme.typography.titleSmall, color = style?.fg ?: Navy900)
    }
}

/** Speech bubble for Wavey's line. */
@Composable
fun SpeechBubble(title: String, body: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .shadow(6.dp, RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp), spotColor = ShadowTint.copy(alpha = 0.2f))
            .background(Color.White, RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp))
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = Navy900)
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
        Text(title, style = MaterialTheme.typography.titleLarge, color = Navy900)
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.bodyMedium, color = Slate600)
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
