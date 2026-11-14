package com.example.wave.ui.screens.monitor

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.wave.R
import com.example.wave.data.ExitPathChecker
import com.example.wave.data.ScanRepository
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.di.AppContainer
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.NetworkUi
import com.example.wave.model.Verdict
import com.example.wave.model.WarningReason
import com.example.wave.model.WaveyMood
import com.example.wave.model.signalQuality
import com.example.wave.model.waveyMoodFor
import com.example.wave.model.withExitPath
import com.example.wave.ui.components.FitText
import com.example.wave.ui.components.HeroCard
import com.example.wave.ui.components.NotCheckedStyle
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.StatusStyle
import com.example.wave.ui.components.ToolRow
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveTopBar
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.components.drawSparkle
import com.example.wave.ui.components.style
import com.example.wave.ui.navigation.Tab
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Danger
import com.example.wave.ui.theme.Divider
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.NeutralFg
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.VerifiedBg
import com.example.wave.ui.theme.VerifiedFg
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WarningFg
import com.example.wave.ui.theme.WaveShapes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// ---- State ----

sealed interface MonitorUiState {
    data object Loading : MonitorUiState
    data object NotConnected : MonitorUiState

    /** [exitPath] is null when skipped because the network is already a WARNING. */
    data class Connected(val network: NetworkUi, val exitPath: ExitPathStatus?) : MonitorUiState {
        val effective: NetworkUi get() = network.withExitPath(exitPath)
        val unsafe: Boolean get() = effective.verdict == Verdict.WARNING
        val mood: WaveyMood get() = waveyMoodFor(effective.verdict, exitPath, connected = true)
    }
}

/**
 * The connection updates live (signal, link speed...). The exit-path check runs once per connection
 * (same access point + same verdict), so a signal change never restarts it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorViewModel(repo: ScanRepository, exitPath: ExitPathChecker) : ViewModel() {
    /** (access point the result belongs to, result). Null result = skipped (already a WARNING). */
    private val exit = repo.connection
        .distinctUntilChangedBy { it?.bssid to it?.verdict }
        .flatMapLatest { network ->
            when {
                network == null -> flowOf(null to null)
                network.verdict == Verdict.WARNING -> flowOf(network.bssid to null)
                else -> exitPath.check(network).map { network.bssid to it }
            }
        }

    val state: StateFlow<MonitorUiState> = combine(repo.connection, exit) { network, (checkedBssid, status) ->
        when {
            network == null -> MonitorUiState.NotConnected
            network.verdict == Verdict.WARNING -> MonitorUiState.Connected(network, null)
            // A different access point than the last result: its own check has just started.
            checkedBssid != network.bssid -> MonitorUiState.Connected(network, ExitPathStatus.PENDING)
            else -> MonitorUiState.Connected(network, status)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonitorUiState.Loading)
}

// ---- Route ----

@Composable
fun MonitorRoute(
    container: AppContainer,
    onDisconnectNow: (List<WarningReason>) -> Unit,
    onOpenTab: (Tab) -> Unit,
    onOpenNetwork: (bssid: String) -> Unit,
) {
    val vm = waveViewModel { MonitorViewModel(container.scanRepository, container.exitPathChecker) }
    val state by vm.state.collectAsStateWithLifecycle()
    MonitorScreen(state = state, onDisconnectNow = onDisconnectNow, onOpenTab = onOpenTab, onOpenNetwork = onOpenNetwork)
}

// ---- Screen (layout follows docs/mockups/5 - Monitor Screen.png; one page, never scrolls) ----

