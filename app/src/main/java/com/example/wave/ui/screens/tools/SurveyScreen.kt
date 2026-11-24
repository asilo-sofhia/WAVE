package com.example.wave.ui.screens.tools

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.wave.R
import com.example.wave.data.admin.DraftAp
import com.example.wave.data.admin.DraftSource
import com.example.wave.data.admin.RegistryDraft
import com.example.wave.verify.CertResult
import com.example.wave.data.admin.AdminStatus
import com.example.wave.data.admin.PublishOutcome
import com.example.wave.data.admin.PlacePublisher
import com.example.wave.data.admin.RegistryDraftStore
import com.example.wave.data.admin.RegistryJsonWriter
import com.example.wave.data.exitpath.ExitPathFetcher
import com.example.wave.data.registryOrNull
import com.example.wave.di.AppContainer
import com.example.wave.model.BlockedAp
import com.example.wave.model.CampusAsn
import com.example.wave.model.Registry
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveTopBar
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.VerifiedFg
import com.example.wave.ui.theme.WAVETheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

// Manage registry (after the admin gate): the admin's Approved and Blocked lists. Networks are scanned and decided
// on in the Wi-Fi radar (Admin Scan); this screen only shows, removes and publishes those decisions.

/**
 * The admin's lists plus the campus provider and publishing. Nothing here scans or compares with the registry.
 * Publishing saves registry.json, which is signed on the laptop (the phone never holds the campus key).
 */
class SurveyViewModel(
    private val draftStore: RegistryDraftStore,
    private val currentRegistry: () -> Registry?,
    private val exitPath: ExitPathFetcher?,
    private val publisher: PlacePublisher? = null,
) : ViewModel() {
    val draft: StateFlow<RegistryDraft?> = draftStore.draft

    private val _actions = MutableStateFlow(AdminActionsUi())
    val actions: StateFlow<AdminActionsUi> = _actions.asStateFlow()

    init {
        viewModelScope.launch { draftStore.load(currentRegistry()) }
    }

    /** Edits act on a whole network: every one of its radios. */
    fun removeAll(bssids: List<String>) = viewModelScope.launch { draftStore.update { it.removeAll(bssids) } }

    fun blockAll(bssids: List<String>, ssid: String, reason: String) =
        viewModelScope.launch { draftStore.update { it.blockAll(bssids, ssid, reason) } }

    fun setBuilding(bssids: List<String>, building: String) =
        viewModelScope.launch { draftStore.update { it.withBuilding(bssids, building) } }

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

    /** Saves the lists as registry.json (CONTRACT §2, unsigned) to the file the admin picked. */
    fun publish(resolver: ContentResolver, uri: Uri) = viewModelScope.launch {
        val d = draftStore.draft.value ?: return@launch
        val registry = d.copy(baseVersion = maxOf(d.baseVersion, currentRegistry()?.version ?: 0)).toRegistry(Instant.now())
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

    /**
     * Publish from the phone: [unlock] shows the system prompt (fingerprint / screen lock), then the list is signed
     * with the admin key and uploaded. One more unlock if the first one ran out before signing.
     */
    fun publishToServer(placeName: String, unlock: suspend () -> Boolean) = viewModelScope.launch {
        val p = publisher ?: return@launch
        val d = draftStore.draft.value ?: return@launch
        if (_actions.value.publishing) return@launch
        _actions.value = _actions.value.copy(publishing = true, message = null)
        // Ask the server for the version first (it may need a minute to wake), THEN unlock: the 30 s unlock is
        // spent only on signing and uploading.
        val outcome: PublishOutcome = when (val step = p.prepare(d)) {
            is PublishOutcome -> step
            is PlacePublisher.Prepared -> {
                var result: PublishOutcome = PublishOutcome.NeedsUnlock
                if (unlock()) {
                    result = p.signAndUpload(step)
                    if (result == PublishOutcome.NeedsUnlock && unlock()) result = p.signAndUpload(step)
                }
                result
            }
        }
        val published = outcome as? PublishOutcome.Answered
        if (published?.answer?.result == "STORED") {
            draftStore.update { it.copy(baseVersion = maxOf(it.baseVersion, published.version)) }
        }
        _actions.value = _actions.value.copy(publishing = false, message = publishMessage(outcome, placeName))
    }

    private fun say(text: String) {
        _actions.value = _actions.value.copy(message = text)
    }
}

// ---- Route ----

@Composable
fun SurveyRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenRadar: () -> Unit,
    initialTab: AdminTab = AdminTab.APPROVED,
    onNeedAdmin: () -> Unit = {},
) {
    val admin by container.adminEnrollment.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.adminEnrollment.refresh() } // an ID card can expire while the app is open
    if (!canOpenManageRegistry(admin)) {
        // Every way in (the gate, Admin Scan's "View details") needs a valid ID card in release builds.
        LaunchedEffect(Unit) { onNeedAdmin() }
        return
    }
    val vm = waveViewModel {
        SurveyViewModel(
            container.registryDraft,
            { container.registryRepository.state.value.registryOrNull },
            container.exitPathFetcher,
            container.placePublisher,
        )
    }
    val draft by vm.draft.collectAsStateWithLifecycle()
    val actions by vm.actions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resolver = context.contentResolver
    val placeName = (admin as? AdminStatus.Enrolled)?.takeIf { it.validity == CertResult.VALID }?.cert?.placeName
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.publish(resolver, uri)
    }
    ManageRegistryScreen(
        draft = draft,
        actions = actions,
        onBack = onBack,
        onOpenRadar = onOpenRadar,
        onRemove = { vm.removeAll(it) },
        onBlock = { bssids, ssid, reason -> vm.blockAll(bssids, ssid, reason) },
        onSaveBuilding = { bssids, building -> vm.setBuilding(bssids, building) },
        onDetectProvider = vm::detectProvider,
        onPublish = { saveAs.launch("registry.json") },
        onClear = { vm.clear() },
        initialTab = initialTab,
        adminLabel = admin.adminLabel(),
        publishTo = placeName,
        onPublishToServer = {
            if (placeName != null) vm.publishToServer(placeName) { context.confirmWithScreenLock("Publish Wi-Fi list", placeName) }
        },
    )
}

