package com.example.wave.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wave.R
import com.example.wave.data.admin.RegistryDraft
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.StatusPill
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveTextButton
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing

/** The two lists on the admin's Manage registry screen. */
enum class AdminTab { APPROVED, BLOCKED }

/** Feedback from the admin actions (provider detection, publishing, clearing). */
data class AdminActionsUi(val detecting: Boolean = false, val message: String? = null)

/** One approved or blocked access point, with a button to take it off the list. */
@Composable
fun DraftRow(title: String, bssid: String, detail: String, status: DraftStatus, onRemove: () -> Unit) {
    WaveCard(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(18.dp), padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = Spacing.l, end = Spacing.xs, top = Spacing.s, bottom = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = Navy900,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(Spacing.s))
                    StatusPill(status.style(), compact = true)
                }
                Text(bssid, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = Navy900)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Slate600)
            }
            IconButton(onClick = onRemove) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove $bssid from the list", tint = Slate600, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
fun EmptyNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Slate600, modifier = Modifier.padding(vertical = Spacing.m))
}

/**
 * Bottom of Manage registry: the campus internet provider, publishing the list, and clearing it.
 * Publishing saves registry.json; it still has to be signed on the laptop (the phone never has the campus key)
 * and uploaded to the WAVE server, which every phone downloads from.
 */
@Composable
fun AdminActionsCard(
    draft: RegistryDraft,
    ui: AdminActionsUi,
    onDetectProvider: () -> Unit,
    onPublish: () -> Unit,
    onClear: () -> Unit,
) {
    val summary = summarize(draft)
    var confirmClear by remember { mutableStateOf(false) }
    WaveCard(Modifier.fillMaxWidth().padding(top = Spacing.m), color = Color.White) {
        Text("Campus provider", style = MaterialTheme.typography.titleMedium, color = Navy900, modifier = Modifier.semantics { heading() })
        Text(
            if (summary.campusAsnMissing) "Not set. Connect to campus Wi-Fi, then tap Detect."
            else draft.campusAsns.filter { it.asn != 0 }.joinToString { "AS${it.asn} ${it.name}" },
            style = MaterialTheme.typography.bodyMedium,
            color = Slate600,
        )
        Spacer(Modifier.height(Spacing.s))
        SecondaryButton(if (ui.detecting) "Detecting…" else "Detect campus provider", onClick = onDetectProvider, enabled = !ui.detecting, icon = R.drawable.ic_wifi)

        Spacer(Modifier.height(Spacing.l))
        Text("Publish list", style = MaterialTheme.typography.titleMedium, color = Navy900, modifier = Modifier.semantics { heading() })
        Text(
            "Users' phones only trust a list stamped with the campus key, which stays on the laptop. " +
                "Save the list here, sign it on the laptop, then upload it to the WAVE server: every phone downloads it.",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate600,
        )
        Spacer(Modifier.height(Spacing.s))
        PrimaryButton("Save list for publishing", onClick = onPublish, enabled = summary.canExport, icon = R.drawable.ic_open_in_new)
        if (!summary.canExport) {
            Text("Approve at least one network first.", style = MaterialTheme.typography.bodySmall, color = Slate600)
        }
        ui.message?.let {
            Spacer(Modifier.height(Spacing.s))
            Text(it, style = MaterialTheme.typography.bodyMedium, color = Navy900)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            WaveTextButton("Clear list", onClick = { confirmClear = true })
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the list?") },
            text = { Text("Every approved and blocked network, and the campus provider, are removed from this phone's list.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; onClear() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
