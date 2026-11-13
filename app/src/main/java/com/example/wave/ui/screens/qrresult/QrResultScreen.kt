package com.example.wave.ui.screens.qrresult

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
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
import com.example.wave.data.QrVerifier
import com.example.wave.data.ScanRepository
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.data.wifi.WifiJoiner
import com.example.wave.di.AppContainer
import com.example.wave.domain.QrJoinAdvice
import com.example.wave.model.NetworkUi
import com.example.wave.model.QrResult
import com.example.wave.model.QrVerification
import com.example.wave.model.Verdict
import com.example.wave.model.WaveyMood
import com.example.wave.model.signalQuality
import com.example.wave.model.verdict
import com.example.wave.model.waveyMoodFor
import com.example.wave.ui.components.DetailRow
import com.example.wave.ui.components.HeroCard
import com.example.wave.ui.components.IconTile
import com.example.wave.ui.components.NotCheckedStyle
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.StatusPill
import com.example.wave.ui.components.TechDetailsToggle
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveScreen
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.components.openWifiSettings
import com.example.wave.ui.components.style
import com.example.wave.ui.navigation.Tab
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WarningFg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ---- State ----

/** What WAVE found when it looked for the code's network nearby. */
sealed interface NearbyCheck {
    data object Checking : NearbyCheck
    data object NoLocation : NearbyCheck

    /** [accessPoints]: everything broadcasting the code's network name, judged against the registry. */
    data class Done(val advice: QrJoinAdvice, val accessPoints: List<NetworkUi> = emptyList()) : NearbyCheck
}

/** Progress of joining the network after "Connect". */
sealed interface JoinStatus {
    data object Idle : JoinStatus
    data object Asking : JoinStatus
    data object Saved : JoinStatus
    data object AlreadySaved : JoinStatus
    data object AlreadyConnected : JoinStatus
    data object Cancelled : JoinStatus
    data object Suggested : JoinStatus
    data class Failed(val message: String) : JoinStatus
}

sealed interface QrResultUiState {
    data object Loading : QrResultUiState
    data class Done(
        val verification: QrVerification,
        val nearby: NearbyCheck = NearbyCheck.Checking,
        val join: JoinStatus = JoinStatus.Idle,
    ) : QrResultUiState
}

/**
 * 1. Check the code (signature, expiry). 2. Look for its network nearby and judge each access point.
 * 3. Only then offer to join: see [QrJoinAdvice].
 */
class QrResultViewModel(
    private val verifier: QrVerifier,
    private val scans: ScanRepository,
    private val location: LocationPermissionGate,
    private val payload: String,
) : ViewModel() {
    private val _state = MutableStateFlow<QrResultUiState>(QrResultUiState.Loading)
    val state: StateFlow<QrResultUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.value = QrResultUiState.Done(verifier.verify(payload))
            checkNearby()
        }
    }

    /** Scan for the code's network (also "Scan again"). */
    fun checkNearby() {
        val done = _state.value as? QrResultUiState.Done ?: return
        val v = done.verification
        val ssid = v.credentials?.ssid ?: v.network?.ssid
        if (QrJoinAdvice.of(v.result, ssid, emptyList()) == QrJoinAdvice.CodeUnsafe) {
            setNearby(NearbyCheck.Done(QrJoinAdvice.CodeUnsafe))
            return
        }
        if (!location.granted.value) {
            setNearby(NearbyCheck.NoLocation)
            return
        }
        viewModelScope.launch {
            setNearby(NearbyCheck.Checking)
            val named = scans.scanNearby().networks.filter { it.ssid == ssid }
            setNearby(NearbyCheck.Done(QrJoinAdvice.of(v.result, ssid, named), named))
        }
    }

    /** True when the phone is already on the code's network (no need to ask Android to save it). */
    fun alreadyOn(ssid: String?): Boolean = ssid != null && scans.connection.value?.ssid == ssid

    fun setJoin(status: JoinStatus) =
        _state.update { (it as? QrResultUiState.Done)?.copy(join = status) ?: it }

    private fun setNearby(check: NearbyCheck) =
        _state.update { (it as? QrResultUiState.Done)?.copy(nearby = check) ?: it }
}

