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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.example.wave.ui.components.FitText
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveTopBar
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
import java.time.Instant
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import com.example.wave.model.CampusAsn
import com.example.wave.data.exitpath.ExitPathFetcher
import com.example.wave.data.admin.RegistryJsonWriter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.FilterChip
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.net.Uri
import android.content.ContentResolver
import java.util.Locale
import com.example.wave.ui.screens.nearby.NearbyRow
import com.example.wave.ui.components.StatusPill
import com.example.wave.model.Registry
import com.example.wave.domain.RegistryClassifier
import com.example.wave.data.registryOrNull
import com.example.wave.data.admin.SignalTracker
import com.example.wave.data.admin.RegistryDraftStore
import com.example.wave.data.admin.RegistryDraft

// ---- State ----

/** One survey pass: one row per network (its radios grouped), strongest first. */
data class SurveyScan(val rows: List<NearbyRow>, val atMillis: Long) {
    val networks: List<NetworkUi> get() = rows.map { it.network }
}

sealed interface SurveyUiState {
    data object Loading : SurveyUiState
    data object NeedsPermission : SurveyUiState

    /** [scans]: oldest first, at most [SurveyViewModel.MAX_SCANS]. [selected] indexes into it. */
    data class Loaded(
        val scans: List<SurveyScan>,
        val selected: Int,
        val scanning: Boolean = false,
        /** The admin's registry draft (null while it loads). */
        val draft: RegistryDraft? = null,
    ) : SurveyUiState {
        val shown: SurveyScan get() = scans[selected]
    }
}

/**
 * Manage registry, the campus admin's radar (after the admin gate). Each visit (and each "Scan again") records a
 * pass; the timeline steps back through the last few. Nothing here is compared with the registry: the admin is
 * deciding what goes in it. Tapping a network opens Review to Approve or Block its radios. The Approved and Blocked
 * lists hold only those decisions. Signal strengths from every pass feed each access point's normal range.
 */