@Composable
fun MonitorScreen(
    state: MonitorUiState,
    onDisconnectNow: (List<WarningReason>) -> Unit,
    onOpenTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
    onOpenNetwork: (bssid: String) -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        WaveTopBar(title = "Connection Monitor")
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 16.dp, end = 16.dp, bottom = Spacing.s),
        ) {
            when (state) {
                MonitorUiState.Loading -> Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.l, Alignment.CenterVertically),
                ) {
                    Wavey(WaveyMood.CURIOUS, size = 150.dp)
                    CircularProgressIndicator()
                }
                MonitorUiState.NotConnected -> {
                    MonitorHero(
                        mood = WaveyMood.NORMAL,
                        orbActive = false,
                        pill = { ConnectionPill("Not connected", NeutralFg, Color.White.copy(alpha = 0.85f)) },
                        title = "You're not on Wi-Fi",
                        subtitle = "Join a network marked Verified and WAVE will watch it here.",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.height(Spacing.m))
                    ToolRow(R.drawable.ic_wifi, "Nearby Networks", { onOpenTab(Tab.NETWORKS) }, subtitle = "Find a verified network.")
                }
                is MonitorUiState.Connected -> {
                    val network = state.effective
                    val registry = state.network.verdict.style()
                    val exit = state.exitPath?.style() ?: NotCheckedStyle.copy(label = "Skipped")
                    MonitorHero(
                        mood = state.mood,
                        orbActive = true,
                        pill = {
                            if (state.unsafe) {
                                ConnectionPill("Connected · not safe", WarningFg, Color.White.copy(alpha = 0.85f))
                            } else {
                                ConnectionPill("Connected", VerifiedFg, Color.White.copy(alpha = 0.85f))
                            }
                        },
                        title = network.ssid,
                        subtitle = when {
                            state.unsafe -> "This connection is not safe."
                            network.verdict == Verdict.UNKNOWN -> "WAVE can't verify this network."
                            state.exitPath == ExitPathStatus.PASSED -> "All checks passed. You're safe."
                            else -> "Your connection is being analyzed."
                        },
                        // Hero and details share the page; the hero gets a bit more so Wavey stays the focus.
                        modifier = Modifier.weight(1.3f),
                    ) {
                        CheckSummary(registry = registry, exit = exit)
                        if (state.unsafe) {
                            Spacer(Modifier.height(Spacing.m))
                            PrimaryButton(
                                "Disconnect now",
                                onClick = { onDisconnectNow(network.reasons) },
                                icon = R.drawable.ic_warning,
                                containerColor = Danger,
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.m))
                    DetailsCard(
                        network = network,
                        registry = registry,
                        exit = exit,
                        onOpen = { onOpenNetwork(network.bssid) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * Hero: Wavey hugging a glowing Wi-Fi orb (sized to whatever height is left), the connection pill,
 * the network name, a one-line status, then [bottom] (the Registry / Exit path summary).
 */
@Composable
private fun MonitorHero(
    mood: WaveyMood,
    orbActive: Boolean,
    pill: @Composable () -> Unit,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    bottom: @Composable ColumnScope.() -> Unit = {},
) {
    HeroCard(modifier.fillMaxWidth(), padding = 12.dp) {
        // Wavey fills the space it's given (like the QR result screen): no size cap, centred, so there's no
        // empty sky above it.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            val stage = minOf(maxHeight, maxWidth * 0.66f)
            if (stage >= 48.dp) {
                HeroSparkles(Modifier.matchParentSize())
                WaveyWithOrb(mood, orbActive, stage)
            }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            pill()
            Spacer(Modifier.height(4.dp))
            FitText(
                title,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold),
                color = Navy900,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.5.sp, lineHeight = 18.sp),
                color = Slate600,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.height(Spacing.s))
        bottom()
    }
}

/**
 * The whole Wavey (two fins, two feet, face clear, as on the QR result screen) holding a small glowing Wi-Fi orb
 * in front of its belly: below the mouth, between the fins, above the feet. No extra paws are drawn.
 */
@Composable
private fun WaveyWithOrb(mood: WaveyMood, active: Boolean, stageSize: Dp) {
    // Big orb filling the belly: its top stays below the cheeks (~50% down), its bottom just above the feet (~88%).
    val orb = stageSize * 0.33f
    Box(Modifier.size(stageSize).clearAndSetSemantics {}) {
        Wavey(mood, size = stageSize, animate = false)
        // Orb: soft glass halo + deep-blue disc + white Wi-Fi mark, centred on the belly (~69% down).
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = stageSize * 0.69f - orb * 0.58f)
                .size(orb * 1.16f)
                .background(Color.White.copy(alpha = 0.45f), CircleShape)
                .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                .padding(orb * 0.08f)
                .shadow(10.dp, CircleShape, spotColor = Color(0xFF0A63E8))
                .background(
                    Brush.radialGradient(
                        if (active) listOf(Color(0xFF48B6FF), Color(0xFF0E74F0), Color(0xFF0A5FD8)) else listOf(Color(0xFFB9C4D3), Color(0xFF8995A8)),
                    ),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_wifi), null, tint = Color.White, modifier = Modifier.size(orb * 0.5f))
        }
    }
}

@Composable
private fun HeroSparkles(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawSparkle(Offset(w * 0.30f, h * 0.18f), 11.dp.toPx(), Color(0xFF0EA2FF), glow = Color(0x553FB8FF))
        drawSparkle(Offset(w * 0.22f, h * 0.40f), 7.dp.toPx(), Color.White)
        drawSparkle(Offset(w * 0.72f, h * 0.36f), 11.dp.toPx(), Color.White, glow = Color(0x40FFFFFF))
        drawSparkle(Offset(w * 0.80f, h * 0.52f), 6.dp.toPx(), Color.White)
    }
}

/** "● Connected" pill: colored dot + word on a white pill. */
@Composable
private fun ConnectionPill(text: String, color: Color, background: Color) {
    Row(
        Modifier
            .background(background, WaveShapes.pill)
            .padding(horizontal = 14.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold), color = color)
    }
}

