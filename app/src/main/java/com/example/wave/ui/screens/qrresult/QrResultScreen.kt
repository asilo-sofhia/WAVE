package com.example.wave.ui.screens.qrresult

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
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
import com.example.wave.ui.components.DetailRow
import com.example.wave.ui.components.FitText
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveScreen
import com.example.wave.ui.components.WaveTextButton
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.components.drawSparkle
import com.example.wave.ui.components.openWifiSettings
import com.example.wave.ui.navigation.Tab
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.BrandIcon
import com.example.wave.ui.theme.Divider
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.NeutralFg
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.UnknownBg
import com.example.wave.ui.theme.UnknownFg
import com.example.wave.ui.theme.VerifiedBg
import com.example.wave.ui.theme.VerifiedFg
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WarningBg
import com.example.wave.ui.theme.WarningFg
import com.example.wave.ui.theme.WaveShapes
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
    onOpenNetwork: (bssid: String) -> Unit = {},
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
        onOpenNetwork = onOpenNetwork,
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

// ---- Presentation ----

/** The hero's overall tone (CLAUDE.md: Verified = green, Warning = red, Unknown = yellow; never color alone). */
private enum class Tone(val fg: Color, val bg: Color, @param:DrawableRes val badge: Int, val badgeLabel: String) {
    VALID(VerifiedFg, VerifiedBg, R.drawable.ic_check, "valid"),
    INVALID(WarningFg, WarningBg, R.drawable.ic_close, "invalid"),
    UNVERIFIED(UnknownFg, UnknownBg, R.drawable.ic_help, "unverified"),
}

/** One line in the result card: value color + icon tint follow its own status. */
private data class ResultRow(@param:DrawableRes val icon: Int, val label: String, val value: String, val fg: Color)

private val QrVerification.registryMatch: Boolean?
    get() = inRegistry ?: network?.let { it.verdict == Verdict.VERIFIED } // fake fixtures carry only the verdict

private fun QrResultUiState.Done.advice(): QrJoinAdvice? = (nearby as? NearbyCheck.Done)?.advice

private fun toneOf(s: QrResultUiState.Done): Tone {
    val v = s.verification
    val advice = s.advice()
    return when {
        advice == QrJoinAdvice.CodeUnsafe || advice is QrJoinAdvice.FakeNearby -> Tone.INVALID
        v.result == QrResult.BAD_SIGNATURE || v.result == QrResult.EXPIRED -> Tone.INVALID
        v.result == QrResult.VALID && v.registryMatch == true -> Tone.VALID
        else -> Tone.UNVERIFIED
    }
}

private fun heroText(s: QrResultUiState.Done, tone: Tone): Pair<String, String> {
    val v = s.verification
    val ssid = v.network?.ssid ?: "this network"
    return when {
        s.advice() is QrJoinAdvice.FakeNearby -> "Fake network nearby" to
            "The access point using $ssid here isn't the real one. Don't connect."
        tone == Tone.VALID -> "Valid Wi-Fi QR Code" to "The QR code is authenticated and matches the trusted registry."
        v.result == QrResult.BAD_SIGNATURE -> "Invalid QR Code" to "Someone changed this code after it was signed. Don't use it."
        v.result == QrResult.EXPIRED -> "Expired QR Code" to "It was valid until ${v.expiresAt}. Ask the ICT office for a new one."
        v.result == QrResult.MALFORMED -> "Not a Wi-Fi QR Code" to "The code doesn't contain any Wi-Fi details."
        else -> "Unverified QR Code" to "The QR code is not recognized or does not match the registry."
    }
}

private fun rowsOf(s: QrResultUiState.Done, tone: Tone): List<ResultRow> {
    val v = s.verification
    // Negative facts take the hero's tone (red when the code is unsafe, yellow when it's just unverified).
    val bad = if (tone == Tone.INVALID) WarningFg else UnknownFg
    val signature = when (v.result) {
        QrResult.VALID -> "Valid" to VerifiedFg
        QrResult.UNSIGNED -> "Not signed" to bad
        QrResult.UNKNOWN_KEY -> "Unknown signer" to bad
        QrResult.BAD_SIGNATURE -> "Not valid" to WarningFg
        QrResult.EXPIRED -> "Expired" to WarningFg
        QrResult.MALFORMED -> "Unreadable" to WarningFg
    }
    val registry = when (v.registryMatch) {
        true -> "Matched" to VerifiedFg
        false -> "Not found" to bad
        null -> "No registry" to NeutralFg
    }
    // Mockup: three rows. What WAVE found nearby shows in the main button and in View Details.
    return listOf(
        ResultRow(R.drawable.ic_wifi, "SSID", v.network?.ssid ?: "—", Navy900),
        ResultRow(R.drawable.ic_shield_outline, "Signature", signature.first, signature.second),
        ResultRow(R.drawable.ic_database, "Registry Match", registry.first, registry.second),
    )
}