class SurveyViewModel(
    private val repo: ScanRepository,
    permission: LocationPermissionGate,
    private val draftStore: RegistryDraftStore,
    private val tracker: SignalTracker,
    private val currentRegistry: () -> Registry?,
    private val exitPath: ExitPathFetcher?,
) : ViewModel() {
    private val _actions = MutableStateFlow(AdminActionsUi())
    val actions: StateFlow<AdminActionsUi> = _actions.asStateFlow()

    private val selected = MutableStateFlow(-1)
    private val scanning = MutableStateFlow(false)
    private val scans = MutableStateFlow(SessionScans.list)

    val state: StateFlow<SurveyUiState> =
        combine(permission.granted, scans, selected, scanning, draftStore.draft) { granted, list, sel, busy, draft ->
            when {
                !granted -> SurveyUiState.NeedsPermission
                list.isEmpty() -> SurveyUiState.Loading
                else -> SurveyUiState.Loaded(list, if (sel in list.indices) sel else list.lastIndex, busy, draft)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SurveyUiState.Loading)

    /** Weakest..strongest signal seen this session for an access point (lowercase BSSID). */
    fun observedRange(bssid: String): IntRange? = tracker.range(bssid)

    fun approve(row: NearbyRow, selected: Set<String>, building: String) = viewModelScope.launch {
        val aps = approvalsFor(row.network, reviewAps(row.accessPoints, tracker::range), selected, building)
        draftStore.update { it.approve(aps) }
    }

    fun block(row: NearbyRow, selected: Set<String>, reason: String) = viewModelScope.launch {
        val name = row.network.ssid.takeUnless { it == RegistryClassifier.HIDDEN_NAME }.orEmpty()
        draftStore.update { draft -> selected.fold(draft) { d, bssid -> d.block(bssid, name, reason) } }
    }

    fun remove(bssid: String) = viewModelScope.launch { draftStore.update { it.remove(bssid) } }

    fun clear() = viewModelScope.launch {
        draftStore.clear(currentRegistry())
        say("The list is empty.")
    }

    /** Asks the WAVE server which provider this Wi-Fi exits through. Must be on campus Wi-Fi. */
    fun detectProvider() {
        val fetcher = exitPath ?: return say("Turn on Real Wi-Fi in the demo menu to detect the provider.")
        if (_actions.value.detecting) return
        viewModelScope.launch {
            _actions.value = AdminActionsUi(detecting = true, message = "Asking the WAVE server…")
            val text = try {
                val answer = withTimeoutOrNull(30_000) { fetcher.fetch() }
                when {
                    answer == null -> "No answer within 30 s."
                    answer.asn == null -> "The server couldn't tell the provider: ${answer.note ?: "no reason given"}"
                    else -> {
                        draftStore.update { it.withCampusAsn(CampusAsn(answer.asn, answer.asName ?: "AS${answer.asn}")) }
                        "Added AS${answer.asn} (${answer.asName ?: "unknown name"}). Do this on campus Wi-Fi only."
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "No answer: ${e.message ?: e.javaClass.simpleName}"
            }
            _actions.value = AdminActionsUi(detecting = false, message = text)
        }
    }

    /** Saves the list as registry.json (CONTRACT §2, unsigned) to the file the admin picked. */
    fun publish(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        val draft = draftStore.draft.value ?: return@launch
        val registry = draft.copy(baseVersion = maxOf(draft.baseVersion, currentRegistry()?.version ?: 0)).toRegistry(Instant.now())
        val result = runCatching {
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(uri, "wt")?.use { it.write(RegistryJsonWriter.write(registry).toByteArray(Charsets.UTF_8)) }
                    ?: error("couldn't open the file")
            }
        }
        say(
            result.fold(
                { "Saved registry.json (version ${registry.version}). Next: sign it on the laptop, then upload it to the server." },
                { "Couldn't save: ${it.message}" },
            ),
        )
    }

    private fun say(text: String) {
        _actions.value = _actions.value.copy(message = text)
    }

    init {
        viewModelScope.launch { draftStore.load(currentRegistry()) }
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
            val rows = groupRows(repo.scanNearby().networks).sortedByDescending { it.network.signal }
            rows.flatMap { it.accessPoints }.forEach { tracker.record(it.bssid.lowercase(Locale.ROOT), it.signal) }
            SessionScans.list = (SessionScans.list + SurveyScan(rows, System.currentTimeMillis())).takeLast(MAX_SCANS)
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
    val vm = waveViewModel {
        SurveyViewModel(
            container.scanRepository,
            container.locationPermission,
            container.registryDraft,
            container.signalTracker,
            { container.registryRepository.state.value.registryOrNull },
            container.exitPathFetcher,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.publish(resolver, uri)
    }
    SurveyScreen(
        state,
        onBack,
        onSelectScan = vm::select,
        onScanAgain = vm::scanAgain,
        onNeedPermission = onNeedPermission,
        observedRange = vm::observedRange,
        onApprove = { row, sel, building -> vm.approve(row, sel, building) },
        onBlock = { row, sel, reason -> vm.block(row, sel, reason) },
        actions = actions,
        onRemove = { vm.remove(it) },
        onDetectProvider = vm::detectProvider,
        onPublish = { saveAs.launch("registry.json") },
        onClear = { vm.clear() },
    )
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
    observedRange: (bssid: String) -> IntRange? = { null },
    onApprove: (row: NearbyRow, selected: Set<String>, building: String) -> Unit = { _, _, _ -> },
    onBlock: (row: NearbyRow, selected: Set<String>, reason: String) -> Unit = { _, _, _ -> },
    actions: AdminActionsUi = AdminActionsUi(),
    onRemove: (bssid: String) -> Unit = {},
    onDetectProvider: () -> Unit = {},
    onPublish: () -> Unit = {},
    onClear: () -> Unit = {},
) {
    var reviewing by remember { mutableStateOf<NearbyRow?>(null) }
    var tab by rememberSaveable { mutableStateOf(AdminTab.SCANNED) }
    Column(modifier.fillMaxSize()) {
        WaveTopBar(
            title = "Manage registry",
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
                        "WAVE needs location access to scan nearby Wi-Fi.",
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
                    val draft = state.draft
                    val rows = state.shown.rows
                    val statusOf = { row: NearbyRow -> draftStatus(draft, row.accessPoints.map { it.bssid }) }
                    SurveyHub(
                        rows.map { it.network },
                        colorOf = { n -> statusOf(rows.first { it.network == n }).style().fg },
                        modifier = Modifier.fillMaxWidth(0.86f).align(Alignment.CenterHorizontally),
                    )
                    ScanTimeline(state.scans.size, state.selected, onSelectScan)
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(top = Spacing.xs)) {
                        FilterChip(tab == AdminTab.SCANNED, onClick = { tab = AdminTab.SCANNED }, label = { Text("Scanned (${rows.size})") })
                        FilterChip(tab == AdminTab.APPROVED, onClick = { tab = AdminTab.APPROVED }, label = { Text("Approved (${draft?.approved?.size ?: 0})") })
                        FilterChip(tab == AdminTab.BLOCKED, onClick = { tab = AdminTab.BLOCKED }, label = { Text("Blocked (${draft?.blocked?.size ?: 0})") })
                    }
                    Spacer(Modifier.height(Spacing.s))
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                        contentPadding = PaddingValues(bottom = Spacing.l),
                    ) {
                        when (tab) {
                            AdminTab.SCANNED -> {
                                if (rows.isEmpty()) item { EmptyNote("No Wi-Fi found. Tap the refresh button to scan again.") }
                                items(rows, key = { "s-" + it.network.bssid }) { row ->
                                    SurveyRow(row.network, statusOf(row), onReview = { reviewing = row })
                                }
                            }
                            AdminTab.APPROVED -> {
                                val approved = draft?.approved.orEmpty().sortedWith(compareBy({ it.ssid }, { it.bssid }))
                                if (approved.isEmpty()) item { EmptyNote("Nothing approved yet. Tap a scanned network to review it.") }
                                items(approved, key = { "a-" + it.bssid }) { ap ->
                                    DraftRow(
                                        title = ap.ssid,
                                        bssid = ap.bssid,
                                        detail = listOf(ap.band.label, "ch ${ap.channel}", ap.building.ifBlank { "no building" }).joinToString(" · "),
                                        status = DraftStatus.APPROVED,
                                        onRemove = { onRemove(ap.bssid) },
                                    )
                                }
                            }
                            AdminTab.BLOCKED -> {
                                val blocked = draft?.blocked.orEmpty().sortedBy { it.bssid }
                                if (blocked.isEmpty()) item { EmptyNote("Nothing blocked yet. Tap a scanned network to review it.") }
                                items(blocked, key = { "b-" + it.bssid }) { b ->
                                    DraftRow(
                                        title = b.ssid.ifEmpty { "Hidden network" },
                                        bssid = b.bssid,
                                        detail = b.reason,
                                        status = DraftStatus.BLOCKED,
                                        onRemove = { onRemove(b.bssid) },
                                    )
                                }
                            }
                        }
                        if (draft != null) {
                            item { AdminActionsCard(draft, actions, onDetectProvider, onPublish, onClear) }
                        }
                    }
                }
            }
        }
    }
    reviewing?.let { row ->
        ReviewSheet(
            row = row,
            draft = (state as? SurveyUiState.Loaded)?.draft,
            observedRange = observedRange,
            onDismiss = { reviewing = null },
            onApprove = { sel, building -> onApprove(row, sel, building); reviewing = null },
            onBlock = { sel, reason -> onBlock(row, sel, reason); reviewing = null },
        )
    }
}

