package com.example.wave.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.wave.R
import com.example.wave.data.admin.RegistryDraft
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.domain.RegistryClassifier
import com.example.wave.model.NetworkUi
import com.example.wave.model.SecurityType
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.StatusPill
import com.example.wave.ui.screens.nearby.NearbyRow
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WarningFg

/**
 * Admin review of one surveyed network: every radio with its signal and flags, then Approve or Block the ticked
 * ones. Only reachable inside Manage registry (after the admin gate); the public radar stays read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(
    row: NearbyRow,
    draft: RegistryDraft?,
    observedRange: (bssid: String) -> IntRange?,
    onDismiss: () -> Unit,
    onApprove: (selected: Set<String>, building: String) -> Unit,
    onBlock: (selected: Set<String>, reason: String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        ReviewContent(row, draft, observedRange, onApprove, onBlock)
    }
}

@Composable
private fun ReviewContent(
    row: NearbyRow,
    draft: RegistryDraft?,
    observedRange: (bssid: String) -> IntRange?,
    onApprove: (selected: Set<String>, building: String) -> Unit,
    onBlock: (selected: Set<String>, reason: String) -> Unit,
) {
    val network = row.network
    val aps = remember(row) { reviewAps(row.accessPoints, observedRange) }
    var selected by remember(row) { mutableStateOf(aps.filter { it.preselected }.map { it.bssid }.toSet()) }
    var building by rememberSaveable(network.bssid) { mutableStateOf(existingBuilding(draft, aps)) }
    var reason by rememberSaveable(network.bssid) { mutableStateOf("Not a campus access point") }
    val approvable = canApprove(network)

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter)
            .navigationBarsPadding()
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            network.ssid,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
            color = Navy900,
            modifier = Modifier.semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${network.security.label} · ${aps.size} access point${if (aps.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600,
                modifier = Modifier.weight(1f),
            )
            val status = draftStatus(draft, aps.map { it.bssid })
            StatusPill(status.style(), compact = true)
        }

        when {
            network.ssid == RegistryClassifier.HIDDEN_NAME ->
                Note("Hidden networks don't share a name, so they can't be registered. You can still block them.")
            !approvable -> Note("${network.security.label} can't be put in the registry. You can still block it.")
            network.security == SecurityType.OPEN ->
                Note("Open network: anyone can make a copy with the same name. Approve only if the campus really runs it.")
        }

        Text("Access points", style = MaterialTheme.typography.titleMedium, color = Navy900, modifier = Modifier.padding(top = Spacing.s))
        aps.forEach { ap ->
            val checked = ap.bssid in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .toggleable(value = checked, role = Role.Checkbox) {
                        selected = if (it) selected + ap.bssid else selected - ap.bssid
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(Spacing.s))
                Column(Modifier.weight(1f)) {
                    Text(ap.bssid, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = Navy900)
                    val seen = ap.observed?.let { " · seen ${it.first} to ${it.last} dBm" }.orEmpty()
                    Text(
                        "${ap.band.label} · ch ${ap.channel} · ${ap.signal} dBm$seen",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600,
                    )
                    if (ap.madeUp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = WarningFg, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Made-up address (common for hotspots and fake routers)", style = MaterialTheme.typography.bodySmall, color = WarningFg)
                        }
                    }
                }
            }
        }

        if (approvable) {
            OutlinedTextField(
                value = building,
                onValueChange = { building = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Building / location") },
                placeholder = { Text("e.g. CICS 2nd floor") },
            )
            PrimaryButton(
                "Approve selected",
                onClick = { onApprove(selected, building) },
                enabled = selected.isNotEmpty(),
                icon = R.drawable.ic_check,
            )
        }
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Reason for blocking") },
        )
        SecondaryButton(
            "Block selected",
            onClick = { onBlock(selected, reason.trim()) },
            enabled = selected.isNotEmpty() && reason.isNotBlank(),
            icon = R.drawable.ic_warning,
        )
        if (selected.isEmpty()) {
            Text("Tick at least one access point.", style = MaterialTheme.typography.bodySmall, color = Slate600)
        }
    }
}

@Composable
private fun Note(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = Slate600, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Slate600)
    }
}

/** Keeps the building the admin already typed for one of these radios. */
private fun existingBuilding(draft: RegistryDraft?, aps: List<ReviewAp>): String =
    draft?.approved?.firstOrNull { a -> aps.any { it.bssid == a.bssid } }?.building.orEmpty()

// ---- Previews: normal, made-up address, hidden ----

@Composable
private fun PreviewOf(vararg aps: NetworkUi) = WAVETheme {
    Column(Modifier.padding(top = Spacing.l)) {
        ReviewContent(NearbyRow(aps.first(), aps.toList()), draft = null, observedRange = { -62..-48 }, onApprove = { _, _ -> }, onBlock = { _, _ -> })
    }
}

@Preview(showBackground = true, heightDp = 900, name = "Review: campus network")
@Composable
private fun ReviewNormalPreview() = PreviewOf(FakeFixtures.campus, FakeFixtures.campus.copy(bssid = "8c:3b:ad:72:10:4e", signal = -63))

@Preview(showBackground = true, heightDp = 900, name = "Review: made-up address")
@Composable
private fun ReviewFlaggedPreview() = PreviewOf(FakeFixtures.twin)

@Preview(showBackground = true, heightDp = 700, name = "Review: hidden network")
@Composable
private fun ReviewHiddenPreview() = PreviewOf(FakeFixtures.campus.copy(ssid = RegistryClassifier.HIDDEN_NAME))