// ---- Route ----

@Composable
fun QrResultRoute(
    container: AppContainer,
    payload: String,
    onBack: () -> Unit,
    onOpenTab: (Tab) -> Unit,
) {
    val vm = waveViewModel {
        QrResultViewModel(container.qrVerifier, container.scanRepository, container.locationPermission, payload)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val joiner = container.wifiJoiner

    // Android's "Save this network?" sheet (Android 11+).
    val saveSheet = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vm.setJoin(
            when (joiner?.resultOf(r.resultCode, r.data)) {
                WifiJoiner.Outcome.SAVED -> JoinStatus.Saved
                WifiJoiner.Outcome.ALREADY_SAVED -> JoinStatus.AlreadySaved
                WifiJoiner.Outcome.CANCELLED -> JoinStatus.Cancelled
                else -> JoinStatus.Failed("Android couldn't save this network.")
            },
        )
    }

    QrResultScreen(
        state = state,
        onBack = onBack,
        onOpenTab = onOpenTab,
        onScanAgain = vm::checkNearby,
        onOpenWifiSettings = { openWifiSettings(context) },
        onConnect = { v, pinBssid ->
            val credentials = v.credentials
            if (vm.alreadyOn(credentials?.ssid ?: v.network?.ssid)) {
                vm.setJoin(JoinStatus.AlreadyConnected)
            } else if (joiner == null || credentials == null) {
                openWifiSettings(context) // demo mode never joins real networks
            } else {
                when (val prepared = joiner.prepare(credentials, pinBssid)) {
                    is WifiJoiner.Prepared.SaveSheet -> try {
                        vm.setJoin(JoinStatus.Asking)
                        saveSheet.launch(prepared.intent)
                    } catch (_: ActivityNotFoundException) {
                        vm.setJoin(JoinStatus.Failed("This phone can't add networks from apps. Use Wi-Fi settings."))
                    }
                    is WifiJoiner.Prepared.Suggested -> vm.setJoin(
                        if (prepared.ok) JoinStatus.Suggested else JoinStatus.Failed("Android didn't accept the network."),
                    )
                    is WifiJoiner.Prepared.Unsupported -> vm.setJoin(JoinStatus.Failed(prepared.reason))
                }
            }
        },
    )
}

// ---- Screen ----

/**
 * CONTRACT §3: a VALID signature for a network that isn't on the registry is Unknown, not Verified.
 * (Fake fixtures always carry a registered network, so demo results are unchanged.)
 */
private val QrVerification.signedButUnregistered: Boolean
    get() = result == QrResult.VALID && network != null && network.verdict != Verdict.VERIFIED

private val QrVerification.shownVerdict: Verdict
    get() = if (signedButUnregistered) Verdict.UNKNOWN else result.verdict

private data class QrCopy(val chip: String, val title: String, val body: String)

private fun copyFor(v: QrVerification): QrCopy {
    val ssid = v.network?.ssid ?: "this network"
    return when (v.result) {
        QrResult.VALID -> if (v.signedButUnregistered) {
            QrCopy("Not registered", "Signed by ${v.signer}", "The code is genuine, but $ssid isn't on the registry.")
        } else {
            QrCopy("Valid", "Signed by ${v.signer}", "This QR code is genuine and hasn't been changed.")
        }
        QrResult.BAD_SIGNATURE -> QrCopy(
            "Changed", "This QR code was changed",
            "Someone edited it after it was signed. It may send you to a fake network. Don't use it.",
        )
        QrResult.EXPIRED -> QrCopy(
            "Expired", "This QR code has expired",
            "It was valid until ${v.expiresAt}. Ask the ICT office for a new one.",
        )
        QrResult.UNSIGNED -> QrCopy(
            "Unsigned", "This QR code isn't signed",
            "Anyone can make a code like this, so WAVE checks the network itself instead.",
        )
        QrResult.UNKNOWN_KEY -> QrCopy(
            "Unknown signer", "Signed by someone WAVE doesn't know",
            "The signature isn't from a trusted issuer, so WAVE checks the network itself instead.",
        )
        QrResult.MALFORMED -> QrCopy("Not Wi-Fi", "This isn't a Wi-Fi QR code", "The code doesn't contain any Wi-Fi details.")
    }
}

