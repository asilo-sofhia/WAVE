package com.example.wave.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wave.R
import com.example.wave.data.ScanRepository
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.di.AppContainer
import com.example.wave.model.NetworkUi
import com.example.wave.model.Verdict
import com.example.wave.model.WaveyMood
import com.example.wave.model.signalQuality
import com.example.wave.model.waveyMoodFor
import com.example.wave.ui.components.HeroCard
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.ReasonList
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.TechDetailsToggle
import com.example.wave.ui.components.VerdictChip
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveScreen
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.components.openWifiSettings
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WarningFg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ---- State ----

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data object NotFound : DetailUiState
    data class Loaded(val network: NetworkUi) : DetailUiState
}

class DetailViewModel(repo: ScanRepository, bssid: String) : ViewModel() {
    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    init {
        _state.value = repo.findNetwork(bssid)?.let { DetailUiState.Loaded(it) } ?: DetailUiState.NotFound
    }
}

// ---- Route ----

@Composable
fun DetailRoute(container: AppContainer, bssid: String, onBack: () -> Unit) {
    val vm = waveViewModel(key = "detail-$bssid") { DetailViewModel(container.scanRepository, bssid) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DetailScreen(state = state, onBack = onBack, onOpenWifiSettings = { openWifiSettings(context) })
}

// ---- Screen ----

@Composable
fun DetailScreen(
    state: DetailUiState,
    onBack: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WaveScreen(title = "Network details", onBack = onBack, modifier = modifier) {
        when (state) {
            DetailUiState.Loading -> Column(
                Modifier.fillMaxWidth().padding(top = Spacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                Wavey(WaveyMood.CURIOUS, size = 140.dp)
                CircularProgressIndicator()
            }
            DetailUiState.NotFound -> {
                Text(
                    "This network is no longer in range. Scan again to see what's nearby.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Slate600,
                )
                PrimaryButton("Back to networks", onClick = onBack)
            }
            is DetailUiState.Loaded -> Loaded(state.network, onBack, onOpenWifiSettings)
        }
    }
}

@Composable
private fun Loaded(network: NetworkUi, onBack: () -> Unit, onOpenWifiSettings: () -> Unit) {
    val (title, body) = when (network.verdict) {
        Verdict.VERIFIED -> "Looks legit" to
            "This access point is on the official registry and its security matches."
        Verdict.UNKNOWN -> "Can't verify this network" to
            "It isn't in the registry, so WAVE can't tell if it's real. If you use it, avoid logging in to important accounts."
        Verdict.WARNING -> "Don't connect" to
            "This looks like a fake copy of ${network.ssid}. Anything you send on it could be seen."
    }

    HeroCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Wavey(waveyMoodFor(network.verdict, null, connected = false), size = 150.dp)
            Spacer(Modifier.height(Spacing.s))
            Text(network.ssid, style = MaterialTheme.typography.headlineSmall, color = Navy900, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.s))
            VerdictChip(network.verdict)
            Spacer(Modifier.height(Spacing.l))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics { heading() }) {
                if (network.verdict == Verdict.WARNING) {
                    Icon(painterResource(R.drawable.ic_warning), null, tint = WarningFg, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(Spacing.s))
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (network.verdict == Verdict.WARNING) WarningFg else Navy900,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Slate600, textAlign = TextAlign.Center)
        }
    }

    if (network.reasons.isNotEmpty()) {
        WaveCard(Modifier.fillMaxWidth()) {
            Text("Why WAVE is worried", style = MaterialTheme.typography.titleMedium, color = Navy900)
            Spacer(Modifier.height(Spacing.m))
            ReasonList(network.reasons)
        }
    }

    // One clear action per verdict.
    when (network.verdict) {
        Verdict.VERIFIED -> PrimaryButton("Connect in Wi-Fi settings", onClick = onOpenWifiSettings, icon = R.drawable.ic_wifi)
        Verdict.UNKNOWN -> SecondaryButton("Back to networks", onClick = onBack)
        Verdict.WARNING -> PrimaryButton("Back to safe networks", onClick = onBack, icon = R.drawable.ic_arrow_back)
    }

    TechDetailsToggle(technicalRows(network))
}

fun technicalRows(network: NetworkUi): List<Pair<String, String>> = buildList {
    add("SSID" to network.ssid)
    add("BSSID" to network.bssid)
    add("Security" to network.security.label)
    add("Band" to network.band.label)
    add("Signal" to "${network.signal} dBm (${signalQuality(network.signal).label})")
    add("Verdict" to network.verdict.name)
    if (network.reasons.isNotEmpty()) add("Reason codes" to network.reasons.joinToString("\n") { it.name })
}

// ---- Previews: verified, warning, unknown, loading ----

@Composable
private fun PreviewOf(state: DetailUiState) = WAVETheme {
    WaveBackground { DetailScreen(state, {}, {}) }
}

@Preview(showSystemUi = true, name = "Verified")
@Composable
private fun DetailVerifiedPreview() = PreviewOf(DetailUiState.Loaded(FakeFixtures.campus))

@Preview(showSystemUi = true, name = "Warning")
@Composable
private fun DetailWarningPreview() = PreviewOf(DetailUiState.Loaded(FakeFixtures.twin))

@Preview(showSystemUi = true, name = "Unknown")
@Composable
private fun DetailUnknownPreview() = PreviewOf(DetailUiState.Loaded(FakeFixtures.library))

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun DetailLoadingPreview() = PreviewOf(DetailUiState.Loading)
