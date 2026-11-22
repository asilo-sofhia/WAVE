package com.example.wave.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wave.data.fake.Scenario
import com.example.wave.data.fake.ScenarioStore
import com.example.wave.model.QrResult
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.theme.MinTouch
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing

/** Hidden demo menu (long-press the WAVE logo in debug builds). Switching scenario restarts at Home. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DebugMenuSheet(
    store: ScenarioStore,
    onDismiss: () -> Unit,
    onResetOnboarding: () -> Unit,
    realWifi: Boolean,
    onSetRealWifi: (Boolean) -> Unit,
) {
    val scenario by store.scenario.collectAsStateWithLifecycle()
    val qrOverride by store.qrOverride.collectAsStateWithLifecycle()
    val delayMs by store.delayMs.collectAsStateWithLifecycle()
    val locationGranted by store.locationGranted.collectAsStateWithLifecycle()
    val denyLocation by store.denyLocationRequests.collectAsStateWithLifecycle()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color.White,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.gutter)
                .navigationBarsPadding()
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            Text("Demo menu", style = MaterialTheme.typography.headlineSmall, color = Navy900)
            Text(
                if (realWifi) {
                    "Real Wi-Fi is on: Nearby, Home and Monitor show this phone's Wi-Fi. Scenarios only change QR results."
                } else {
                    "Fake data only. Changing the scenario restarts the app at Home."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600,
            )

            Label("Data source (restarts the app)")
            Column(Modifier.selectableGroup()) {
                listOf(true to "Real Wi-Fi (this phone)", false to "Demo scenarios (fake)").forEach { (real, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinTouch)
                            .selectable(
                                selected = realWifi == real,
                                role = Role.RadioButton,
                                onClick = { if (realWifi != real) onSetRealWifi(real) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = realWifi == real, onClick = null)
                        Spacer(Modifier.width(Spacing.m))
                        Text(label, style = MaterialTheme.typography.bodyLarge, color = Navy900)
                    }
                }
            }

            HorizontalDivider()
            Label("Scenario")
            Column(Modifier.selectableGroup()) {
                Scenario.entries.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = MinTouch)
                            .selectable(
                                selected = item == scenario,
                                role = Role.RadioButton,
                                onClick = {
                                    if (item != scenario) {
                                        store.select(item)
                                        onDismiss()
                                    }
                                },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = item == scenario, onClick = null)
                        Spacer(Modifier.width(Spacing.m))
                        Text(item.label, style = MaterialTheme.typography.bodyLarge, color = Navy900)
                    }
                }
            }

            HorizontalDivider()
            Label("QR result")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                FilterChip(
                    selected = qrOverride == null,
                    onClick = { store.qrOverride.value = null },
                    label = { Text("From scenario") },
                )
                QrResult.entries.forEach { result ->
                    FilterChip(
                        selected = qrOverride == result,
                        onClick = { store.qrOverride.value = result },
                        label = { Text(result.name) },
                    )
                }
            }

            HorizontalDivider()
            Label("Fake delay")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                listOf(0L to "None", 1200L to "1.2 s", 3000L to "3 s").forEach { (ms, label) ->
                    FilterChip(selected = delayMs == ms, onClick = { store.delayMs.value = ms }, label = { Text(label) })
                }
            }

            if (!realWifi) { // these only drive the fake permission and fake connection
                HorizontalDivider()
                SwitchRow("Location permission granted", locationGranted) { store.setLocationGranted(it) }
                SwitchRow("Refuse \"Allow location\" requests", denyLocation) { store.denyLocationRequests.value = it }
            }

            Spacer(Modifier.heightIn(min = Spacing.s))
            if (!realWifi) SecondaryButton("Simulate: leave Wi-Fi", onClick = { store.simulateDisconnect(); onDismiss() })
            SecondaryButton("Reset onboarding (show Splash)", onClick = { onResetOnboarding(); onDismiss() })
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Navy900, modifier = Modifier.padding(top = Spacing.s))
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .selectable(selected = checked, role = Role.Switch, onClick = { onChange(!checked) }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Navy900, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