@Composable
fun QrResultScreen(
    state: QrResultUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTab: (Tab) -> Unit = {},
    onScanAgain: () -> Unit = {},
    onOpenWifiSettings: () -> Unit = {},
    /** Join the code's network; the BSSID pins it to the verified access point when a fake shares the name. */
    onConnect: (QrVerification, pinBssid: String?) -> Unit = { _, _ -> },
) {
    WaveScreen(title = "QR result", onBack = onBack, modifier = modifier) {
        when (state) {
            QrResultUiState.Loading -> Column(
                Modifier.fillMaxWidth().padding(top = Spacing.xxl).semantics { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.l),
            ) {
                Wavey(WaveyMood.CURIOUS, size = 150.dp)
                Text("Checking the QR code's signature…", style = MaterialTheme.typography.titleMedium, color = Navy900)
                CircularProgressIndicator()
            }
            is QrResultUiState.Done -> Done(state, onBack, onOpenTab, onScanAgain, onOpenWifiSettings, onConnect)
        }
    }
}

@Composable
private fun Done(
    state: QrResultUiState.Done,
    onBack: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    onScanAgain: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onConnect: (QrVerification, String?) -> Unit,
) {
    val v = state.verification
    val copy = copyFor(v)
    val verdict = v.shownVerdict
    val ssid = v.credentials?.ssid ?: v.network?.ssid ?: "this network"

    HeroCard(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Wavey(if (v.signedButUnregistered) WaveyMood.CURIOUS else waveyMoodFor(v.result), size = 150.dp)
            Spacer(Modifier.height(Spacing.s))
            StatusPill(verdict.style(), label = copy.chip)
            Spacer(Modifier.height(Spacing.m))
            Text(
                copy.title,
                style = MaterialTheme.typography.titleLarge,
                color = if (verdict == Verdict.WARNING) WarningFg else Navy900,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(copy.body, style = MaterialTheme.typography.bodyLarge, color = Slate600, textAlign = TextAlign.Center)
        }
    }

    NearbyCard(ssid, state.nearby)

    v.network?.let { network ->
        WaveCard(Modifier.fillMaxWidth()) {
            Text("What the code says", style = MaterialTheme.typography.titleMedium, color = Navy900)
            DetailRow("Network", network.ssid)
            DetailRow("Security", network.security.label, showDivider = false)
        }
    }

    JoinStatusLine(ssid, state.join)
    Actions(state, ssid, onBack, onOpenTab, onScanAgain, onOpenWifiSettings, onConnect)

    TechDetailsToggle(
        buildList {
            add("Result code" to v.result.name)
            add("Signer" to (v.signer ?: "None"))
            add("Expires" to (v.expiresAt ?: "-"))
            add("Raw payload" to v.rawPayload)
        },
    )
}