/**
 * "WAVE" in the middle, one Wi-Fi node per scanned network around it (up to six), joined by spokes. Each node's ring
 * shows the admin's decision (Approved / Blocked / New), not a registry comparison.
 */
@Composable
private fun SurveyHub(networks: List<NetworkUi>, colorOf: (NetworkUi) -> Color, modifier: Modifier = Modifier) {
    val nodes = networks.take(6)
    BoxWithConstraints(
        modifier
            .height(220.dp)
            .clearAndSetSemantics { contentDescription = "Map of ${networks.size} scanned networks" },
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
                    .border(2.dp, colorOf(nodes[i]).copy(alpha = 0.8f), CircleShape),
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

/** One scanned network: its own details and the admin's decision, never a registry comparison. Tap to review. */
@Composable
private fun SurveyRow(network: NetworkUi, status: DraftStatus, onReview: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    WaveCard(Modifier.fillMaxWidth().clip(shape), color = Color.White, shape = shape, padding = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = "Review", role = Role.Button, onClick = onReview)
                .heightIn(min = 62.dp)
                .padding(start = Spacing.l, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_wifi), contentDescription = null, tint = Blue700, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                FitText(network.ssid, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Navy900)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${network.security.label} · ${network.signal} dBm", style = MaterialTheme.typography.bodySmall, color = Slate600)
                    Spacer(Modifier.width(Spacing.s))
                    StatusPill(status.style(), compact = true)
                }
            }
            IconButton(onClick = onReview) {
                Box(
                    Modifier.size(34.dp).border(2.dp, Blue700, RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = "Review ${network.ssid}", tint = Blue700, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ---- Previews: scanned list, loading, permission ----

@Composable
private fun PreviewOf(state: SurveyUiState) = WAVETheme { WaveBackground { SurveyScreen(state, {}, {}, {}, {}) } }

private val previewScan = SurveyScan(listOf(FakeFixtures.campus, FakeFixtures.twin, FakeFixtures.library).map { NearbyRow(it, listOf(it)) }, 0L)

@Preview(showSystemUi = true, name = "Scanned networks")
@Composable
private fun SurveyLoadedPreview() = PreviewOf(SurveyUiState.Loaded(List(4) { previewScan }, selected = 3))

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun SurveyLoadingPreview() = PreviewOf(SurveyUiState.Loading)

@Preview(showSystemUi = true, name = "Needs permission")
@Composable
private fun SurveyPermissionPreview() = PreviewOf(SurveyUiState.NeedsPermission)
