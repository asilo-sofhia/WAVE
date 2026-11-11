package com.example.wave.ui.screens.nearby

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.wave.R
import com.example.wave.data.LocationPermissionGate
import com.example.wave.data.ScanRepository
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.di.AppContainer
import com.example.wave.model.ScanResult
import com.example.wave.model.Verdict
import com.example.wave.model.WaveyMood
import com.example.wave.ui.components.IconTile
import com.example.wave.ui.components.NetworkRow
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SectionHeader
import com.example.wave.ui.components.ToolRow
import com.example.wave.ui.components.TopBarAction
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveScreen
import com.example.wave.ui.components.WaveTextButton
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.navigation.Tab
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Divider
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WaveShapes
import com.example.wave.ui.theme.WarningBg
import com.example.wave.ui.theme.WarningFg
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---- State ----

sealed interface NearbyUiState {
    data object Loading : NearbyUiState
    data object NeedsPermission : NearbyUiState
    data object Empty : NearbyUiState
    data class Loaded(val result: ScanResult) : NearbyUiState {
        val twinSsids: List<String> = result.networks.filter { it.verdict == Verdict.WARNING }.map { it.ssid }.distinct()
        val mood: WaveyMood = when {
            twinSsids.isNotEmpty() -> WaveyMood.UNCOMFORTABLE
            result.networks.all { it.verdict == Verdict.UNKNOWN } -> WaveyMood.CURIOUS
            else -> WaveyMood.NORMAL
        }
    }
}

class NearbyViewModel(
    private val repo: ScanRepository,
    permission: LocationPermissionGate,
) : ViewModel() {
    private val scan = MutableStateFlow<ScanResult?>(null)
    private var job: Job? = null

    val state: StateFlow<NearbyUiState> = combine(permission.granted, scan) { granted, result ->
        when {
            !granted -> NearbyUiState.NeedsPermission
            result == null -> NearbyUiState.Loading
            result.networks.isEmpty() -> NearbyUiState.Empty
            else -> NearbyUiState.Loaded(result)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyUiState.Loading)

    init {
        viewModelScope.launch {
            permission.granted.collect { granted -> if (granted && scan.value == null) refresh() }
        }
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch {
            scan.value = null
            scan.value = repo.scanNearby()
        }
    }
}

// ---- Route ----

@Composable
fun NearbyRoute(
    container: AppContainer,
    onOpenNetwork: (bssid: String) -> Unit,
    onNeedPermission: () -> Unit,
    onOpenTab: (Tab) -> Unit,
) {
    val vm = waveViewModel { NearbyViewModel(container.scanRepository, container.locationPermission) }
    val state by vm.state.collectAsStateWithLifecycle()
    NearbyScreen(
        state = state,
        onRefresh = vm::refresh,
        onOpenNetwork = onOpenNetwork,
        onNeedPermission = onNeedPermission,
        onOpenTab = onOpenTab,
    )
}

// ---- Screen ----

@Composable
fun NearbyScreen(
    state: NearbyUiState,
    onRefresh: () -> Unit,
    onOpenNetwork: (String) -> Unit,
    onNeedPermission: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    WaveScreen(
        title = "Nearby Networks",
        modifier = modifier,
        actions = {
            if (state !is NearbyUiState.NeedsPermission) {
                TopBarAction(R.drawable.ic_refresh, "Scan again", onRefresh)
            }
        },
    ) {
        SummaryCard(state, onRefresh)

        when (state) {
            NearbyUiState.NeedsPermission -> PermissionCard(onNeedPermission)
            NearbyUiState.Loading -> {
                SectionHeader("Available networks")
                repeat(3) { SkeletonRow() }
            }
            NearbyUiState.Empty -> Text(
                "No Wi-Fi networks nearby. Move closer to an access point and scan again.",
                style = MaterialTheme.typography.bodyLarge,
                color = Slate600,
            )
            is NearbyUiState.Loaded -> {
                RegistryLine(state.result.registry.source, state.result.registry.updatedAt)
                if (state.twinSsids.isNotEmpty()) TwinBanner(state.twinSsids)
                SectionHeader("Available networks", trailing = "${state.result.networks.size} results")
                state.result.networks.forEach { network ->
                    NetworkRow(network, onClick = { onOpenNetwork(network.bssid) })
                }
            }
        }

        SectionHeader("Quick actions")
        ToolRow(R.drawable.ic_qr, "Scan Wi-Fi QR", { onOpenTab(Tab.SCAN_QR) })
        ToolRow(R.drawable.ic_monitor, "Connection Monitor", { onOpenTab(Tab.MONITOR) })
    }
}

@Composable
private fun SummaryCard(state: NearbyUiState, onRefresh: () -> Unit) {
    val mood = when (state) {
        NearbyUiState.Loading, NearbyUiState.NeedsPermission -> WaveyMood.CURIOUS
        NearbyUiState.Empty -> WaveyMood.NORMAL
        is NearbyUiState.Loaded -> state.mood
    }
    val (title, subtitle) = when (state) {
        NearbyUiState.Loading -> "Scanning…" to "Checking each network against the registry."
        NearbyUiState.NeedsPermission -> "Location is off" to "Needed to list nearby Wi-Fi."
        NearbyUiState.Empty -> "No networks found" to "Last scanned just now"
        is NearbyUiState.Loaded -> "${state.result.networks.size} networks found" to "Last scanned just now"
    }
    WaveCard(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wavey(mood, size = 72.dp)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Navy900)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Slate600)
            }
            if (state is NearbyUiState.Loaded || state is NearbyUiState.Empty) {
                WaveTextButton("Refresh", onClick = onRefresh)
            }
        }
    }
}

