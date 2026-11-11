package com.example.wave.ui.screens.monitor

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.wave.R
import com.example.wave.data.ExitPathChecker
import com.example.wave.data.ScanRepository
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.di.AppContainer
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.NetworkUi
import com.example.wave.model.Verdict
import com.example.wave.model.WarningReason
import com.example.wave.model.WaveyMood
import com.example.wave.model.signalQuality
import com.example.wave.model.waveyMoodFor
import com.example.wave.model.withExitPath
import com.example.wave.ui.components.DetailRow
import com.example.wave.ui.components.HeroCard
import com.example.wave.ui.components.NotCheckedStyle
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.StatusCard
import com.example.wave.ui.components.StatusPill
import com.example.wave.ui.components.StatusStyle
import com.example.wave.ui.components.ToolRow
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveScreen
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.components.style
import com.example.wave.ui.navigation.Tab
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Danger
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.UnknownBg
import com.example.wave.ui.theme.UnknownFg
import com.example.wave.ui.theme.VerifiedBg
import com.example.wave.ui.theme.VerifiedFg
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WaveShapes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// ---- State ----

sealed interface MonitorUiState {
    data object Loading : MonitorUiState
    data object NotConnected : MonitorUiState

    /** [exitPath] is null when skipped because the network is already a WARNING. */
    data class Connected(val network: NetworkUi, val exitPath: ExitPathStatus?) : MonitorUiState {
        val effective: NetworkUi get() = network.withExitPath(exitPath)
        val unsafe: Boolean get() = effective.verdict == Verdict.WARNING
        val mood: WaveyMood get() = waveyMoodFor(effective.verdict, exitPath, connected = true)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorViewModel(repo: ScanRepository, exitPath: ExitPathChecker) : ViewModel() {
    val state: StateFlow<MonitorUiState> = repo.connection
        .flatMapLatest { network ->
            when {
                network == null -> flowOf(MonitorUiState.NotConnected)
                network.verdict == Verdict.WARNING -> flowOf(MonitorUiState.Connected(network, null))
                else -> exitPath.check(network).map { MonitorUiState.Connected(network, it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonitorUiState.Loading)
}

// ---- Route ----

@Composable
fun MonitorRoute(
    container: AppContainer,
    onDisconnectNow: (List<WarningReason>) -> Unit,
    onOpenTab: (Tab) -> Unit,
) {
    val vm = waveViewModel { MonitorViewModel(container.scanRepository, container.exitPathChecker) }
    val state by vm.state.collectAsStateWithLifecycle()
    MonitorScreen(state = state, onDisconnectNow = onDisconnectNow, onOpenTab = onOpenTab)
}

// ---- Screen ----

@Composable
fun MonitorScreen(
    state: MonitorUiState,
    onDisconnectNow: (List<WarningReason>) -> Unit,
    onOpenTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    WaveScreen(title = "Connection Monitor", modifier = modifier) {
        when (state) {
            MonitorUiState.Loading -> Column(
                Modifier.fillMaxWidth().padding(top = Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                Wavey(WaveyMood.CURIOUS, size = 150.dp)
                CircularProgressIndicator()
            }
            MonitorUiState.NotConnected -> {
                HeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Wavey(WaveyMood.NORMAL, size = 150.dp)
                        StatusPill(NotCheckedStyle, label = "Not connected")
                        Spacer(Modifier.height(Spacing.s))
                        Text("You're not on Wi-Fi", style = MaterialTheme.typography.headlineSmall, color = Navy900)
                        Text(
                            "Join a network marked Verified and WAVE will watch it here.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Slate600,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                ToolRow(R.drawable.ic_wifi, "Nearby Networks", { onOpenTab(Tab.NETWORKS) }, subtitle = "Find a verified network.")
            }
            is MonitorUiState.Connected -> Connected(state, onDisconnectNow)
        }
    }
}

@Composable
private fun Connected(state: MonitorUiState.Connected, onDisconnectNow: (List<WarningReason>) -> Unit) {
    val network = state.effective
    val exitStyle: StatusStyle = state.exitPath?.style() ?: NotCheckedStyle.copy(label = "Skipped")
    val subtitle = when {
        state.unsafe -> "This connection is not safe."
        network.verdict == Verdict.UNKNOWN -> "WAVE can't verify this network."
        state.exitPath == ExitPathStatus.PASSED -> "All checks passed. You're safe."
        else -> "Your connection is being analyzed."
    }

    HeroCard(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Wavey(state.mood, size = 160.dp)
            StatusPill(StatusStyle(VerifiedFg, VerifiedBg, R.drawable.ic_wifi, "Connected"))
            Spacer(Modifier.height(Spacing.s))
            Text(network.ssid, style = MaterialTheme.typography.headlineSmall, color = Navy900, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Slate600, textAlign = TextAlign.Center)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            StatusCard(
                R.drawable.ic_storage, "Registry", state.network.verdict.style().label,
                Modifier.weight(1f), style = state.network.verdict.style(),
            )
            StatusCard(R.drawable.ic_schedule, "Exit path", exitStyle.label, Modifier.weight(1f), style = exitStyle)
        }
        if (state.unsafe) {
            Spacer(Modifier.height(Spacing.l))
            PrimaryButton(
                "Disconnect now",
                onClick = { onDisconnectNow(network.reasons) },
                icon = R.drawable.ic_warning,
                containerColor = Danger,
            )
        }
    }

    WaveCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Connection details", style = MaterialTheme.typography.titleMedium, color = Navy900, modifier = Modifier.weight(1f))
            LivePill()
        }
        DetailRow("BSSID", network.bssid, monospace = true)
        DetailRow("Security type", network.security.label)
        DetailRow("Signal strength", "${network.signal} dBm · ${signalQuality(network.signal).label}")
        DetailRow("Band", network.band.label)
        DetailRow("Registry verification", state.network.verdict.style().label, valueColor = state.network.verdict.style().fg)
        DetailRow("Exit-path analysis", exitStyle.label, valueColor = exitStyle.fg, showDivider = false)
    }
}

@Composable
private fun LivePill() {
    val alpha = if (LocalInspectionMode.current) {
        1f
    } else {
        rememberInfiniteTransition(label = "live").animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "live-dot",
        ).value
    }
    Row(
        Modifier.background(UnknownBg, WaveShapes.pill).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(8.dp).alpha(alpha).background(Color(0xFF16A34A), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text("Live", style = MaterialTheme.typography.labelMedium, color = UnknownFg)
    }
}

// ---- Previews: verified, warning, unknown, loading (+ pending, not connected) ----

@Composable
private fun PreviewOf(state: MonitorUiState) = WAVETheme {
    WaveBackground { MonitorScreen(state, {}, {}) }
}

@Preview(showSystemUi = true, name = "Verified (exit passed)")
@Composable
private fun MonitorVerifiedPreview() = PreviewOf(MonitorUiState.Connected(FakeFixtures.campus, ExitPathStatus.PASSED))

@Preview(showSystemUi = true, name = "Verified (exit pending)")
@Composable
private fun MonitorPendingPreview() = PreviewOf(MonitorUiState.Connected(FakeFixtures.campus, ExitPathStatus.PENDING))

@Preview(showSystemUi = true, name = "Warning (exit failed)")
@Composable
private fun MonitorWarningPreview() = PreviewOf(MonitorUiState.Connected(FakeFixtures.campus, ExitPathStatus.FAILED))

@Preview(showSystemUi = true, name = "Unknown")
@Composable
private fun MonitorUnknownPreview() = PreviewOf(MonitorUiState.Connected(FakeFixtures.library, ExitPathStatus.PASSED))

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun MonitorLoadingPreview() = PreviewOf(MonitorUiState.Loading)

@Preview(showSystemUi = true, name = "Not connected")
@Composable
private fun MonitorNotConnectedPreview() = PreviewOf(MonitorUiState.NotConnected)