// ---- Screen ----

@Composable
fun ManageRegistryScreen(
    draft: RegistryDraft?,
    actions: AdminActionsUi,
    onBack: () -> Unit,
    onOpenRadar: () -> Unit,
    onRemove: (bssids: List<String>) -> Unit,
    onDetectProvider: () -> Unit,
    onPublish: () -> Unit,
    onClear: () -> Unit,
    /** "Admin · <place>" when this phone holds a valid ID card. */
    adminLabel: String? = null,
    /** The place to publish to (valid ID card), or null: Publish then saves a file. */
    publishTo: String? = null,
    onPublishToServer: () -> Unit = {},
    modifier: Modifier = Modifier,
    initialTab: AdminTab = AdminTab.APPROVED,
    onBlock: (bssids: List<String>, ssid: String, reason: String) -> Unit = { _, _, _ -> },
    onSaveBuilding: (bssids: List<String>, building: String) -> Unit = { _, _ -> },
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var editing by remember { mutableStateOf<EditingEntry?>(null) }
    val listState = rememberLazyListState()
    // Each tab starts at the top (otherwise the list keeps the other tab's scroll and hides the entries).
    LaunchedEffect(tab) { listState.scrollToItem(0) }

    Column(modifier.fillMaxSize()) {
        WaveTopBar(title = "Manage registry", onBack = onBack)
        if (adminLabel != null) {
            Text(
                adminLabel,
                style = MaterialTheme.typography.labelLarge,
                color = VerifiedFg,
                modifier = Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.s),
            )
        }
        if (draft == null) {
            Box(Modifier.fillMaxWidth().padding(top = Spacing.xxl), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = Spacing.gutter),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(Spacing.s),
            contentPadding = PaddingValues(bottom = Spacing.xl),
        ) {
            item(key = "tabs") {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    FilterChip(tab == AdminTab.APPROVED, onClick = { tab = AdminTab.APPROVED }, label = { Text("Approved (${draft.approved.size})") })
                    FilterChip(tab == AdminTab.BLOCKED, onClick = { tab = AdminTab.BLOCKED }, label = { Text("Blocked (${draft.blocked.size})") })
                }
            }
            when (tab) {
                AdminTab.APPROVED -> {
                    val groups = approvedGroups(draft)
                    if (groups.isEmpty()) item(key = "empty-a") { EmptyNote("Nothing approved yet. Open the Wi-Fi radar, tap Scan on a network, then Approve.") }
                    items(groups, key = { "a-" + it.ssid + "|" + it.security }) { g ->
                        NetworkEntryRow(
                            title = g.ssid,
                            detail = listOfNotNull(
                                g.security.label,
                                apCount(g.aps.size),
                                g.aps.firstOrNull()?.building?.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            status = DraftStatus.APPROVED,
                            onEdit = { editing = EditingEntry.Approved(g) },
                        )
                    }
                }
                AdminTab.BLOCKED -> {
                    val groups = blockedGroups(draft)
                    if (groups.isEmpty()) item(key = "empty-b") { EmptyNote("Nothing blocked yet. Open the Wi-Fi radar, tap Scan on a network, then Block.") }
                    items(groups, key = { "b-" + it.ssid }) { g ->
                        NetworkEntryRow(
                            title = g.ssid.ifEmpty { "Hidden network" },
                            detail = "${apCount(g.bssids.size)} · ${g.reason}",
                            status = DraftStatus.BLOCKED,
                            onEdit = { editing = EditingEntry.Blocked(g) },
                        )
                    }
                }
            }
            item(key = "add") {
                SecondaryButton("Add from Wi-Fi radar", onClick = onOpenRadar, icon = R.drawable.ic_radar, modifier = Modifier.padding(top = Spacing.s))
            }
            item(key = "actions") { AdminActionsCard(draft, actions, onDetectProvider, onPublish, onClear, publishTo, onPublishToServer) }
        }
    }
    editing?.let { entry ->
        EditEntrySheet(
            entry = entry,
            onDismiss = { editing = null },
            onSaveBuilding = { bssids, building -> onSaveBuilding(bssids, building); editing = null },
            onBlock = { bssids, ssid, reason -> onBlock(bssids, ssid, reason); editing = null },
            onRemove = { bssids -> onRemove(bssids); editing = null },
        )
    }
}

