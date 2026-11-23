package com.example.wave.ui.screens.tools

import com.example.wave.R
import com.example.wave.data.admin.DraftAp
import com.example.wave.data.admin.DraftSource
import com.example.wave.data.admin.RegistryDraft
import com.example.wave.data.admin.surveyedRange
import com.example.wave.domain.RegistryClassifier
import com.example.wave.model.NetworkUi
import com.example.wave.model.SecurityMapping
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import com.example.wave.model.isLocallyAdministered
import com.example.wave.ui.components.StatusStyle
import com.example.wave.ui.theme.UnknownBg
import com.example.wave.ui.theme.UnknownFg
import com.example.wave.ui.theme.VerifiedBg
import com.example.wave.ui.theme.VerifiedFg
import com.example.wave.ui.theme.WarningBg
import com.example.wave.ui.theme.WarningFg
import java.util.Locale

// Pure helpers behind the admin screens (survey Review sheet and Manage registry), unit-tested.

/** Where a scanned network stands in the admin's list. */
enum class DraftStatus { NEW, APPROVED, BLOCKED }

fun draftStatus(draft: RegistryDraft?, bssids: List<String>): DraftStatus {
    if (draft == null) return DraftStatus.NEW
    val keys = bssids.map { it.lowercase(Locale.ROOT) }
    if (draft.blocked.any { it.bssid in keys }) return DraftStatus.BLOCKED
    return if (draft.approved.any { it.bssid in keys }) DraftStatus.APPROVED else DraftStatus.NEW
}

fun DraftStatus.style(): StatusStyle = when (this) {
    DraftStatus.NEW -> StatusStyle(UnknownFg, UnknownBg, R.drawable.ic_help, "New")
    DraftStatus.APPROVED -> StatusStyle(VerifiedFg, VerifiedBg, R.drawable.ic_check, "Approved")
    DraftStatus.BLOCKED -> StatusStyle(WarningFg, WarningBg, R.drawable.ic_warning, "Blocked")
}

/** One radio of the network under review. */
data class ReviewAp(
    val bssid: String,
    val band: WifiBand,
    val channel: Int,
    val signal: Int,
    /** Weakest..strongest seen this session, or null if only seen once so far. */
    val observed: IntRange?,
    /** "Made-up" (locally administered) address: typical of hotspots and fake routers. */
    val madeUp: Boolean,
    /** Ticked by default: every radio except made-up addresses. */
    val preselected: Boolean,
)

/** The network's radios, strongest first, with lowercase addresses (the registry's format). */
fun reviewAps(aps: List<NetworkUi>, observedRange: (bssid: String) -> IntRange?): List<ReviewAp> =
    aps.sortedByDescending { it.signal }.map { n ->
        val bssid = n.bssid.lowercase(Locale.ROOT)
        val madeUp = isLocallyAdministered(bssid)
        ReviewAp(
            bssid = bssid,
            band = n.band,
            channel = n.metrics?.channel ?: defaultChannel(n.band),
            signal = n.signal,
            observed = observedRange(bssid),
            madeUp = madeUp,
            preselected = !madeUp,
        )
    }

/** Hidden networks have no name to register, and WEP / unsupported security has no registry value. */
fun canApprove(network: NetworkUi): Boolean =
    network.ssid != RegistryClassifier.HIDDEN_NAME && SecurityMapping.toContract(network.security) != null

/** The draft entries for the ticked radios, with a widened signal range (see [surveyedRange]). */
fun approvalsFor(network: NetworkUi, aps: List<ReviewAp>, selected: Set<String>, building: String): List<DraftAp> =
    aps.filter { it.bssid in selected }.map { a ->
        val range = surveyedRange(a.observed?.first ?: a.signal, a.observed?.last ?: a.signal)
        DraftAp(
            bssid = a.bssid,
            ssid = network.ssid,
            security = network.security,
            band = a.band,
            channel = a.channel,
            building = building.trim(),
            rssiMin = range.first,
            rssiMax = range.last,
            source = DraftSource.SURVEYED,
        )
    }

/** Admin Scan: an approved or blocked network offers "View details", which opens this tab of Manage registry. */
fun viewDetailsTab(status: DraftStatus): AdminTab? = when (status) {
    DraftStatus.APPROVED -> AdminTab.APPROVED
    DraftStatus.BLOCKED -> AdminTab.BLOCKED
    DraftStatus.NEW -> null
}

/** Admin Scan's Approve: every radio except made-up addresses (WAVE warns about those even when registered). */
fun adminApproveTargets(aps: List<ReviewAp>): Set<String> = aps.filter { it.preselected }.map { it.bssid }.toSet()

/** Admin Scan's Block: every radio of the network. */
fun adminBlockTargets(aps: List<ReviewAp>): Set<String> = aps.map { it.bssid }.toSet()

/** One approved network on Manage registry: its radios grouped (same name and security). */
data class ApprovedGroup(val ssid: String, val security: SecurityType, val aps: List<DraftAp>) {
    val bssids: List<String> get() = aps.map { it.bssid }
}

/** One blocked network on Manage registry: its radios grouped by the name they used. */
data class BlockedGroup(val ssid: String, val bssids: List<String>, val reason: String)

fun approvedGroups(draft: RegistryDraft): List<ApprovedGroup> =
    draft.approved.groupBy { it.ssid to it.security }
        .map { (key, aps) -> ApprovedGroup(key.first, key.second, aps.sortedBy { it.bssid }) }
        .sortedWith(compareBy({ it.ssid }, { it.security.name }))

fun blockedGroups(draft: RegistryDraft): List<BlockedGroup> =
    draft.blocked.groupBy { it.ssid }
        .map { (ssid, entries) ->
            val reasons = entries.map { it.reason }.distinct()
            BlockedGroup(ssid, entries.map { it.bssid }.sorted(), if (reasons.size == 1) reasons.single() else "Several reasons")
        }
        .sortedBy { it.ssid }

/** What the Manage registry screen shows at the top. */
data class DraftSummary(
    val approvedCount: Int,
    val blockedCount: Int,
    /** Distinct (name, security) networks the approved access points belong to. */
    val networkCount: Int,
    val nextVersion: Int,
    /** The signing tool refuses a registry without networks. */
    val canExport: Boolean,
    /** No real campus provider yet (empty, or only the ASN 0 placeholder). */
    val campusAsnMissing: Boolean,
    val isEmpty: Boolean,
)

fun summarize(draft: RegistryDraft): DraftSummary = DraftSummary(
    approvedCount = draft.approved.size,
    blockedCount = draft.blocked.size,
    networkCount = draft.approved.map { it.ssid to it.security }.distinct().size,
    nextVersion = draft.baseVersion + 1,
    canExport = draft.approved.isNotEmpty(),
    campusAsnMissing = draft.campusAsns.none { it.asn != 0 },
    isEmpty = draft.approved.isEmpty() && draft.blocked.isEmpty(),
)

/** Channel is informational in the registry; used only when the scan didn't report one. */
private fun defaultChannel(band: WifiBand): Int = when (band) {
    WifiBand.GHZ_2_4 -> 1
    WifiBand.GHZ_5 -> 36
    WifiBand.GHZ_6 -> 1
}
