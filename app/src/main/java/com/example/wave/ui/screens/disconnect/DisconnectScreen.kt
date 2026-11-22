package com.example.wave.ui.screens.disconnect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.wave.R
import com.example.wave.data.ScanRepository
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.di.AppContainer
import com.example.wave.model.NetworkUi
import com.example.wave.model.WarningReason
import com.example.wave.model.WaveyMood
import com.example.wave.ui.components.IconTile
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.ReasonList
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.TechDetailsToggle
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.components.openWifiSettings
import com.example.wave.ui.screens.detail.technicalRows
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Danger
import com.example.wave.ui.theme.DangerBg
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---- State ----

data class DisconnectUiState(
    /** Null once the phone has left the network. */
    val network: NetworkUi?,
    val reasons: List<WarningReason>,
    val checking: Boolean = false,
    val stillConnected: Boolean = false,
)

class DisconnectViewModel(
    private val repo: ScanRepository,
    extraReasons: List<WarningReason>,
    /** Demo-only: the fake can't see the real Wi-Fi panel, so it is told the user left. Null for real backends. */
    private val demoDisconnect: (() -> Unit)?,
) : ViewModel() {
    private val checking = MutableStateFlow(false)
    private val stillConnected = MutableStateFlow(false)

    val state: StateFlow<DisconnectUiState> =
        combine(repo.connection, checking, stillConnected) { network, isChecking, still ->
            DisconnectUiState(
                network = network,
                reasons = ((network?.reasons ?: emptyList()) + extraReasons).distinct(),
                checking = isChecking,
                stillConnected = still,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DisconnectUiState(repo.connection.value, extraReasons),
        )

    fun onConfirmDisconnected() {
        viewModelScope.launch {
            checking.value = true
            demoDisconnect?.invoke()
            val now = repo.checkConnection()
            stillConnected.value = now != null
            checking.value = false
        }
    }
}

// ---- Route ----

@Composable
fun DisconnectRoute(container: AppContainer, extraReasons: List<WarningReason>, onDone: () -> Unit) {
    val vm = waveViewModel {
        DisconnectViewModel(container.scanRepository, extraReasons, container.demo?.let { it::simulateDisconnect })
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.network) { if (state.network == null) onDone() }
    DisconnectScreen(
        state = state,
        onOpenWifiSettings = { openWifiSettings(context) },
        onConfirmDisconnected = vm::onConfirmDisconnected,
    )
}

// ---- Screen ----

@Composable
fun DisconnectScreen(
    state: DisconnectUiState,
    onOpenWifiSettings: () -> Unit,
    onConfirmDisconnected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ssid = state.network?.ssid ?: "this network"
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Wavey(WaveyMood.BRUISED, size = 180.dp)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { heading() }) {
            Icon(painterResource(R.drawable.ic_warning), null, tint = Danger, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(Spacing.s))
            Text("Disconnect now", style = MaterialTheme.typography.displaySmall, color = Danger)
        }
        Text(
            "You're connected to \"$ssid\", and it isn't safe. Anything you send on it could be seen.",
            style = MaterialTheme.typography.bodyLarge,
            color = Navy900,
            textAlign = TextAlign.Center,
        )

        WaveCard(Modifier.fillMaxWidth(), color = DangerBg) {
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                IconTile(R.drawable.ic_lock, tint = Danger, background = DangerBg, size = 36.dp)
                Spacer(Modifier.width(Spacing.s))
                Text(
                    "Until you disconnect, don't log in, pay, or type passwords.",
                    style = MaterialTheme.typography.titleSmall,
                    color = Navy900,
                )
            }
        }

        if (state.reasons.isNotEmpty()) {
            WaveCard(Modifier.fillMaxWidth()) {
                Text("Why WAVE is worried", style = MaterialTheme.typography.titleMedium, color = Navy900)
                Spacer(Modifier.height(Spacing.m))
                ReasonList(state.reasons)
            }
        }

        if (state.stillConnected) {
            Text(
                "You're still connected to \"$ssid\". Open Wi-Fi settings and pick a network marked Verified.",
                style = MaterialTheme.typography.bodyLarge,
                color = Danger,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        PrimaryButton(
            "Open Wi-Fi settings",
            onClick = onOpenWifiSettings,
            icon = R.drawable.ic_wifi,
            containerColor = Danger,
        )
        SecondaryButton(if (state.checking) "Checking…" else "I've disconnected", onClick = onConfirmDisconnected)
        Text(
            "Android doesn't let apps disconnect Wi-Fi for you, so WAVE opens the Wi-Fi panel.",
            style = MaterialTheme.typography.bodySmall,
            color = Slate600,
            textAlign = TextAlign.Center,
        )
        state.network?.let { TechDetailsToggle(technicalRows(it)) }
    }
}

// ---- Previews: warning (twin), warning (exit failed), still connected, loading ----

@Composable
private fun PreviewOf(state: DisconnectUiState) = WAVETheme {
    WaveBackground { DisconnectScreen(state, {}, {}) }
}

@Preview(showSystemUi = true, name = "Warning (connected to twin)")
@Composable
private fun DisconnectTwinPreview() = PreviewOf(DisconnectUiState(FakeFixtures.twin, FakeFixtures.twin.reasons))

@Preview(showSystemUi = true, name = "Warning (exit path failed)")
@Composable
private fun DisconnectExitPreview() =
    PreviewOf(DisconnectUiState(FakeFixtures.campus, listOf(WarningReason.EXIT_ASN_MISMATCH)))

@Preview(showSystemUi = true, name = "Still connected")
@Composable
private fun DisconnectStillPreview() =
    PreviewOf(DisconnectUiState(FakeFixtures.twin, FakeFixtures.twin.reasons, stillConnected = true))

@Preview(showSystemUi = true, name = "Loading (checking)")
@Composable
private fun DisconnectLoadingPreview() =
    PreviewOf(DisconnectUiState(FakeFixtures.twin, FakeFixtures.twin.reasons, checking = true))
