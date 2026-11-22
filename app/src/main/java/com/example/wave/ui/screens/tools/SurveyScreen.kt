package com.example.wave.ui.screens.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
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
import com.example.wave.model.Verdict
import com.example.wave.ui.components.FitText
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SectionHeader
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveTopBar
import com.example.wave.ui.components.style
import com.example.wave.ui.screens.nearby.groupRows
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Blue700
import com.example.wave.ui.theme.BrandIcon
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

/** One survey pass: the networks seen (one entry per network), strongest first. */
data class SurveyScan(val networks: List<NetworkUi>, val atMillis: Long)

sealed interface SurveyUiState {
    data object Loading : SurveyUiState
    data object NeedsPermission : SurveyUiState

    /** [scans]: oldest first, at most [SurveyViewModel.MAX_SCANS]. [selected] indexes into it. */
    data class Loaded(val scans: List<SurveyScan>, val selected: Int, val scanning: Boolean = false) : SurveyUiState {
        val shown: SurveyScan get() = scans[selected]
    }
}

/**
 * Demo admin survey. Each visit (and each "Scan again") records a pass; the timeline steps back through the
 * last few. Kept in memory for this app session only: the real registry is edited with backend/signing.
 */
class SurveyViewModel(private val repo: ScanRepository, permission: LocationPermissionGate) : ViewModel() {
    private val selected = MutableStateFlow(-1)
    private val scanning = MutableStateFlow(false)
    private val scans = MutableStateFlow(SessionScans.list)

    val state: StateFlow<SurveyUiState> =
        combine(permission.granted, scans, selected, scanning) { granted, list, sel, busy ->
            when {
                !granted -> SurveyUiState.NeedsPermission
                list.isEmpty() -> SurveyUiState.Loading
                else -> SurveyUiState.Loaded(list, if (sel in list.indices) sel else list.lastIndex, busy)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SurveyUiState.Loading)

    init {
        viewModelScope.launch {
            var scanned = false
            permission.granted.collect { granted -> if (granted && !scanned) { scanned = true; scanAgain() } }
        }
    }

    fun select(index: Int) { selected.value = index }

    fun scanAgain() {
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true
            val networks = groupRows(repo.scanNearby().networks).map { it.network }.sortedByDescending { it.signal }
            SessionScans.list = (SessionScans.list + SurveyScan(networks, System.currentTimeMillis())).takeLast(MAX_SCANS)
            scans.value = SessionScans.list
            selected.value = SessionScans.list.lastIndex
            scanning.value = false
        }
    }

    /** Survey passes survive leaving and reopening the survey (same app session). */
    private object SessionScans { var list: List<SurveyScan> = emptyList() }

    companion object { const val MAX_SCANS = 4 }
}

// ---- Route ----

@Composable
fun SurveyRoute(container: AppContainer, onBack: () -> Unit, onNeedPermission: () -> Unit) {
    val vm = waveViewModel { SurveyViewModel(container.scanRepository, container.locationPermission) }
    val state by vm.state.collectAsStateWithLifecycle()
    SurveyScreen(state, onBack, onSelectScan = vm::select, onScanAgain = vm::scanAgain, onNeedPermission = onNeedPermission)
}

// ---- Screen ----

@Composable
fun SurveyScreen(
    state: SurveyUiState,
    onBack: () -> Unit,
    onSelectScan: (Int) -> Unit,
    onScanAgain: () -> Unit,
    onNeedPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<NetworkUi?>(null) }
    Column(modifier.fillMaxSize()) {
        WaveTopBar(
            title = "Network survey",
            onBack = onBack,
            actions = {
                if (state is SurveyUiState.Loaded) {
                    IconButton(onClick = onScanAgain, enabled = !state.scanning) {
                        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Scan again", tint = Blue700)
                    }
                }
            },
        )
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Spacing.gutter)) {
            when (state) {
                SurveyUiState.NeedsPermission -> WaveCard(Modifier.fillMaxWidth()) {
                    Text(
                        "WAVE needs location access to survey nearby Wi-Fi.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Navy900,
                    )
                    Spacer(Modifier.height(Spacing.l))
                    PrimaryButton("Allow location", onClick = onNeedPermission, icon = R.drawable.ic_location)
                }
                SurveyUiState.Loading -> Box(Modifier.fillMaxWidth().padding(top = Spacing.xxl), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is SurveyUiState.Loaded -> {
                    val networks = state.shown.networks
                    SurveyHub(networks, Modifier.fillMaxWidth(0.86f).align(Alignment.CenterHorizontally))
                    ScanTimeline(state.scans.size, state.selected, onSelectScan)
                    SectionHeader(
                        "Surveyed networks",
                        Modifier.padding(top = Spacing.s).semantics { heading() },
                        trailing = if (networks.size == 1) "1 result" else "${networks.size} results",
                    )
                    Spacer(Modifier.height(Spacing.s))
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                        contentPadding = PaddingValues(bottom = Spacing.l),
                    ) {
                        items(networks, key = { it.bssid }) { n -> SurveyRow(n, onEdit = { editing = n }) }
                    }
                }
            }
        }
    }
    editing?.let { n ->
        AlertDialog(
            onDismissRequest = { editing = null },
            confirmButton = { TextButton(onClick = { editing = null }) { Text("OK") } },
            title = { Text("Edit ${n.ssid}") },
            text = {
                Text(
                    "This is a demo. Registry entries are changed with the campus signing tool, which publishes a " +
                        "new signed registry that every phone downloads. Editing from the app is not available yet.",
                )
            },
        )
    }
}

