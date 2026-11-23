package com.example.wave.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.wave.R
import com.example.wave.data.ScanRepository
import com.example.wave.data.admin.RegistryDraft
import com.example.wave.data.admin.RegistryDraftStore
import com.example.wave.data.admin.SignalTracker
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.data.registryOrNull
import com.example.wave.di.AppContainer
import com.example.wave.domain.RegistryClassifier
import com.example.wave.model.NetworkUi
import com.example.wave.model.Registry
import com.example.wave.ui.components.AccessPointsCard
import com.example.wave.ui.components.ConnectionCard
import com.example.wave.ui.components.NetworkMetricsCard
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.StatusPill
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveScreen
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

// ---- State ----

sealed interface AdminScanUiState {
    data object NotFound : AdminScanUiState

    /** [accessPoints]: every radio broadcasting this name (incl. [network]). [draft] is null while it loads. */
    data class Loaded(
        val network: NetworkUi,
        val accessPoints: List<NetworkUi>,
        val draft: RegistryDraft?,
        val message: String? = null,
    ) : AdminScanUiState
}

/**
 * The admin's view of one scanned network: its raw details only, no comparison with the registry (the admin is
 * deciding what goes in it). Approve / Block write to the admin's list. The user's Network details is unchanged.
 */
class AdminScanViewModel(
    private val repo: ScanRepository,
    bssid: String,
    private val draftStore: RegistryDraftStore,
    private val tracker: SignalTracker,
    currentRegistry: () -> Registry?,
) : ViewModel() {
    private val snapshot = repo.findNetwork(bssid)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<AdminScanUiState> =
        combine(repo.connection, draftStore.draft, message) { connected, draft, msg ->
            val network = connected?.takeIf { it.bssid.equals(bssid, ignoreCase = true) } ?: snapshot
            if (network == null) AdminScanUiState.NotFound
            else AdminScanUiState.Loaded(network, aps(network), draft, msg)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            snapshot?.let { AdminScanUiState.Loaded(it, aps(it), draftStore.draft.value) } ?: AdminScanUiState.NotFound,
        )

    init {
        viewModelScope.launch { draftStore.load(currentRegistry()) }
        // What this screen shows counts towards each radio's normal signal range, like a radar pass.
        snapshot?.let { n -> aps(n).forEach { tracker.record(it.bssid.lowercase(Locale.ROOT), it.signal) } }
    }

    fun approve() {
        val loaded = state.value as? AdminScanUiState.Loaded ?: return
        val rows = reviewAps(loaded.accessPoints, tracker::range)
        val targets = adminApproveTargets(rows)
        viewModelScope.launch {
            draftStore.update { it.approve(approvalsFor(loaded.network, rows, targets, building = "")) }
            message.value = "Approved ${targets.size} access point${if (targets.size == 1) "" else "s"}. It's in Manage registry."
        }
    }

    fun block() {
        val loaded = state.value as? AdminScanUiState.Loaded ?: return
        val targets = adminBlockTargets(reviewAps(loaded.accessPoints, tracker::range))
        val name = loaded.network.ssid.takeUnless { it == RegistryClassifier.HIDDEN_NAME }.orEmpty()
        viewModelScope.launch {
            draftStore.update { d -> targets.fold(d) { acc, b -> acc.block(b, name, "Blocked on the admin scan") } }
            message.value = "Blocked ${targets.size} access point${if (targets.size == 1) "" else "s"}. It's in Manage registry."
        }
    }

    private fun aps(n: NetworkUi): List<NetworkUi> = repo.accessPointsOf(n).ifEmpty { listOf(n) }
}

// ---- Route ----