@Composable
private fun RegistryLine(source: String, updatedAt: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_storage), null, tint = Slate600, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.s))
        Text("Using $source", style = MaterialTheme.typography.bodyMedium, color = Slate600, modifier = Modifier.weight(1f))
        Text("Updated $updatedAt", style = MaterialTheme.typography.bodyMedium, color = Slate600)
    }
}

@Composable
private fun TwinBanner(ssids: List<String>) {
    WaveCard(Modifier.fillMaxWidth(), color = WarningBg) {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
            IconTile(R.drawable.ic_warning, tint = WarningFg, background = WarningBg.copy(alpha = 0f), size = 40.dp)
            Spacer(Modifier.width(Spacing.s))
            Column {
                Text(
                    "A copy of ${ssids.joinToString()} is nearby",
                    style = MaterialTheme.typography.titleSmall,
                    color = WarningFg,
                )
                Text(
                    "Only join the one marked Verified.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Navy900,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onNeedPermission: () -> Unit) {
    WaveCard(Modifier.fillMaxWidth()) {
        Text(
            "WAVE needs location access to list nearby Wi-Fi. Your location never leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = Navy900,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(Spacing.l))
        PrimaryButton("Allow location", onClick = onNeedPermission, icon = R.drawable.ic_location)
    }
}

@Composable
private fun SkeletonRow() {
    val alpha = rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "skeleton-alpha",
    )
    WaveCard(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Loading network" }) {
        Row(Modifier.alpha(alpha.value), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(48.dp).background(Divider, WaveShapes.tile))
            Spacer(Modifier.width(Spacing.m))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Spacer(Modifier.size(width = 140.dp, height = 14.dp).background(Divider, WaveShapes.pill))
                Spacer(Modifier.size(width = 100.dp, height = 12.dp).background(Divider, WaveShapes.pill))
            }
        }
    }
}

// ---- Previews: verified, warning, unknown, loading (+ permission, empty) ----

@Composable
private fun PreviewOf(state: NearbyUiState) = WAVETheme {
    WaveBackground { NearbyScreen(state, {}, {}, {}, {}) }
}

private fun loaded(vararg n: com.example.wave.model.NetworkUi) =
    NearbyUiState.Loaded(ScanResult(n.toList(), FakeFixtures.registry, 0L))

@Preview(showSystemUi = true, name = "Verified (all safe)")
@Composable
private fun NearbyVerifiedPreview() = PreviewOf(loaded(FakeFixtures.campus, FakeFixtures.lab))

@Preview(showSystemUi = true, name = "Warning (twin nearby)")
@Composable
private fun NearbyWarningPreview() =
    PreviewOf(loaded(FakeFixtures.campus, FakeFixtures.twin, FakeFixtures.library, FakeFixtures.lab))

@Preview(showSystemUi = true, name = "Unknown only")
@Composable
private fun NearbyUnknownPreview() = PreviewOf(loaded(FakeFixtures.library, FakeFixtures.cafe))

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun NearbyLoadingPreview() = PreviewOf(NearbyUiState.Loading)

@Preview(showSystemUi = true, name = "Needs permission")
@Composable
private fun NearbyPermissionPreview() = PreviewOf(NearbyUiState.NeedsPermission)

@Preview(showSystemUi = true, name = "Empty")
@Composable
private fun NearbyEmptyPreview() = PreviewOf(NearbyUiState.Empty)
