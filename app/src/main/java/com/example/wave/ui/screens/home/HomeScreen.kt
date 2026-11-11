package com.example.wave.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.wave.model.waveyMoodFor
import com.example.wave.model.withExitPath
import com.example.wave.ui.components.HeroCard
import com.example.wave.ui.components.NotCheckedStyle
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SectionHeader
import com.example.wave.ui.components.SpeechBubble
import com.example.wave.ui.components.StatusCard
import com.example.wave.ui.components.ToolRow
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveScreen
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.components.style
import com.example.wave.ui.navigation.Tab
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Danger
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---- State ----

sealed interface HomeCheck {
    data object NotChecked : HomeCheck
    data object Checking : HomeCheck
    data object NotOnWifi : HomeCheck

    /** [exitPath] is null when the exit-path check was skipped (network already a WARNING). */
    data class Checked(val network: NetworkUi, val exitPath: ExitPathStatus?) : HomeCheck {
        val effective: NetworkUi get() = network.withExitPath(exitPath)
    }
}

data class HomeUiState(val ssid: String?, val check: HomeCheck) {
    val mood: WaveyMood
        get() = when (check) {
            HomeCheck.NotChecked, HomeCheck.NotOnWifi -> WaveyMood.NORMAL
            HomeCheck.Checking -> WaveyMood.CURIOUS
            is HomeCheck.Checked -> waveyMoodFor(check.effective.verdict, check.exitPath, connected = true)
        }

    /** Connected to a WARNING network (or exit path failed): the only action is to disconnect. */
    val unsafe: Boolean
        get() = check is HomeCheck.Checked && check.effective.verdict == Verdict.WARNING
}

class HomeViewModel(
    private val repo: ScanRepository,
    private val exitPath: ExitPathChecker,
) : ViewModel() {
    private val check = MutableStateFlow<HomeCheck>(HomeCheck.NotChecked)
    private var job: Job? = null

    val state: StateFlow<HomeUiState> = combine(repo.connection, check) { connection, check ->
        val shown = when {
            check == HomeCheck.Checking -> check
            connection == null -> HomeCheck.NotOnWifi
            check is HomeCheck.Checked && check.network.bssid != connection.bssid -> HomeCheck.NotChecked
            else -> check
        }
        HomeUiState(ssid = connection?.ssid, check = shown)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(repo.connection.value?.ssid, HomeCheck.NotChecked))

    fun onCheckWifi() {
        job?.cancel()
        job = viewModelScope.launch {
            check.value = HomeCheck.Checking
            val network = repo.checkConnection()
            if (network == null) {
                check.value = HomeCheck.NotChecked
                return@launch
            }
            if (network.verdict == Verdict.WARNING) {
                check.value = HomeCheck.Checked(network, exitPath = null)
                return@launch
            }
            exitPath.check(network).collect { check.value = HomeCheck.Checked(network, it) }
        }
    }
}

// ---- Route ----

@Composable
fun HomeRoute(
    container: AppContainer,
    onOpenTab: (Tab) -> Unit,
    onDisconnectNow: (List<WarningReason>) -> Unit,
) {
    val vm = waveViewModel { HomeViewModel(container.scanRepository, container.exitPathChecker) }
    val state by vm.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onCheckWifi = vm::onCheckWifi,
        onDisconnectNow = {
            (state.check as? HomeCheck.Checked)?.let { onDisconnectNow(it.effective.reasons) }
        },
        onOpenTab = onOpenTab,
    )
}

// ---- Screen ----

