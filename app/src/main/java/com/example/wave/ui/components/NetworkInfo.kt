package com.example.wave.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.wave.R
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.model.CheckOutcome
import com.example.wave.model.NetworkUi
import com.example.wave.model.Verdict
import com.example.wave.model.VerdictCheck
import com.example.wave.model.signalQuality
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WaveShapes
import kotlinx.coroutines.delay

/**
 * "What WAVE checked": every check behind the verdict, passed or not, so the user can see WHY a
 * network is Verified, Unknown or Warning. Falls back to the plain reason list when no checks ran.
 */
@Composable
fun VerdictChecksCard(network: NetworkUi, modifier: Modifier = Modifier) {
    if (network.checks.isEmpty()) {
        if (network.reasons.isNotEmpty()) {
            WaveCard(modifier.fillMaxWidth()) {
                CardTitle("Why WAVE is worried")
                Spacer(Modifier.height(Spacing.m))
                ReasonList(network.reasons)
            }
        }
        return
    }
    WaveCard(modifier.fillMaxWidth()) {
        CardTitle("What WAVE checked")
        Text(checksSummary(network), style = MaterialTheme.typography.bodyMedium, color = Slate600)
        Spacer(Modifier.height(Spacing.m))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            network.checks.forEach { CheckRow(it) }
        }
    }
}

/** One line under the "What WAVE checked" title, e.g. "3 of 5 checks failed." */
fun checksSummary(network: NetworkUi): String {
    val total = network.checks.size
    val failed = network.checks.count { it.outcome == CheckOutcome.FAILED }
    val passed = network.checks.count { it.outcome == CheckOutcome.PASSED }
    return when {
        failed > 0 -> "$failed of $total checks failed."
        network.verdict == Verdict.UNKNOWN -> "Not on the registry, so only $passed of $total checks could run."
        passed == total -> "All $total checks passed."
        else -> "$passed of $total checks passed."
    }
}

@Composable
private fun CheckRow(check: VerdictCheck) {
    val style = check.outcome.style()
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "${check.kind.title}: ${style.label}. ${check.detail}"
        },
        verticalAlignment = Alignment.Top,
    ) {
        IconTile(style.icon, tint = style.fg, background = style.bg, size = 36.dp)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    check.kind.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Navy900,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.s))
                Text(
                    style.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = style.fg,
                    modifier = Modifier
                        .background(style.bg, WaveShapes.pill)
                        .padding(horizontal = Spacing.s, vertical = 2.dp),
                )
            }
            Text(check.detail, style = MaterialTheme.typography.bodyMedium, color = Slate600)
        }
    }
}

/**
 * "Wi-Fi details": every radio reading Android gives from a scan, which is all available
 * BEFORE connecting. Always visible, so the verdict can be confirmed against the raw numbers.
 */
@Composable
fun NetworkMetricsCard(network: NetworkUi, modifier: Modifier = Modifier, nowMillis: Long = rememberNowMillis()) {
    val rows = metricRows(network, nowMillis)
    val m = network.metrics
    WaveCard(modifier.fillMaxWidth()) {
        CardTitle("Wi-Fi details")
        Text("Read from the scan, before connecting.", style = MaterialTheme.typography.bodyMedium, color = Slate600)
        Spacer(Modifier.height(Spacing.s))
        rows.forEach { (label, value) ->
            DetailRow(label, value, monospace = label == "BSSID")
        }
        DetailRow(
            "Security flags",
            m?.capabilities?.ifBlank { "(none)" } ?: NOT_REPORTED,
            monospace = m != null,
            stacked = true,
            showDivider = false,
        )
        Spacer(Modifier.height(Spacing.s))
        Row(verticalAlignment = Alignment.Top) {
            Icon(painterResource(R.drawable.ic_info), null, tint = Slate600, modifier = Modifier.size(18.dp).padding(top = 2.dp))
            Spacer(Modifier.width(Spacing.s))
            Text(
                "IP address, link speed and the internet-provider check are only available after you connect.",
                style = MaterialTheme.typography.bodySmall,
                color = Slate600,
            )
        }
    }
}

/** Label/value pairs for the metrics card (also used by the technical-details toggles). */
fun metricRows(network: NetworkUi, nowMillis: Long = System.currentTimeMillis()): List<Pair<String, String>> {
    val m = network.metrics
    return listOf(
        "BSSID" to network.bssid,
        "Signal" to "${network.signal} dBm · ${signalQuality(network.signal).label}",
        "Security" to network.security.label,
        "Band" to network.band.label,
        "Channel" to (m?.channel?.toString() ?: NOT_REPORTED),
        "Frequency" to (m?.let { "${it.frequencyMhz} MHz" } ?: NOT_REPORTED),
        "Channel width" to (m?.channelWidthMhz?.let { "$it MHz" } ?: NOT_REPORTED),
        "Wi-Fi standard" to (m?.wifiStandard?.label ?: NOT_REPORTED),
        "Last seen" to lastSeenLabel(m?.seenAtMillis, nowMillis),
    )
}

fun lastSeenLabel(seenAtMillis: Long?, nowMillis: Long): String =
    seenAtMillis?.let { timeAgo(it, nowMillis).replaceFirstChar(Char::uppercase) } ?: NOT_REPORTED

/** "just now", "30 s ago", "2 min ago", "over an hour ago". */
fun timeAgo(thenMillis: Long, nowMillis: Long): String {
    val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 5 -> "just now"
        seconds < 60 -> "$seconds s ago"
        seconds < 3600 -> "${seconds / 60} min ago"
        else -> "over an hour ago"
    }
}

/** The current time, refreshed every [periodMillis], so "x s ago" labels keep counting while on screen. */
@Composable
fun rememberNowMillis(periodMillis: Long = 5_000): Long {
    val now by produceState(System.currentTimeMillis(), periodMillis) {
        while (true) {
            delay(periodMillis)
            value = System.currentTimeMillis()
        }
    }
    return now
}

private const val NOT_REPORTED = "Not reported"

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Navy900, modifier = Modifier.semantics { heading() })
}

@Preview(showBackground = true, backgroundColor = 0xFFE8F5FF, heightDp = 1300)
@Composable
private fun NetworkInfoPreview() = WAVETheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VerdictChecksCard(FakeFixtures.twin)
        VerdictChecksCard(FakeFixtures.library)
        NetworkMetricsCard(FakeFixtures.twin, nowMillis = 0L)
    }
}
