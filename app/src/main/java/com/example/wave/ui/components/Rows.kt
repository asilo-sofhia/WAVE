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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.wave.ui.theme.WaveShapes

/**
 * One network in a list, laid out exactly like the mockup 3 "Available networks" rows:
 * tinted Wi-Fi tile | bold name + verdict chip on ONE line | "security · signal" | optional note
 * | signal bars | chevron. The chip never wraps: on narrow phones a long name shrinks a little instead.
 * TalkBack reads the row as one item.
 */
@Composable
fun NetworkRow(
    network: NetworkUi,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    note: String? = network.reasons.firstOrNull()?.title,
    /** All access points this row stands for ([network] is the strongest). More than one adds a neutral line. */
    accessPoints: List<NetworkUi> = listOf(network),
) {
    val style = network.verdict.style()
    WaveCard(modifier = modifier.fillMaxWidth(), padding = 0.dp, color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Open details", role = Role.Button, onClick = onClick)
                .heightIn(min = 62.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(Brush.verticalGradient(listOf(style.bg.copy(alpha = 0.65f), style.bg)), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_wifi), null, tint = style.fg, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FitText(
                        network.ssid,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.ExtraBold),
                        color = Navy900,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(7.dp))
                    RowChip(style)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${network.security.label} · ${signalQuality(network.signal).label}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                    color = Slate600,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (accessPoints.size > 1) {
                    FitText(
                        accessPointsLabel(accessPoints),
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp),
                        color = Slate600,
                    )
                }
                if (note != null) {
                    FitText(
                        note,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
                        color = WarningFg,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.s))
            SignalBars(network.signal, showLabel = false)
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Slate600, modifier = Modifier.size(22.dp))
        }
    }
}

/** "2 access points · 2.4 + 5 GHz" for a network seen through several radios. */
fun accessPointsLabel(accessPoints: List<NetworkUi>): String {
    val bands = accessPoints.map { it.band }.distinct().sortedBy { it.ordinal }
        .joinToString(" + ") { it.label.removeSuffix(" GHz") } + " GHz"
    return "${accessPoints.size} access points · $bands"
}

/** The small pill beside the network name: icon + word, tinted by verdict. */
@Composable
private fun RowChip(style: StatusStyle) {
    Row(
        Modifier
            .background(style.bg, WaveShapes.pill)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Outlined glyphs like the mockup chips (check stays a check).
        val icon = when (style.icon) {
            R.drawable.ic_warning -> R.drawable.ic_warning_outline
            R.drawable.ic_help -> R.drawable.ic_help_outline
            else -> style.icon
        }
        Icon(painterResource(icon), contentDescription = null, tint = style.fg, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            style.label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
            color = style.fg,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Four bars, optionally with the word (Excellent/Strong/Good/Fair/Weak) underneath. */
@Composable
fun SignalBars(dBm: Int, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    val quality = signalQuality(dBm)
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .size(width = 20.dp, height = 18.dp)
                .clearAndSetSemantics { contentDescription = "Signal ${quality.label.lowercase()}" },
        ) {
            val barW = size.width / 7f
            val gap = (size.width - barW * 4) / 3f
            for (i in 0 until 4) {
                val h = size.height * (0.35f + 0.65f * (i + 1) / 4f)
                drawRoundRect(
                    color = if (i < quality.bars) Color(0xFF5A6A96) else Color(0xFFD3DAE8),
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2),
                )
            }
        }
        if (showLabel) {
            Text(
                quality.label,
                style = MaterialTheme.typography.labelSmall,
                color = Slate600,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
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
                .heightIn(min = 76.dp)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandIconTile(icon)
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Navy900)
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
    stacked: Boolean = false,
) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        if (stacked) {
            Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = Slate600)
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = valueColor,
                    fontFamily = if (monospace) FontFamily.Monospace else null,
                )
            }
            if (showDivider) HorizontalDivider(color = Divider)
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = MinTouch).padding(vertical = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Slate600, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Spacing.m))
            FitText(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = valueColor,
                textAlign = TextAlign.End,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                modifier = Modifier.weight(1.4f),
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
                    DetailRow(label, value, monospace = true, stacked = true, showDivider = i != rows.lastIndex)
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