@Composable
fun HomeScreen(
    state: HomeUiState,
    onCheckWifi: () -> Unit,
    onDisconnectNow: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    WaveScreen(title = "WAVE", modifier = modifier) {
        HeroCard(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                val (title, body) = bubbleText(state)
                SpeechBubble(
                    title = title,
                    body = body,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
                Box(Modifier.padding(top = Spacing.xl)) {
                    Wavey(mood = state.mood, size = 136.dp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                StatusCard(
                    icon = R.drawable.ic_wifi,
                    label = "Wi-Fi",
                    value = state.ssid ?: "Not connected",
                    modifier = Modifier.weight(1f),
                )
                StatusCard(
                    icon = R.drawable.ic_shield,
                    label = "Health",
                    value = healthStyle(state).label,
                    style = healthStyle(state),
                    modifier = Modifier.weight(1f),
                )
            }
            Column(Modifier.padding(top = Spacing.l)) {
                if (state.unsafe) {
                    PrimaryButton(
                        "Disconnect now",
                        onClick = onDisconnectNow,
                        icon = R.drawable.ic_warning,
                        containerColor = Danger,
                    )
                } else {
                    PrimaryButton(
                        "Check Wi-Fi",
                        onClick = onCheckWifi,
                        icon = R.drawable.ic_shield,
                        loading = state.check == HomeCheck.Checking,
                        enabled = state.ssid != null,
                    )
                }
                Text(
                    hintText(state),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate600,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.s),
                )
            }
        }

        SectionHeader("Security tools", trailing = "3 tools")
        ToolRow(R.drawable.ic_wifi, "Nearby Networks", { onOpenTab(Tab.NETWORKS) }, subtitle = "Find and verify networks around you.")
        ToolRow(R.drawable.ic_qr, "Scan Wi-Fi QR", { onOpenTab(Tab.SCAN_QR) }, subtitle = "Check a QR code before you join.")
        ToolRow(R.drawable.ic_monitor, "Connection Monitor", { onOpenTab(Tab.MONITOR) }, subtitle = "Watch the network you're on.")
    }
}

private fun bubbleText(state: HomeUiState): Pair<String, String> = when (val c = state.check) {
    HomeCheck.NotChecked -> "I'm ready to scan!" to "Let's check your Wi-Fi."
    HomeCheck.Checking -> "Checking…" to "Looking this network up in the registry."
    HomeCheck.NotOnWifi -> "You're not on Wi-Fi" to "Check nearby networks before you join one."
    is HomeCheck.Checked -> when {
        state.unsafe -> "Disconnect now!" to "This network looks fake."
        c.effective.verdict == Verdict.UNKNOWN -> "I can't verify this one" to "Avoid logging in to important accounts."
        c.exitPath == ExitPathStatus.PASSED -> "All clear!" to "This is the real network."
        else -> "Registry looks right" to "Now checking where your traffic goes…"
    }
}

private fun healthStyle(state: HomeUiState) = when (val c = state.check) {
    is HomeCheck.Checked -> c.effective.verdict.style()
    HomeCheck.Checking -> ExitPathStatus.PENDING.style().copy(label = "Checking")
    else -> NotCheckedStyle
}

private fun hintText(state: HomeUiState): String = when (val c = state.check) {
    HomeCheck.NotChecked -> "Your network has not been checked yet."
    HomeCheck.Checking -> "This takes a few seconds."
    HomeCheck.NotOnWifi -> "Connect to Wi-Fi to check it."
    is HomeCheck.Checked -> when {
        state.unsafe -> "Don't log in or pay for anything on this network."
        c.exitPath == ExitPathStatus.PENDING -> "Exit-path check in progress…"
        else -> "Checked just now."
    }
}

// ---- Previews: verified, warning, unknown, loading (+ not checked) ----

@Composable
private fun PreviewOf(state: HomeUiState) = WAVETheme {
    WaveBackground { HomeScreen(state, {}, {}, {}) }
}

@Preview(showSystemUi = true, name = "Verified")
@Composable
private fun HomeVerifiedPreview() = PreviewOf(
    HomeUiState("BATSTATEU-ICT", HomeCheck.Checked(FakeFixtures.campus, ExitPathStatus.PASSED)),
)

@Preview(showSystemUi = true, name = "Warning")
@Composable
private fun HomeWarningPreview() = PreviewOf(
    HomeUiState("BATSTATEU-ICT", HomeCheck.Checked(FakeFixtures.twin, null)),
)

@Preview(showSystemUi = true, name = "Unknown")
@Composable
private fun HomeUnknownPreview() = PreviewOf(
    HomeUiState("Library Guest", HomeCheck.Checked(FakeFixtures.library, ExitPathStatus.PASSED)),
)

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun HomeLoadingPreview() = PreviewOf(HomeUiState("BATSTATEU-ICT", HomeCheck.Checking))

@Preview(showSystemUi = true, name = "Not checked")
@Composable
private fun HomeNotCheckedPreview() = PreviewOf(HomeUiState("BATSTATEU-ICT", HomeCheck.NotChecked))