/** The nearby check in words, for View Details. Null when the code was never looked up (unsafe code). */
private fun nearbyText(n: NearbyCheck): String? = when (n) {
    NearbyCheck.Checking -> "Checking…"
    NearbyCheck.NoLocation -> "Location off"
    is NearbyCheck.Done -> when (n.advice) {
        QrJoinAdvice.CodeUnsafe -> null
        QrJoinAdvice.NotInRange -> "Not in range"
        is QrJoinAdvice.Connect -> "Verified access point"
        is QrJoinAdvice.FakeNearby -> "Fake copy nearby"
        is QrJoinAdvice.Unverified -> "Not verified"
    }
}

// ---- Screen (layout follows the "QR Result (Verified / Invalid)" mockups: one page, no scrolling) ----

@Composable
fun QrResultScreen(
    state: QrResultUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenTab: (Tab) -> Unit = {},
    onOpenNetwork: (bssid: String) -> Unit = {},
    onScanAgain: () -> Unit = {},
    onOpenWifiSettings: () -> Unit = {},
    /** Join the code's network; the BSSID pins it to the verified access point when a fake shares the name. */
    onConnect: (QrVerification, pinBssid: String?) -> Unit = { _, _ -> },
) {
    WaveScreen(title = "QR Verification Result", onBack = onBack, modifier = modifier, scrollable = false) {
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
            is QrResultUiState.Done -> Done(state, onBack, onOpenTab, onOpenNetwork, onScanAgain, onOpenWifiSettings, onConnect)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.Done(
    state: QrResultUiState.Done,
    onBack: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    onOpenNetwork: (String) -> Unit,
    onScanAgain: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onConnect: (QrVerification, String?) -> Unit,
) {
    val v = state.verification
    val tone = toneOf(state)
    val (title, body) = heroText(state, tone)
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val ssid = v.credentials?.ssid ?: v.network?.ssid ?: "this network"
    // HAPPY for a genuine code that matches the registry (team decision for this screen; the access point is
    // still checked before "Connect"). A fake access point nearby is a network warning (UNCOMFORTABLE);
    // any invalid or unverified code is SAD.
    val mood = when {
        tone == Tone.VALID -> WaveyMood.HAPPY
        state.advice() is QrJoinAdvice.FakeNearby -> WaveyMood.UNCOMFORTABLE
        else -> WaveyMood.SAD
    }

    // The hero takes all the height the rest doesn't need, so Wavey grows on tall phones and the page never scrolls.
    ResultHero(tone, mood, title, body, Modifier.weight(1f))
    ResultCard(rowsOf(state, tone))
    JoinStatusLine(ssid, state.join)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        Buttons(
            state = state,
            tone = tone,
            onConnect = { pin -> onConnect(v, pin) },
            onViewDetails = {
                val fake = (state.advice() as? QrJoinAdvice.FakeNearby)?.accessPoint
                if (fake != null) onOpenNetwork(fake.bssid) else detailsOpen = true
            },
            onScanAnother = onBack,
            onScanAgain = onScanAgain,
            onOpenTab = onOpenTab,
            onOpenWifiSettings = onOpenWifiSettings,
        )
    }
    if (detailsOpen) {
        // A sheet, so the page itself stays one screen.
        ModalBottomSheet(
            onDismissRequest = { detailsOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.xl),
            ) { DetailsCard(state) }
        }
    }
}

/**
 * Tinted card (mockup): big Wavey on the left with sparkles, the QR tile with a ✓ / ✕ / ? badge overlapping on
 * the right, then a big colored title and one line. Wavey scales with the height the card gets.
 */