@Composable
fun AdminScanRoute(
    container: AppContainer,
    bssid: String,
    onBack: () -> Unit,
    onOpenNetwork: (bssid: String) -> Unit,
    onViewDetails: (AdminTab) -> Unit,
) {
    val vm = waveViewModel(key = "adminscan-$bssid") {
        AdminScanViewModel(
            container.scanRepository,
            bssid,
            container.registryDraft,
            container.signalTracker,
            { container.registryRepository.state.value.registryOrNull },
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    AdminScanScreen(state, onBack, onApprove = vm::approve, onBlock = vm::block, onOpenNetwork = onOpenNetwork, onViewDetails = onViewDetails)
}

// ---- Screen ----

@Composable
fun AdminScanScreen(
    state: AdminScanUiState,
    onBack: () -> Unit,
    onApprove: () -> Unit,
    onBlock: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenNetwork: (bssid: String) -> Unit = {},
    onViewDetails: (AdminTab) -> Unit = {},
) {
    WaveScreen(title = "Admin Scan", onBack = onBack, modifier = modifier) {
        when (state) {
            AdminScanUiState.NotFound -> {
                Text("This network is no longer in range. Scan again to see what's nearby.", style = MaterialTheme.typography.bodyLarge, color = Slate600)
                PrimaryButton("Back to Wi-Fi radar", onClick = onBack)
            }
            is AdminScanUiState.Loaded -> Loaded(state, onApprove, onBlock, onOpenNetwork, onViewDetails)
        }
    }
}

@Composable
private fun Loaded(
    state: AdminScanUiState.Loaded,
    onApprove: () -> Unit,
    onBlock: () -> Unit,
    onOpenNetwork: (String) -> Unit,
    onViewDetails: (AdminTab) -> Unit,
) {
    val network = state.network
    val rows = reviewAps(state.accessPoints) { null }
    val status = draftStatus(state.draft, rows.map { it.bssid })

    // Which network this is, and the admin's decision so far (no registry verdict here).
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(network.ssid, style = MaterialTheme.typography.headlineSmall, color = Navy900, modifier = Modifier.weight(1f).semantics { heading() })
        Spacer(Modifier.width(Spacing.s))
        StatusPill(status.style())
    }

    network.connection?.let { ConnectionCard(it) }
    NetworkMetricsCard(network)
    AccessPointsCard(network, state.accessPoints, onOpen = onOpenNetwork)

    val canApproveNow = canApprove(network) && adminApproveTargets(rows).isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)) {
        // Already decided: show the decision with a way to see it in Manage registry, instead of the button.
        if (status == DraftStatus.APPROVED) {
            PrimaryButton("Approved · View details", onClick = { onViewDetails(AdminTab.APPROVED) }, icon = R.drawable.ic_check)
        } else {
            PrimaryButton("Approve", onClick = onApprove, enabled = state.draft != null && canApproveNow, icon = R.drawable.ic_check)
        }
        if (status == DraftStatus.BLOCKED) {
            SecondaryButton("Blocked · View details", onClick = { onViewDetails(AdminTab.BLOCKED) }, icon = R.drawable.ic_warning)
        } else {
            SecondaryButton("Block", onClick = onBlock, enabled = state.draft != null, icon = R.drawable.ic_warning)
        }
        if (status == DraftStatus.NEW) approveNote(network, rows)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Slate600) }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Navy900) }
        if (state.draft == null) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
    }
}

/** Why Approve is off, or what it will skip. Null when every radio can be approved. */
private fun approveNote(network: NetworkUi, rows: List<ReviewAp>): String? = when {
    network.ssid == RegistryClassifier.HIDDEN_NAME -> "Hidden networks have no name to register. You can still block them."
    !canApprove(network) -> "${network.security.label} can't be put in the registry. You can still block it."
    rows.any { it.madeUp } -> "Some addresses are made-up, as mesh routers and phone hotspots use. Approve only if you're sure this is your router (check its label)."
    else -> null
}

// ---- Previews: new, connected + approved, made-up only, not found ----

@Composable
private fun PreviewOf(state: AdminScanUiState) = WAVETheme {
    WaveBackground { AdminScanScreen(state, {}, {}, {}) }
}

@Preview(showSystemUi = true, name = "New network")
@Composable
private fun AdminScanNewPreview() = PreviewOf(
    AdminScanUiState.Loaded(FakeFixtures.campus, listOf(FakeFixtures.campus), RegistryDraft.empty(1)),
)

@Preview(showSystemUi = true, name = "Already approved")
@Composable
private fun AdminScanApprovedPreview() = PreviewOf(
    AdminScanUiState.Loaded(
        FakeFixtures.campus,
        listOf(FakeFixtures.campus),
        RegistryDraft.empty(1).approve(approvalsFor(FakeFixtures.campus, reviewAps(listOf(FakeFixtures.campus)) { null }, setOf(FakeFixtures.campus.bssid.lowercase()), "")),
    ),
)

@Preview(showSystemUi = true, name = "Made-up address only")
@Composable
private fun AdminScanMadeUpPreview() = PreviewOf(
    AdminScanUiState.Loaded(FakeFixtures.twin, listOf(FakeFixtures.twin), RegistryDraft.empty(1)),
)

@Preview(showSystemUi = true, name = "Not found")
@Composable
private fun AdminScanNotFoundPreview() = PreviewOf(AdminScanUiState.NotFound)