private fun apCount(n: Int) = if (n == 1) "1 access point" else "$n access points"

// ---- Previews: approved, blocked, empty, loading ----

@Composable
private fun PreviewOf(
    draft: RegistryDraft?,
    tab: AdminTab = AdminTab.APPROVED,
    actions: AdminActionsUi = AdminActionsUi(),
    publishTo: String? = null,
) = WAVETheme {
    WaveBackground {
        ManageRegistryScreen(
            draft, actions, {}, {}, {}, {}, {}, {}, initialTab = tab,
            adminLabel = publishTo?.let { "Admin · $it" }, publishTo = publishTo,
        )
    }
}

private val previewDraft = RegistryDraft(
    baseVersion = 1,
    campusAsns = listOf(CampusAsn(9299, "PLDT")),
    approved = listOf(
        DraftAp("00:1a:2b:00:00:01", "BATSTATEU-ICT", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_5, 36, "Library", -85, -30, DraftSource.SURVEYED),
        DraftAp("00:1a:2b:00:00:02", "BATSTATEU-ICT", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_2_4, 6, "Library", -85, -30, DraftSource.SURVEYED),
    ),
    blocked = listOf(BlockedAp("6a:3f:00:11:22:9c", "BSU Free WiFi", "Blocked on the admin scan")),
)

@Preview(showSystemUi = true, name = "Approved")
@Composable
private fun ManageApprovedPreview() = PreviewOf(previewDraft)

@Preview(showSystemUi = true, name = "Blocked")
@Composable
private fun ManageBlockedPreview() = PreviewOf(previewDraft, AdminTab.BLOCKED)

@Preview(showSystemUi = true, name = "Empty")
@Composable
private fun ManageEmptyPreview() = PreviewOf(RegistryDraft.empty(1))

@Preview(showSystemUi = true, name = "Admin: publish to place", heightDp = 1600)
@Composable
private fun ManageAdminPreview() = PreviewOf(previewDraft, publishTo = "WAVE Test")

@Preview(showSystemUi = true, name = "Admin: publishing", heightDp = 1600)
@Composable
private fun ManagePublishingPreview() = PreviewOf(previewDraft, actions = AdminActionsUi(publishing = true), publishTo = "WAVE Test")

@Preview(showSystemUi = true, name = "Admin: published", heightDp = 1600)
@Composable
private fun ManagePublishedPreview() = PreviewOf(
    previewDraft,
    actions = AdminActionsUi(message = "Published version 2 to WAVE Test. Phones get it on their next update."),
    publishTo = "WAVE Test",
)

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun ManageLoadingPreview() = PreviewOf(null)