@Composable
private fun ResultHero(tone: Tone, mood: WaveyMood, title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .shadow(14.dp, WaveShapes.hero, ambientColor = tone.fg.copy(alpha = 0.18f), spotColor = tone.fg.copy(alpha = 0.22f))
            .background(Brush.verticalGradient(listOf(tone.bg, Color.White.copy(alpha = 0.92f))), WaveShapes.hero)
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), WaveShapes.hero)
            .padding(horizontal = Spacing.l, vertical = Spacing.m)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            val wavey = min(maxHeight, maxWidth * 0.66f).coerceAtLeast(96.dp)
            val tile = wavey * 0.46f
            // Wavey and the tile form one group, centred; the tile tucks in against Wavey's right side.
            Box(Modifier.size(width = wavey + tile * 0.78f, height = wavey)) {
                Canvas(Modifier.matchParentSize()) {
                    drawSparkle(Offset(size.width * 0.04f, size.height * 0.40f), 9.dp.toPx(), tone.fg.copy(alpha = 0.8f))
                    drawSparkle(Offset(size.width * 0.16f, size.height * 0.12f), 6.dp.toPx(), tone.fg.copy(alpha = 0.6f))
                    drawSparkle(Offset(size.width * 0.96f, size.height * 0.10f), 5.dp.toPx(), tone.fg.copy(alpha = 0.5f))
                }
                Wavey(mood, size = wavey, modifier = Modifier.align(Alignment.CenterStart))
                QrBadge(tone, tile, Modifier.align(Alignment.CenterEnd).offset(y = -wavey * 0.12f))
            }
        }
        Spacer(Modifier.height(Spacing.xs))
        FitText(
            title,
            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold),
            color = tone.fg,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Navy900, textAlign = TextAlign.Center)
    }
}

/** White rounded QR tile of side [tile] with a round status badge on its lower-right corner. */
@Composable
private fun QrBadge(tone: Tone, tile: Dp, modifier: Modifier = Modifier) {
    val badge = (tile * 0.42f).coerceAtLeast(28.dp)
    Box(modifier.size(tile + badge * 0.35f).semantics { contentDescription = "QR code, ${tone.badgeLabel}" }) {
        Box(
            Modifier
                .size(tile)
                .shadow(8.dp, RoundedCornerShape(tile * 0.24f))
                .background(Color.White, RoundedCornerShape(tile * 0.24f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_qr), null, tint = BrandIcon, modifier = Modifier.size(tile * 0.62f))
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(badge)
                .border(3.dp, Color.White, CircleShape)
                .background(tone.fg, CircleShape)
                .padding(badge * 0.16f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(tone.badge), null, tint = Color.White, modifier = Modifier.fillMaxSize())
        }
    }
}

/** SSID / Signature / Registry Match / Nearby, each with its own colored icon and value. */
@Composable
private fun ResultCard(rows: List<ResultRow>) {
    WaveCard(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(22.dp), padding = 12.dp) {
        rows.forEachIndexed { i, row ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(row.icon),
                    null,
                    tint = if (row.fg == Navy900) BrandIcon else row.fg,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Spacing.m))
                Text(row.label, style = MaterialTheme.typography.bodyLarge, color = Navy900, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.s))
                FitText(
                    row.value,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = row.fg,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
            if (i != rows.lastIndex) HorizontalDivider(color = Divider)
        }
    }
}

/** The extra facts behind "View Details": security, signer, expiry, the nearby access point, raw code. */
@Composable
private fun DetailsCard(s: QrResultUiState.Done) {
    val v = s.verification
    val nearby = s.nearby as? NearbyCheck.Done
    val ap = when (val a = nearby?.advice) {
        is QrJoinAdvice.Connect -> a.accessPoint
        is QrJoinAdvice.Unverified -> a.accessPoint
        is QrJoinAdvice.FakeNearby -> a.accessPoint
        else -> null
    }
    val rows = buildList {
        v.network?.let { add("Security" to it.security.label) }
        add("Signer" to (v.signer ?: "None"))
        add("Valid until" to (v.expiresAt ?: "—"))
        add("Result code" to v.result.name)
        nearbyText(s.nearby)?.let { add("Nearby network" to it) }
        ap?.let {
            add("Access point" to it.bssid)
            add("Signal" to "${it.signal} dBm · ${signalQuality(it.signal).label} · ${it.band.label}")
        }
        if (nearby != null && nearby.accessPoints.size > 1) add("Same name nearby" to "${nearby.accessPoints.size} access points")
        add("Raw code" to v.rawPayload) // the Wi-Fi password is already masked
    }
    WaveCard(Modifier.fillMaxWidth()) {
        Text("Details", style = MaterialTheme.typography.titleMedium, color = Navy900, modifier = Modifier.semantics { heading() })
        rows.forEachIndexed { i, (label, value) ->
            DetailRow(
                label,
                value,
                monospace = label == "Raw code" || label == "Access point",
                stacked = true,
                showDivider = i != rows.lastIndex,
            )
        }
    }
}

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

