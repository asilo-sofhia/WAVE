package com.example.wave.ui.screens.nearby

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.wave.R
import com.example.wave.data.LocationPermissionGate
import com.example.wave.data.ScanRepository
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.di.AppContainer
import com.example.wave.model.NetworkUi
import com.example.wave.model.RegistryInfo
import com.example.wave.model.ScanResult
import com.example.wave.model.Verdict
import com.example.wave.ui.components.NetworkRow
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SectionHeader
import com.example.wave.ui.components.TopBarAction
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveTopBar
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.BrandIcon
import com.example.wave.ui.theme.Divider
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.VerifiedFg
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WaveShapes
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
    data class Loaded(val result: ScanResult) : NearbyUiState
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

/**
 * The note under a row. A WARNING that shares its name with a verified network is the classic evil twin,
 * so it gets the mockup's line; otherwise the first reason.
 */
fun noteFor(network: NetworkUi, all: List<NetworkUi>): String? = when {
    network.verdict != Verdict.WARNING -> null
    all.any { it.verdict == Verdict.VERIFIED && it.ssid == network.ssid } -> "Same name, different access point"
    else -> network.reasons.firstOrNull()?.title
}

// ---- Route ----

@Composable
fun NearbyRoute(
    container: AppContainer,
    onOpenNetwork: (bssid: String) -> Unit,
    onNeedPermission: () -> Unit,
) {
    val vm = waveViewModel { NearbyViewModel(container.scanRepository, container.locationPermission) }
    val state by vm.state.collectAsStateWithLifecycle()
    NearbyScreen(
        state = state,
        onRefresh = vm::refresh,
        onOpenNetwork = onOpenNetwork,
        onNeedPermission = onNeedPermission,
    )
}

// ---- Screen (layout follows docs/mockups/3 - Wifi Screen.png; one page, only the list area scrolls if needed) ----

@Composable
fun NearbyScreen(
    state: NearbyUiState,
    onRefresh: () -> Unit,
    onOpenNetwork: (String) -> Unit,
    onNeedPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        WaveTopBar(
            title = "Nearby Networks",
            actions = {
                if (state !is NearbyUiState.NeedsPermission) {
                    TopBarAction(R.drawable.ic_refresh, "Scan again", onRefresh)
                }
            },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Spacing.gutter),
        ) {
            SummaryCard(state, onRefresh)
            Spacer(Modifier.height(Spacing.s))
            when (state) {
                NearbyUiState.NeedsPermission -> PermissionCard(onNeedPermission)
                NearbyUiState.Empty -> {
                    Spacer(Modifier.height(Spacing.s))
                    Text(
                        "No Wi-Fi networks nearby. Move closer to an access point and scan again.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Slate600,
                    )
                }
                NearbyUiState.Loading -> {
                    SectionHeader("Available networks", Modifier.padding(top = Spacing.s))
                    Spacer(Modifier.height(Spacing.s))
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) { repeat(4) { SkeletonRow() } }
                }
                is NearbyUiState.Loaded -> {
                    RegistryLine(state.result.registry)
                    SectionHeader(
                        "Available networks",
                        Modifier.padding(top = Spacing.xs),
                        trailing = "${state.result.networks.size} results",
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    // The page itself never scrolls; on very short screens only this list does.
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                        contentPadding = PaddingValues(top = 2.dp, bottom = Spacing.s),
                    ) {
                        items(state.result.networks, key = { it.bssid }) { network ->
                            NetworkRow(
                                network = network,
                                onClick = { onOpenNetwork(network.bssid) },
                                note = noteFor(network, state.result.networks),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Glass card: white Wi-Fi circle, "N networks found", "Last scanned just now", blue Refresh. */
@Composable
private fun SummaryCard(state: NearbyUiState, onRefresh: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val (title, subtitle) = when (state) {
        NearbyUiState.Loading -> "Scanning…" to "Checking each network against the registry."
        NearbyUiState.NeedsPermission -> "Location is off" to "Needed to list nearby Wi-Fi."
        NearbyUiState.Empty -> "No networks found" to "Last scanned just now"
        is NearbyUiState.Loaded -> "${state.result.networks.size} networks found" to "Last scanned just now"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Color(0x80FFFFFF), Color(0x4DFFFFFF))), shape)
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), shape)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // White circle with a soft halo ring and the blue Wi-Fi mark.
        Box(
            Modifier
                .size(58.dp)
                .background(Color.White.copy(alpha = 0.45f), CircleShape)
                .padding(5.dp)
                .shadow(6.dp, CircleShape, spotColor = Color(0x553B82C4))
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (state == NearbyUiState.Loading) {
                CircularProgressIndicator(color = BrandIcon, strokeWidth = 2.5.dp, modifier = Modifier.size(44.dp))
            }
            Icon(painterResource(R.drawable.ic_wifi), null, tint = BrandIcon, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Navy900)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Slate600)
        }
        if (state is NearbyUiState.Loaded || state is NearbyUiState.Empty) {
            Text(
                "Refresh",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = RefreshBlue,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onRefresh)
                    .padding(horizontal = Spacing.s, vertical = 14.dp),
            )
        }
    }
}

private val RefreshBlue = Color(0xFF0A66D9) // 5.3:1 on white

@Composable
private fun RegistryLine(registry: RegistryInfo) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.xs).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_database), null, tint = VerifiedFg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(
            "Using ${registry.source}",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate600,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Updated ${registry.updatedAt}",
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            color = VerifiedFg,
        )
    }
}

@Composable
private fun PermissionCard(onNeedPermission: () -> Unit) {
    WaveCard(Modifier.fillMaxWidth()) {
        Text(
            "WAVE needs location access to list nearby Wi-Fi. Your location never leaves your phone.",
            style = MaterialTheme.typography.bodyLarge,
            color = Navy900,
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
    WaveCard(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Loading network" },
        color = Color.White,
        padding = 14.dp,
    ) {
        Row(Modifier.alpha(alpha.value), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(48.dp).background(Divider, RoundedCornerShape(14.dp)))
            Spacer(Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Spacer(Modifier.size(width = 150.dp, height = 14.dp).background(Divider, WaveShapes.pill))
                Spacer(Modifier.size(width = 110.dp, height = 12.dp).background(Divider, WaveShapes.pill))
            }
        }
    }
}

// ---- Previews: verified, warning, unknown, loading (+ permission, empty) ----

@Composable
private fun PreviewOf(state: NearbyUiState) = WAVETheme {
    WaveBackground { NearbyScreen(state, {}, {}, {}) }
}

private fun loaded(vararg n: NetworkUi) = NearbyUiState.Loaded(ScanResult(n.toList(), FakeFixtures.registry, 0L))

@Preview(showSystemUi = true, name = "Mockup list (verified, warning, unknown)")
@Composable
private fun NearbyMockupPreview() =
    PreviewOf(loaded(FakeFixtures.campus, FakeFixtures.twin, FakeFixtures.library, FakeFixtures.lab))

@Preview(showSystemUi = true, name = "Verified (all safe)")
@Composable
private fun NearbyVerifiedPreview() = PreviewOf(loaded(FakeFixtures.campus, FakeFixtures.lab))

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