/** "Is this network nearby?": the access points broadcasting the code's name, and what WAVE makes of them. */
@Composable
private fun NearbyCard(ssid: String, nearby: NearbyCheck) {
    val (style, title, body) = when (nearby) {
        NearbyCheck.Checking -> Triple(NotCheckedStyle.copy(label = "Checking"), "Looking for $ssid nearby…", null)
        NearbyCheck.NoLocation -> Triple(
            NotCheckedStyle.copy(label = "Location off"), "Can't look for $ssid",
            "Android needs location access to list nearby Wi-Fi.",
        )
        is NearbyCheck.Done -> when (val a = nearby.advice) {
            QrJoinAdvice.CodeUnsafe -> return // the code itself is the problem; nothing to look for
            QrJoinAdvice.NotInRange -> Triple(
                NotCheckedStyle.copy(label = "Not found"), "$ssid isn't in range",
                "Move closer to the network and scan again.",
            )
            is QrJoinAdvice.Connect -> Triple(
                Verdict.VERIFIED.style(), "Verified access point nearby",
                apLine(a.accessPoint) + if (a.pinBssid != null) {
                    "\nA fake copy is also nearby. WAVE will connect only to the verified one."
                } else {
                    ""
                },
            )
            is QrJoinAdvice.FakeNearby -> Triple(
                Verdict.WARNING.style(), "Only a fake copy is nearby",
                "The access point using this name isn't the real one" +
                    (a.accessPoint.reasons.firstOrNull()?.let { ": ${it.title.lowercase()}." } ?: ".") +
                    " Don't connect.",
            )
            is QrJoinAdvice.Unverified -> Triple(
                Verdict.UNKNOWN.style(), "Can't verify this network",
                "$ssid is nearby but isn't on the registry. If you join, avoid logging in to important accounts.\n" +
                    apLine(a.accessPoint),
            )
        }
    }
    WaveCard(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Text("Nearby", style = MaterialTheme.typography.titleMedium, color = Navy900, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(Spacing.s))
        Row(verticalAlignment = Alignment.Top) {
            if (nearby == NearbyCheck.Checking) {
                CircularProgressIndicator(Modifier.size(36.dp).padding(6.dp), strokeWidth = 3.dp)
            } else {
                IconTile(style.icon, tint = style.fg, background = style.bg, size = 36.dp)
            }
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Navy900)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = Slate600)
                if (nearby is NearbyCheck.Done && nearby.accessPoints.size > 1) {
                    Text(
                        "${nearby.accessPoints.size} access points use this name.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate600,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.s))
            StatusPill(style, compact = true)
        }
    }
}

private fun apLine(ap: NetworkUi) = "${ap.band.label} · ${ap.signal} dBm (${signalQuality(ap.signal).label}) · ${ap.bssid}"

