package com.example.wave.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.wave.R
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.model.NetworkUi
import com.example.wave.model.WarningReason
import com.example.wave.model.signalQuality
import com.example.wave.ui.theme.Blue400
import com.example.wave.ui.theme.Blue700
import com.example.wave.ui.theme.BlueTint
import com.example.wave.ui.theme.Divider
import com.example.wave.ui.theme.MinTouch
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WarningBg
import com.example.wave.ui.theme.WarningFg

/** One network in a list. TalkBack reads it as one item: name, verdict, security, first reason, signal. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkRow(network: NetworkUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val style = network.verdict.style()
    WaveCard(modifier = modifier.fillMaxWidth(), padding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Open details", role = Role.Button, onClick = onClick)
                .heightIn(min = 72.dp)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconTile(R.drawable.ic_wifi, tint = style.fg, background = style.bg)
                // Small verdict glyph on the tile, so the tile isn't color-only either.
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 4.dp, y = 4.dp)
                        .size(20.dp)
                        .background(Color.White, CircleShape)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(style.icon), null, tint = style.fg, modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        network.ssid,
                        style = MaterialTheme.typography.titleSmall,
                        color = Navy900,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    VerdictChip(network.verdict)
                }
                Text(
                    "${network.security.label} · ${network.band.label}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate600,
                )
                network.reasons.firstOrNull()?.let {
                    Text(it.title, style = MaterialTheme.typography.bodyMedium, color = WarningFg)
                }
            }
            Spacer(Modifier.width(Spacing.s))
            SignalBars(network.signal)
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Slate600)
        }
    }
}

/** Four bars + a word (Excellent/Good/Fair/Weak). */
@Composable
fun SignalBars(dBm: Int, modifier: Modifier = Modifier) {
    val quality = signalQuality(dBm)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .size(width = 22.dp, height = 18.dp)
                .clearAndSetSemantics { contentDescription = "Signal ${quality.label.lowercase()}" },
        ) {
            val barW = size.width / 6f
            val gap = (size.width - barW * 4) / 3f
            for (i in 0 until 4) {
                val h = size.height * (0.35f + 0.65f * (i + 1) / 4f)
                drawRoundRect(
                    color = if (i < quality.bars) Navy900.copy(alpha = 0.75f) else Divider,
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2),
                )
            }
        }
        Text(
            quality.label,
            style = MaterialTheme.typography.labelSmall,
            color = Slate600,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

/** Navigation row: icon tile, title, optional subtitle, chevron. */
@Composable
fun ToolRow(
    @DrawableRes icon: Int,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    WaveCard(modifier = modifier.fillMaxWidth(), padding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 64.dp)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(icon, tint = Blue400, background = BlueTint)
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Navy900)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Slate600)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Slate600)
        }
    }
}

/** Plain-language reasons, each with the warning triangle. */
@Composable
fun ReasonList(reasons: List<WarningReason>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        reasons.forEach { reason ->
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                IconTile(R.drawable.ic_warning, tint = WarningFg, background = WarningBg, size = 36.dp)
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(reason.title, style = MaterialTheme.typography.titleSmall, color = Navy900)
                    Text(reason.explanation, style = MaterialTheme.typography.bodyMedium, color = Slate600)
                }
            }
        }
    }
}

/** Label/value line used in details lists. */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Navy900,
    monospace: Boolean = false,
    showDivider: Boolean = true,
) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Slate600, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Spacing.m))
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = valueColor,
                textAlign = TextAlign.End,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                modifier = Modifier.weight(1.3f),
            )
        }
        if (showDivider) HorizontalDivider(color = Divider)
    }
}

/** "Technical details" disclosure. Collapsed by default so the main message stays simple. */
@Composable
fun TechDetailsToggle(rows: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "Hide" else "Show", role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                .heightIn(min = MinTouch)
                .padding(horizontal = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_info), null, tint = Blue700, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.s))
            Text("Technical details", style = MaterialTheme.typography.labelLarge, color = Blue700, modifier = Modifier.weight(1f))
            Icon(
                painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                null,
                tint = Blue700,
            )
        }
        AnimatedVisibility(expanded) {
            WaveCard(Modifier.fillMaxWidth().padding(top = Spacing.s)) {
                rows.forEachIndexed { i, (label, value) ->
                    DetailRow(label, value, monospace = true, showDivider = i != rows.lastIndex)
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFE8F5FF)
@Composable
private fun RowsPreview() = WAVETheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NetworkRow(FakeFixtures.campus, {})
        NetworkRow(FakeFixtures.twin, {})
        NetworkRow(FakeFixtures.library, {})
        ToolRow(R.drawable.ic_qr, "Scan Wi-Fi QR", {}, subtitle = "Check a QR code before joining.")
    }
}
