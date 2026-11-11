package com.example.wave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.Verdict
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WaveShapes

@Composable
fun StatusPill(style: StatusStyle, modifier: Modifier = Modifier, label: String = style.label, compact: Boolean = false) {
    Row(
        modifier = modifier
            .background(style.bg, WaveShapes.pill)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 2.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
    ) {
        Icon(
            painter = painterResource(style.icon),
            contentDescription = null, // the label says it
            tint = style.fg,
            modifier = Modifier.size(if (compact) 14.dp else 16.dp),
        )
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp) else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = style.fg,
        )
    }
}

@Composable
fun VerdictChip(verdict: Verdict, modifier: Modifier = Modifier, compact: Boolean = false) =
    StatusPill(verdict.style(), modifier, compact = compact)

@Composable
fun ExitPathChip(status: ExitPathStatus, modifier: Modifier = Modifier) =
    StatusPill(status.style(), modifier)

@Preview(showBackground = true)
@Composable
private fun ChipsPreview() = WAVETheme {
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VerdictChip(Verdict.VERIFIED)
        VerdictChip(Verdict.WARNING)
        VerdictChip(Verdict.UNKNOWN)
        ExitPathChip(ExitPathStatus.PENDING)
    }
}