/** "WAVE" in the middle, one Wi-Fi node per surveyed network around it (up to six), joined by spokes. */
@Composable
private fun SurveyHub(networks: List<NetworkUi>, modifier: Modifier = Modifier) {
    val nodes = networks.take(6)
    BoxWithConstraints(
        modifier
            .height(220.dp)
            .clearAndSetSemantics { contentDescription = "Survey map with ${networks.size} networks" },
        contentAlignment = Alignment.Center,
    ) {
        val node = 46.dp
        val hub = 84.dp
        val radiusX = (maxWidth - node) / 2
        val radiusY = (maxHeight - node) / 2
        val positions = nodes.indices.map { i ->
            val a = Math.toRadians(-90.0 + 360.0 * i / nodes.size.coerceAtLeast(1))
            Math.cos(a).toFloat() to Math.sin(a).toFloat()
        }
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            positions.forEach { (x, y) ->
                drawLine(BrandIcon.copy(alpha = 0.7f), c, Offset(c.x + x * radiusX.toPx(), c.y + y * radiusY.toPx()), strokeWidth = 2.dp.toPx())
            }
        }
        positions.forEachIndexed { i, (x, y) ->
            Box(
                Modifier
                    .offset(x = radiusX * x, y = radiusY * y)
                    .size(node)
                    .background(Color.White, CircleShape)
                    .border(2.dp, nodes[i].verdict.style().fg.copy(alpha = 0.8f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_wifi), null, tint = Blue700, modifier = Modifier.size(22.dp))
            }
        }
        Box(
            Modifier
                .size(hub)
                .shadow(10.dp, CircleShape, spotColor = Color(0x553B82C4))
                .background(Color.White, CircleShape)
                .border(3.dp, BrandIcon, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("WAVE", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp), color = Navy900)
        }
    }
}

/** "Previous scan ●—●—●—◉ Latest scan": tap a dot to see that pass. */
@Composable
private fun ScanTimeline(count: Int, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Text("Previous scan", style = MaterialTheme.typography.bodySmall, color = Slate600)
        Box(Modifier.weight(1f).padding(horizontal = Spacing.s), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(BrandIcon.copy(alpha = 0.5f)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (count == 1) Arrangement.End else Arrangement.SpaceBetween) {
                repeat(count) { i ->
                    val isSelected = i == selected
                    Box(
                        Modifier
                            .size(48.dp)
                            .clickable(onClickLabel = "Show this scan", role = Role.RadioButton) { onSelect(i) }
                            .semantics {
                                this.selected = isSelected
                                contentDescription = if (i == count - 1) "Latest scan" else "Scan ${i + 1} of $count"
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(if (isSelected) 18.dp else 10.dp)
                                .background(if (isSelected) Color.White else BrandIcon, CircleShape)
                                .border(if (isSelected) 4.dp else 0.dp, BrandIcon, CircleShape),
                        )
                    }
                }
            }
        }
        Text("Latest scan", style = MaterialTheme.typography.bodySmall, color = Slate600)
    }
}

/** What the registry says about a surveyed network, in plain words. */
private fun registryStatus(n: NetworkUi): String = when (n.verdict) {
    Verdict.VERIFIED -> "Registry match"
    Verdict.WARNING -> "Doesn't match the registry"
    Verdict.UNKNOWN -> "Not in registry"
}

@Composable
private fun SurveyRow(network: NetworkUi, onEdit: () -> Unit) {
    val style = network.verdict.style()
    WaveCard(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(18.dp), padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(start = Spacing.l, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_wifi), contentDescription = null, tint = Blue700, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                FitText(network.ssid, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Navy900)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(style.icon), contentDescription = null, tint = style.fg, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(registryStatus(network), style = MaterialTheme.typography.bodySmall, color = style.fg)
                }
            }
            IconButton(onClick = onEdit) {
                Box(
                    Modifier.size(34.dp).border(2.dp, Blue700, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit ${network.ssid}", tint = Blue700, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---- Previews: verified, warning, unknown (one list), loading, permission ----

@Composable
private fun PreviewOf(state: SurveyUiState) = WAVETheme { WaveBackground { SurveyScreen(state, {}, {}, {}, {}) } }

private val previewScan = SurveyScan(listOf(FakeFixtures.campus, FakeFixtures.twin, FakeFixtures.library), 0L)

@Preview(showSystemUi = true, name = "Loaded (verified, warning, unknown)")
@Composable
private fun SurveyLoadedPreview() = PreviewOf(SurveyUiState.Loaded(List(4) { previewScan }, selected = 3))

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun SurveyLoadingPreview() = PreviewOf(SurveyUiState.Loading)

@Preview(showSystemUi = true, name = "Needs permission")
@Composable
private fun SurveyPermissionPreview() = PreviewOf(SurveyUiState.NeedsPermission)