@Composable
private fun JoinStatusLine(ssid: String, join: JoinStatus) {
    val text = when (join) {
        JoinStatus.Idle -> return
        JoinStatus.Asking -> "Android is asking to save $ssid…"
        JoinStatus.Saved -> "Saved. Your phone will connect to $ssid."
        JoinStatus.AlreadySaved -> "$ssid is already saved on this phone. Open Wi-Fi settings to connect."
        JoinStatus.AlreadyConnected -> "You're already connected to $ssid. Monitor shows how it checks out."
        JoinStatus.Cancelled -> "Not saved. Tap Connect to try again."
        JoinStatus.Suggested -> "Android will show a notification: allow it to connect to $ssid."
        is JoinStatus.Failed -> join.message
    }
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (join is JoinStatus.Failed) WarningFg else Navy900,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** One clear next step for each situation (see [QrJoinAdvice]). */
@Composable
private fun Actions(
    state: QrResultUiState.Done,
    ssid: String,
    onBack: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    onScanAgain: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onConnect: (QrVerification, String?) -> Unit,
) {
    val v = state.verification
    when (state.join) {
        JoinStatus.Saved, JoinStatus.Suggested, JoinStatus.AlreadyConnected -> {
            PrimaryButton("Open Monitor", onClick = { onOpenTab(Tab.MONITOR) }, icon = R.drawable.ic_monitor_heart)
            return
        }
        JoinStatus.AlreadySaved -> {
            PrimaryButton("Open Wi-Fi settings", onClick = onOpenWifiSettings, icon = R.drawable.ic_wifi)
            return
        }
        else -> Unit
    }
    when (val nearby = state.nearby) {
        NearbyCheck.Checking -> PrimaryButton("Checking nearby…", onClick = {}, loading = true, enabled = false)
        NearbyCheck.NoLocation -> PrimaryButton("Allow location", onClick = { onOpenTab(Tab.NETWORKS) }, icon = R.drawable.ic_location)
        is NearbyCheck.Done -> when (val a = nearby.advice) {
            QrJoinAdvice.CodeUnsafe -> PrimaryButton("Scan another code", onClick = onBack, icon = R.drawable.ic_qr)
            QrJoinAdvice.NotInRange -> {
                PrimaryButton("Scan again", onClick = onScanAgain, icon = R.drawable.ic_refresh)
                SecondaryButton("Scan another code", onClick = onBack)
            }
            is QrJoinAdvice.Connect -> PrimaryButton(
                "Connect to $ssid",
                onClick = { onConnect(v, a.pinBssid) },
                icon = R.drawable.ic_wifi,
                loading = state.join == JoinStatus.Asking,
            )
            is QrJoinAdvice.FakeNearby ->
                PrimaryButton("Check nearby networks", onClick = { onOpenTab(Tab.NETWORKS) }, icon = R.drawable.ic_wifi)
            is QrJoinAdvice.Unverified -> {
                PrimaryButton("Check nearby networks", onClick = { onOpenTab(Tab.NETWORKS) }, icon = R.drawable.ic_wifi)
                SecondaryButton("Connect anyway", onClick = { onConnect(v, null) })
            }
        }
    }
}

// ---- Previews: verified, warning, unknown, loading (+ each QR result and nearby outcome) ----

@Composable
private fun PreviewOf(state: QrResultUiState) = WAVETheme {
    WaveBackground { QrResultScreen(state, {}) }
}

private fun done(result: QrResult, nearby: NearbyCheck) = QrResultUiState.Done(FakeFixtures.qr(result), nearby)

@Preview(showSystemUi = true, name = "Verified (VALID, verified AP nearby)")
@Composable
private fun QrValidPreview() = PreviewOf(done(QrResult.VALID, NearbyCheck.Done(QrJoinAdvice.Connect(FakeFixtures.campus, null))))

@Preview(showSystemUi = true, name = "Verified + fake nearby (pinned)")
@Composable
private fun QrValidTwinPreview() = PreviewOf(
    done(QrResult.VALID, NearbyCheck.Done(QrJoinAdvice.Connect(FakeFixtures.campus, FakeFixtures.campus.bssid))),
)

@Preview(showSystemUi = true, name = "Warning (only a fake nearby)")
@Composable
private fun QrFakeNearbyPreview() = PreviewOf(done(QrResult.UNSIGNED, NearbyCheck.Done(QrJoinAdvice.FakeNearby(FakeFixtures.twin))))

@Preview(showSystemUi = true, name = "Warning (BAD_SIGNATURE)")
@Composable
private fun QrChangedPreview() = PreviewOf(done(QrResult.BAD_SIGNATURE, NearbyCheck.Done(QrJoinAdvice.CodeUnsafe)))

@Preview(showSystemUi = true, name = "Warning (EXPIRED)")
@Composable
private fun QrExpiredPreview() = PreviewOf(done(QrResult.EXPIRED, NearbyCheck.Done(QrJoinAdvice.CodeUnsafe)))

@Preview(showSystemUi = true, name = "Unknown (UNSIGNED, unverified AP)")
@Composable
private fun QrUnsignedPreview() = PreviewOf(done(QrResult.UNSIGNED, NearbyCheck.Done(QrJoinAdvice.Unverified(FakeFixtures.library))))

@Preview(showSystemUi = true, name = "Unknown (UNKNOWN_KEY, not in range)")
@Composable
private fun QrUnknownKeyPreview() = PreviewOf(done(QrResult.UNKNOWN_KEY, NearbyCheck.Done(QrJoinAdvice.NotInRange)))

@Preview(showSystemUi = true, name = "Unknown (MALFORMED)")
@Composable
private fun QrMalformedPreview() = PreviewOf(done(QrResult.MALFORMED, NearbyCheck.Done(QrJoinAdvice.CodeUnsafe)))

@Preview(showSystemUi = true, name = "Checking nearby")
@Composable
private fun QrCheckingPreview() = PreviewOf(done(QrResult.VALID, NearbyCheck.Checking))

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun QrLoadingPreview() = PreviewOf(QrResultUiState.Loading)
