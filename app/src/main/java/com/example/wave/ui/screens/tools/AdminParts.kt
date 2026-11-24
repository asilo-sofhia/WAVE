package com.example.wave.ui.screens.tools

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.example.wave.ui.components.FitText
import com.example.wave.ui.theme.Blue700
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
data class AdminActionsUi(val detecting: Boolean = false, val message: String? = null, val publishing: Boolean = false)

/**
 * One approved or blocked network (its radios grouped): Wi-Fi icon, name, details and decision, with an edit button.
 * The whole row opens the edit sheet too.
 */
@Composable
fun NetworkEntryRow(title: String, detail: String, status: DraftStatus, onEdit: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    WaveCard(Modifier.fillMaxWidth().clip(shape), color = Color.White, shape = shape, padding = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Edit", role = Role.Button, onClick = onEdit)
                .heightIn(min = 62.dp)
                .padding(start = Spacing.l, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_wifi), contentDescription = null, tint = Blue700, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                FitText(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Navy900)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = Slate600, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(Spacing.s))
                    StatusPill(status.style(), compact = true)
                }
            }
            IconButton(onClick = onEdit) {
                Box(Modifier.size(34.dp).border(2.dp, Blue700, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit $title", tint = Blue700, modifier = Modifier.size(18.dp))
                }
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
    /** The place this phone publishes for (a valid admin ID card), or null: then the list is saved as a file. */
    publishTo: String? = null,
    onPublishToServer: () -> Unit = {},
) {
    val summary = summarize(draft)
    var confirmClear by remember { mutableStateOf(false) }
    var confirmPublish by remember { mutableStateOf(false) }
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
        if (publishTo != null) {
            Text(
                "Signed on this phone with your admin key and sent to the WAVE server. Every phone downloads it.",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600,
            )
            Spacer(Modifier.height(Spacing.s))
            PrimaryButton(
                "Publish to $publishTo",
                onClick = { confirmPublish = true },
                enabled = summary.canExport,
                loading = ui.publishing,
                icon = R.drawable.ic_shield_check,
            )
        } else {
            Text(
                "Users' phones only trust a list stamped with the campus key, which stays on the laptop. " +
                    "Save the list here, sign it on the laptop, then upload it to the WAVE server: every phone downloads it.",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600,
            )
            Spacer(Modifier.height(Spacing.s))
            PrimaryButton("Save list for publishing", onClick = onPublish, enabled = summary.canExport, icon = R.drawable.ic_open_in_new)
        }
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
    if (confirmPublish && publishTo != null) {
        PublishConfirmDialog(
            placeName = publishTo,
            networks = summary.networkCount,
            blocked = summary.blockedCount,
            onPublish = { confirmPublish = false; onPublishToServer() },
            onDismiss = { confirmPublish = false },
        )
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