/** Mockup: one strong button + "Scan Another Code". Which strong button depends on what WAVE found. */
@Composable
private fun Buttons(
    state: QrResultUiState.Done,
    tone: Tone,
    onConnect: (pinBssid: String?) -> Unit,
    onViewDetails: () -> Unit,
    onScanAnother: () -> Unit,
    onScanAgain: () -> Unit,
    onOpenTab: (Tab) -> Unit,
    onOpenWifiSettings: () -> Unit,
) {
    val advice = state.advice()
    when (state.join) {
        JoinStatus.Saved, JoinStatus.Suggested, JoinStatus.AlreadyConnected ->
            PrimaryButton("Open Monitor", onClick = { onOpenTab(Tab.MONITOR) }, icon = R.drawable.ic_monitor_heart)
        JoinStatus.AlreadySaved ->
            PrimaryButton("Open Wi-Fi settings", onClick = onOpenWifiSettings, icon = R.drawable.ic_open_in_new)
        else -> when {
            state.nearby == NearbyCheck.Checking ->
                PrimaryButton("Checking the network nearby…", onClick = {}, loading = true, enabled = false)
            state.nearby == NearbyCheck.NoLocation ->
                PrimaryButton("Allow location", onClick = { onOpenTab(Tab.NETWORKS) }, icon = R.drawable.ic_location)
            advice is QrJoinAdvice.Connect -> PrimaryButton(
                "Connect to Wi-Fi",
                onClick = { onConnect(advice.pinBssid) },
                icon = R.drawable.ic_open_in_new,
                loading = state.join == JoinStatus.Asking,
            )
            advice == QrJoinAdvice.NotInRange && tone != Tone.INVALID ->
                PrimaryButton("Scan again", onClick = onScanAgain, icon = R.drawable.ic_refresh)
            else -> PrimaryButton("View Details", onClick = onViewDetails, containerColor = Navy900)
        }
    }
    SecondaryButton("Scan Another Code", onClick = onScanAnother)
    // An unregistered network nearby can be joined, but never as the highlighted choice.
    val canConnectAnyway = advice is QrJoinAdvice.Unverified &&
        (state.join == JoinStatus.Idle || state.join == JoinStatus.Cancelled || state.join is JoinStatus.Failed)
    if (canConnectAnyway) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            WaveTextButton("Connect anyway (not verified)", onClick = { onConnect(null) })
        }
    }
}

// ---- Previews: verified, warning, unknown, loading (+ nearby outcomes) ----

@Composable
private fun PreviewOf(state: QrResultUiState) = WAVETheme {
    WaveBackground { QrResultScreen(state, {}) }
}

private fun done(result: QrResult, nearby: NearbyCheck) = QrResultUiState.Done(FakeFixtures.qr(result), nearby)

@Preview(showSystemUi = true, name = "Verified (valid, verified AP nearby)")
@Composable
private fun QrValidPreview() = PreviewOf(done(QrResult.VALID, NearbyCheck.Done(QrJoinAdvice.Connect(FakeFixtures.campus, null))))

@Preview(showSystemUi = true, name = "Warning (changed code)")
@Composable
private fun QrChangedPreview() = PreviewOf(done(QrResult.BAD_SIGNATURE, NearbyCheck.Done(QrJoinAdvice.CodeUnsafe)))

@Preview(showSystemUi = true, name = "Warning (fake network nearby)")
@Composable
private fun QrFakeNearbyPreview() = PreviewOf(done(QrResult.VALID, NearbyCheck.Done(QrJoinAdvice.FakeNearby(FakeFixtures.twin))))

@Preview(showSystemUi = true, name = "Warning (expired)")
@Composable
private fun QrExpiredPreview() = PreviewOf(done(QrResult.EXPIRED, NearbyCheck.Done(QrJoinAdvice.CodeUnsafe)))

@Preview(showSystemUi = true, name = "Unknown (unsigned, unverified AP)")
@Composable
private fun QrUnsignedPreview() = PreviewOf(done(QrResult.UNSIGNED, NearbyCheck.Done(QrJoinAdvice.Unverified(FakeFixtures.library))))

@Preview(showSystemUi = true, name = "Unknown (unknown signer, not in range)")
@Composable
private fun QrUnknownKeyPreview() = PreviewOf(done(QrResult.UNKNOWN_KEY, NearbyCheck.Done(QrJoinAdvice.NotInRange)))

@Preview(showSystemUi = true, name = "Unknown (not a Wi-Fi code)")
@Composable
private fun QrMalformedPreview() = PreviewOf(done(QrResult.MALFORMED, NearbyCheck.Done(QrJoinAdvice.CodeUnsafe)))

@Preview(showSystemUi = true, name = "Loading (checking nearby)")
@Composable
private fun QrCheckingPreview() = PreviewOf(done(QrResult.VALID, NearbyCheck.Checking))

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun QrLoadingPreview() = PreviewOf(QrResultUiState.Loading)
