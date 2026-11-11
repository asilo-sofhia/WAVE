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
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.Verdict
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WaveShapes

@Composable
fun StatusPill(style: StatusStyle, modifier: Modifier = Modifier, label: String = style.label) {
    Row(
        modifier = modifier
            .background(style.bg, WaveShapes.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(style.icon),
            contentDescription = null, // the label says it
            tint = style.fg,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = style.fg,
        )
    }
}

@Composable
fun VerdictChip(verdict: Verdict, modifier: Modifier = Modifier) =
    StatusPill(verdict.style(), modifier)

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
