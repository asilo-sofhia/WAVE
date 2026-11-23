package com.example.wave.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import com.example.wave.R
import com.example.wave.data.admin.DraftAp
import com.example.wave.data.admin.DraftSource
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.StatusPill
import com.example.wave.ui.components.WaveTextButton
import com.example.wave.ui.theme.Danger
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme

/** Which list entry is being edited on Manage registry. */
sealed interface EditingEntry {
    data class Approved(val group: ApprovedGroup) : EditingEntry
    data class Blocked(val group: BlockedGroup) : EditingEntry
}

/**
 * Edit one network on Manage registry (all its radios together).
 * Approved: change the building, block it instead, or remove it. Blocked: change the reason, or remove it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntrySheet(
    entry: EditingEntry,
    onDismiss: () -> Unit,
    onSaveBuilding: (bssids: List<String>, building: String) -> Unit,
    onBlock: (bssids: List<String>, ssid: String, reason: String) -> Unit,
    onRemove: (bssids: List<String>) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        EditEntryContent(entry, onSaveBuilding, onBlock, onRemove)
    }
}

@Composable
private fun EditEntryContent(
    entry: EditingEntry,
    onSaveBuilding: (bssids: List<String>, building: String) -> Unit,
    onBlock: (bssids: List<String>, ssid: String, reason: String) -> Unit,
    onRemove: (bssids: List<String>) -> Unit,
) {
    val ssid = when (entry) {
        is EditingEntry.Approved -> entry.group.ssid
        is EditingEntry.Blocked -> entry.group.ssid
    }
    val bssids = when (entry) {
        is EditingEntry.Approved -> entry.group.bssids
        is EditingEntry.Blocked -> entry.group.bssids
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter)
            .navigationBarsPadding()
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ssid.ifEmpty { "Hidden network" },
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = Navy900,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Spacer(Modifier.width(Spacing.s))
            StatusPill((if (entry is EditingEntry.Approved) DraftStatus.APPROVED else DraftStatus.BLOCKED).style())
        }

        Text("Access points", style = MaterialTheme.typography.titleMedium, color = Navy900, modifier = Modifier.padding(top = Spacing.s))
        when (entry) {
            is EditingEntry.Approved -> entry.group.aps.forEach { ap ->
                Column {
                    Text(ap.bssid, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = Navy900)
                    Text("${ap.band.label} · ch ${ap.channel} · normal signal ${ap.rssiMin} to ${ap.rssiMax} dBm", style = MaterialTheme.typography.bodySmall, color = Slate600)
                }
            }
            is EditingEntry.Blocked -> entry.group.bssids.forEach {
                Text(it, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace), color = Navy900)
            }
        }

        when (entry) {
            is EditingEntry.Approved -> {
                var building by rememberSaveable(bssids) { mutableStateOf(entry.group.aps.firstOrNull()?.building.orEmpty()) }
                OutlinedTextField(
                    value = building,
                    onValueChange = { building = it },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                    singleLine = true,
                    label = { Text("Building / location") },
                    placeholder = { Text("e.g. CICS 2nd floor") },
                )
                PrimaryButton("Save", onClick = { onSaveBuilding(bssids, building) }, icon = R.drawable.ic_check)
                SecondaryButton("Block instead", onClick = { onBlock(bssids, ssid, "Blocked from Manage registry") }, icon = R.drawable.ic_warning)
            }
            is EditingEntry.Blocked -> {
                var reason by rememberSaveable(bssids) { mutableStateOf(entry.group.reason) }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                    singleLine = true,
                    label = { Text("Reason for blocking") },
                )
                PrimaryButton("Save", onClick = { onBlock(bssids, ssid, reason.trim()) }, enabled = reason.isNotBlank(), icon = R.drawable.ic_check)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            WaveTextButton(
                if (entry is EditingEntry.Approved) "Remove from the list" else "Remove from the list (unblock)",
                onClick = { onRemove(bssids) },
                color = Danger,
            )
        }
    }
}

// ---- Previews: approved, blocked ----

@Preview(showBackground = true, heightDp = 700, name = "Edit approved network")
@Composable
private fun EditApprovedPreview() = WAVETheme {
    Column(Modifier.padding(top = Spacing.l)) {
        EditEntryContent(
            EditingEntry.Approved(
                ApprovedGroup(
                    "PLDTHOMEFIBRnzA2A", SecurityType.WPA2_PERSONAL,
                    listOf(
                        DraftAp("52:14:c1:c3:f8:c0", "PLDTHOMEFIBRnzA2A", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_2_4, 2, "Home", -85, -30, DraftSource.SURVEYED),
                        DraftAp("52:14:c1:c3:f8:c4", "PLDTHOMEFIBRnzA2A", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_5, 100, "Home", -85, -30, DraftSource.SURVEYED),
                    ),
                ),
            ),
            { _, _ -> }, { _, _, _ -> }, {},
        )
    }
}

@Preview(showBackground = true, heightDp = 600, name = "Edit blocked network")
@Composable
private fun EditBlockedPreview() = WAVETheme {
    Column(Modifier.padding(top = Spacing.l)) {
        EditEntryContent(
            EditingEntry.Blocked(BlockedGroup("BSU Free WiFi", listOf("6a:3f:00:11:22:9c"), "Rogue hotspot in the canteen")),
            { _, _ -> }, { _, _, _ -> }, {},
        )
    }
}