/** White summary card split in two: Registry | Exit path (tile + label + bold value). */
@Composable
private fun CheckSummary(registry: StatusStyle, exit: StatusStyle) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .shadow(8.dp, shape, ambientColor = Color(0x403B82C4), spotColor = Color(0x4D3B82C4))
            .background(Color.White.copy(alpha = 0.92f), shape)
            .border(1.dp, Color.White, shape)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryHalf(registry.icon, registry, "Registry", Modifier.weight(1f))
        Box(
            Modifier
                .padding(horizontal = Spacing.s, vertical = 6.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(Divider),
        )
        SummaryHalf(exit.icon, exit, "Exit path", Modifier.weight(1f))
    }
}

@Composable
private fun SummaryHalf(@DrawableRes icon: Int, style: StatusStyle, label: String, modifier: Modifier) {
    Row(modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .background(Brush.verticalGradient(listOf(style.bg.copy(alpha = 0.7f), style.bg)), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, tint = style.fg, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(9.dp))
        Column {
            FitText(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = Slate600)
            FitText(
                style.label,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.ExtraBold),
                color = Navy900,
            )
        }
    }
}

/**
 * "Connection details" card with the Live pill and five live label/value rows (mockup 5).
 * Tapping it opens every reading for this connection (Network details: checks, Wi-Fi details, IP, link speed).
 */
@Composable
private fun DetailsCard(
    network: NetworkUi,
    registry: StatusStyle,
    exit: StatusStyle,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    WaveCard(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClickLabel = "Open all connection details", role = Role.Button, onClick = onOpen),
        color = Color.White,
        shape = shape,
        padding = 14.dp,
    ) {
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Connection details",
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold),
                color = Navy900,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            LivePill()
            Spacer(Modifier.width(4.dp))
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Slate600, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        // The five rows share the card's height evenly, so the card fills its part of the page.
        DetailLine("BSSID", network.bssid)
        DetailLine("Security type", network.security.label)
        DetailLine("Signal strength", "${network.signal} dBm · ${signalQuality(network.signal).label}")
        DetailLine("Registry verification", registry.label, valueColor = registry.fg)
        DetailLine("Exit-path analysis", exit.label, valueColor = exit.fg, divider = false)
    }
}

/** Read-only label/value line (not tappable, so no 48dp target needed). Grows to share the card's height. */
@Composable
private fun ColumnScope.DetailLine(label: String, value: String, valueColor: Color = Navy900, divider: Boolean = true) {
    Column(
        Modifier
            .weight(1f)
            .padding(horizontal = 4.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(
            Modifier.fillMaxWidth().weight(1f).heightIn(min = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp), color = Slate600, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Spacing.s))
            FitText(
                value,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.5.sp, fontWeight = FontWeight.ExtraBold),
                color = valueColor,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1.2f),
            )
        }
        if (divider) HorizontalDivider(color = Divider)
    }
}

@Composable
private fun LivePill() {
    val alpha = if (LocalInspectionMode.current) {
        1f
    } else {
        rememberInfiniteTransition(label = "live").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
            label = "live-dot",
        ).value
    }
    Row(
        Modifier.background(VerifiedBg, WaveShapes.pill).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.size(8.dp).alpha(alpha).background(VerifiedFg, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text("Live", style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = VerifiedFg)
    }
}

// ---- Previews: verified, warning, unknown, loading (+ pending, not connected) ----

@Composable
private fun PreviewOf(state: MonitorUiState) = WAVETheme {
    WaveBackground { MonitorScreen(state, {}, {}) }
}

@Preview(showSystemUi = true, name = "Verified (exit pending) - mockup")
@Composable
private fun MonitorPendingPreview() = PreviewOf(MonitorUiState.Connected(FakeFixtures.campus, ExitPathStatus.PENDING))

@Preview(showSystemUi = true, name = "Verified (exit passed)")
@Composable
private fun MonitorVerifiedPreview() = PreviewOf(MonitorUiState.Connected(FakeFixtures.campus, ExitPathStatus.PASSED))

@Preview(showSystemUi = true, name = "Warning (exit failed)")
@Composable
private fun MonitorWarningPreview() = PreviewOf(MonitorUiState.Connected(FakeFixtures.campus, ExitPathStatus.FAILED))

@Preview(showSystemUi = true, name = "Unknown")
@Composable
private fun MonitorUnknownPreview() = PreviewOf(MonitorUiState.Connected(FakeFixtures.library, ExitPathStatus.PASSED))

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun MonitorLoadingPreview() = PreviewOf(MonitorUiState.Loading)

@Preview(showSystemUi = true, name = "Not connected")
@Composable
private fun MonitorNotConnectedPreview() = PreviewOf(MonitorUiState.NotConnected)
