package com.example.wave.ui.screens.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
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
import com.example.wave.model.waveyMoodFor
import com.example.wave.model.withExitPath
import com.example.wave.ui.components.HeroCard
import com.example.wave.ui.components.LogoTile
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SpeechBubble
import com.example.wave.ui.components.StatusCard
import com.example.wave.ui.components.StatusStyle
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.components.drawSparkle
import com.example.wave.ui.components.style
import com.example.wave.ui.navigation.Tab
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.BrandIcon
import com.example.wave.ui.theme.Danger
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WaveShapes
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---- State ----

sealed interface HomeCheck {
    data object NotChecked : HomeCheck
    data object Checking : HomeCheck
    data object NotOnWifi : HomeCheck

    /** [exitPath] is null when the exit-path check was skipped (network already a WARNING). */
    data class Checked(val network: NetworkUi, val exitPath: ExitPathStatus?) : HomeCheck {
        val effective: NetworkUi get() = network.withExitPath(exitPath)
    }
}

data class HomeUiState(val ssid: String?, val check: HomeCheck) {
    val mood: WaveyMood
        get() = when (check) {
            HomeCheck.NotChecked, HomeCheck.NotOnWifi -> WaveyMood.NORMAL
            HomeCheck.Checking -> WaveyMood.CURIOUS
            is HomeCheck.Checked -> waveyMoodFor(check.effective.verdict, check.exitPath, connected = true)
        }

    /** Connected to a WARNING network (or exit path failed): the only action is to disconnect. */
    val unsafe: Boolean
        get() = check is HomeCheck.Checked && check.effective.verdict == Verdict.WARNING
}

class HomeViewModel(
    private val repo: ScanRepository,
    private val exitPath: ExitPathChecker,
) : ViewModel() {
    private val check = MutableStateFlow<HomeCheck>(HomeCheck.NotChecked)
    private var job: Job? = null

    val state: StateFlow<HomeUiState> = combine(repo.connection, check) { connection, check ->
        val shown = when {
            check == HomeCheck.Checking -> check
            connection == null -> HomeCheck.NotOnWifi
            check is HomeCheck.Checked && check.network.bssid != connection.bssid -> HomeCheck.NotChecked
            else -> check
        }
        HomeUiState(ssid = connection?.ssid, check = shown)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(repo.connection.value?.ssid, HomeCheck.NotChecked))

    fun onCheckWifi() {
        job?.cancel()
        job = viewModelScope.launch {
            check.value = HomeCheck.Checking
            val network = repo.checkConnection()
            if (network == null) {
                check.value = HomeCheck.NotChecked
                return@launch
            }
            if (network.verdict == Verdict.WARNING) {
                check.value = HomeCheck.Checked(network, exitPath = null)
                return@launch
            }
            exitPath.check(network).collect { check.value = HomeCheck.Checked(network, it) }
        }
    }
}

// ---- Route ----

@Composable
fun HomeRoute(
    container: AppContainer,
    onOpenTab: (Tab) -> Unit,
    onDisconnectNow: (List<WarningReason>) -> Unit,
) {
    val vm = waveViewModel { HomeViewModel(container.scanRepository, container.exitPathChecker) }
    val state by vm.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onCheckWifi = vm::onCheckWifi,
        onDisconnectNow = {
            (state.check as? HomeCheck.Checked)?.let { onDisconnectNow(it.effective.reasons) }
        },
        onOpenTab = onOpenTab,
    )
}

// ---- Screen (layout follows docs/mockups/2 - Home Page.png; one page, never scrolls) ----

@Composable
fun HomeScreen(
    state: HomeUiState,
    onCheckWifi: () -> Unit,
    onDisconnectNow: () -> Unit,
    /** Kept for the route; the tools list that used it was removed (the bottom bar has the same tabs). */
    @Suppress("UNUSED_PARAMETER") onOpenTab: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, bottom = Spacing.m),
    ) {
        HomeHeader(Modifier.padding(top = Spacing.s, bottom = Spacing.l))
        // The hero takes the rest of the page; Wavey grows into it.
        HomeHero(state, onCheckWifi, onDisconnectNow, Modifier.weight(1f))
    }
}

/** Logo tile + time-of-day greeting. No name, bell or settings: there are no accounts or settings screen. */
@Composable
private fun HomeHeader(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        LogoTile(size = 56.dp)
        Spacer(Modifier.width(Spacing.l))
        Column {
            Text(greeting(), style = MaterialTheme.typography.bodyLarge, color = Slate600)
            Text(
                "Stay secure on Wi-Fi",
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp, lineHeight = 26.sp),
                color = Navy900,
                modifier = Modifier.semantics { heading() },
            )
        }
    }
}

@Composable
private fun greeting(): String {
    if (LocalInspectionMode.current) return "Good morning!"
    val hour = remember { LocalTime.now().hour }
    return when {
        hour < 12 -> "Good morning!"
        hour < 18 -> "Good afternoon!"
        else -> "Good evening!"
    }
}

@Composable
private fun HomeHero(state: HomeUiState, onCheckWifi: () -> Unit, onDisconnectNow: () -> Unit, modifier: Modifier = Modifier) {
    HeroCard(modifier.fillMaxWidth()) {
        // Stage (mockup): name chip + speech bubble at the top left, Wi-Fi waves at the top right, then a big
        // Wavey centred on the pedestal right under the bubble. Wavey takes all the height left, so there's
        // no empty band above it.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            HeroBackdrop(Modifier.matchParentSize())
            Box(Modifier.align(Alignment.TopStart)) { WaveyChip() }
            // Bubble, Wi-Fi waves and Wavey stay together at the bottom of the stage (on the pedestal); any extra
            // height goes above them, under the chip. The image has transparent margins, so Wavey may be as wide
            // as the card. 150dp leaves room for the chip and the bubble.
            val waveySize = minOf(maxWidth * 1.05f, maxHeight - 150.dp).coerceAtLeast(120.dp)
            val wavesEndPadding = maxWidth * 0.03f
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // Pushed down into the empty top margin of Wavey's image, so its tail sits just above the fin.
                Box(Modifier.fillMaxWidth().offset(y = waveySize * 0.08f)) {
                    val (title, body) = bubbleText(state)
                    SpeechBubble(
                        title = title,
                        body = body,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .fillMaxWidth(0.62f)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    WifiWaves(Modifier.align(Alignment.BottomEnd).padding(end = wavesEndPadding).size(112.dp))
                }
                Wavey(
                    mood = state.mood,
                    size = waveySize,
                    blink = true,
                    modifier = Modifier.offset(y = 6.dp),
                )
            }
        }
        // Clear gap between the pedestal and the cards.
        Row(
            Modifier.fillMaxWidth().padding(top = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            StatusCard(
                icon = R.drawable.ic_wifi,
                label = "Wi-Fi SSID",
                value = state.ssid ?: "Not connected",
                modifier = Modifier.weight(1f),
                large = true,
            )
            StatusCard(
                icon = R.drawable.ic_shield_outline,
                label = "Health",
                value = healthLabel(state),
                style = healthStyle(state),
                modifier = Modifier.weight(1f),
                large = true,
            )
        }
        Spacer(Modifier.height(Spacing.l))
        if (state.unsafe) {
            PrimaryButton("Disconnect now", onClick = onDisconnectNow, icon = R.drawable.ic_warning, containerColor = Danger)
        } else {
            PrimaryButton(
                "Check Wi-Fi",
                onClick = onCheckWifi,
                loading = state.check == HomeCheck.Checking,
                enabled = state.ssid != null,
                gradient = CheckWifiGradient,
            )
        }
        Text(
            hintText(state),
            style = MaterialTheme.typography.bodyMedium,
            color = Slate600,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.m),
        )
    }
}

private val CheckWifiGradient = Brush.verticalGradient(listOf(Color(0xFF1A72CC), Color(0xFF0A4AA0)))

/** "Wavey" name tag with the little two-drop mark, as in the mockup. */
@Composable
private fun WaveyChip() {
    Row(
        Modifier
            .shadow(6.dp, WaveShapes.pill, spotColor = Color(0x553B82C4))
            .background(Color.White, WaveShapes.pill)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Wavey", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Navy900)
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(width = 16.dp, height = 14.dp)) {
            rotate(35f, pivot = Offset(size.width * 0.32f, size.height * 0.3f)) {
                drawOval(BrandIcon, topLeft = Offset(0f, size.height * 0.08f), size = Size(size.width * 0.62f, size.height * 0.42f))
            }
            rotate(35f, pivot = Offset(size.width * 0.7f, size.height * 0.72f)) {
                drawOval(BrandIcon, topLeft = Offset(size.width * 0.42f, size.height * 0.52f), size = Size(size.width * 0.56f, size.height * 0.4f))
            }
        }
    }
}

/** Pedestal disc under Wavey, sparkles and the soft highlight wave at the top of the card. */
@Composable
private fun HeroBackdrop(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Soft highlight wave across the top right.
        val wave = Path().apply {
            moveTo(w * 0.30f, 0f)
            cubicTo(w * 0.55f, h * 0.10f, w * 0.80f, h * 0.02f, w, h * 0.12f)
            lineTo(w, 0f)
            close()
        }
        drawPath(wave, Color.White.copy(alpha = 0.22f))

        // Pedestal: a wide disc whose top face sits at Wavey's feet; its side runs down behind the cards.
        val topY = h - 14.dp.toPx()
        val rx = w * 0.49f
        val ry = 22.dp.toPx()
        drawRect(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.White.copy(alpha = 0.05f)), startY = topY, endY = topY + 70.dp.toPx()),
            topLeft = Offset(w / 2 - rx, topY),
            size = Size(rx * 2, 70.dp.toPx()),
        )
        drawOval(
            Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.45f)), startY = topY - ry, endY = topY + ry),
            topLeft = Offset(w / 2 - rx, topY - ry),
            size = Size(rx * 2, ry * 2),
        )

        // Sparkles and bubbles around Wavey.
        drawSparkle(Offset(w * 0.20f, h * 0.44f), 11.dp.toPx(), Color(0xFF0EA2FF), glow = Color(0x553FB8FF))
        drawSparkle(Offset(w * 0.92f, h * 0.40f), 10.dp.toPx(), Color.White, glow = Color(0x40FFFFFF))
        drawSparkle(Offset(w * 0.95f, h * 0.10f), 3.dp.toPx(), Color.White)
        drawSparkle(Offset(w * 0.05f, h * 0.72f), 3.dp.toPx(), Color.White)
        drawCircle(Color(0xFF19C3F5), 2.5.dp.toPx(), Offset(w * 0.12f, h * 0.60f))
        drawCircle(Color.White, 2.dp.toPx(), Offset(w * 0.96f, h * 0.62f))
    }
}

/** Three glowing Wi-Fi waves opening up and to the right (mockup), drawn beside the speech bubble. */
@Composable
private fun WifiWaves(modifier: Modifier) {
    Canvas(modifier.clearAndSetSemantics {}) {
        val centre = Offset(size.width * 0.12f, size.height * 0.92f)
        listOf(34.dp, 58.dp, 82.dp).forEach { r ->
            val radius = r.toPx()
            val topLeft = Offset(centre.x - radius, centre.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            drawArc(Color(0x6633C8FF), -80f, 62f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round))
            drawArc(Color.White.copy(alpha = 0.92f), -80f, 62f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

private fun bubbleText(state: HomeUiState): Pair<String, String> = when (val c = state.check) {
    HomeCheck.NotChecked -> "I'm ready to scan!" to "Let's check your Wi-Fi."
    HomeCheck.Checking -> "Checking…" to "Looking this network up in the registry."
    HomeCheck.NotOnWifi -> "You're not on Wi-Fi" to "Check nearby networks before you join one."
    is HomeCheck.Checked -> when {
        state.unsafe -> "Disconnect now!" to "This network looks fake."
        c.effective.verdict == Verdict.UNKNOWN -> "I can't verify this one" to "Avoid logging in to important accounts."
        c.exitPath == ExitPathStatus.PASSED -> "All clear!" to "This is the real network."
        else -> "Registry looks right" to "Now checking where your traffic goes…"
    }
}

/** Null = neutral brand tile (mockup's blue shield) for "Not checked"; verdict styles once checked. */
private fun healthStyle(state: HomeUiState): StatusStyle? = when (val c = state.check) {
    is HomeCheck.Checked -> c.effective.verdict.style()
    HomeCheck.Checking -> ExitPathStatus.PENDING.style().copy(label = "Checking")
    else -> null
}

private fun healthLabel(state: HomeUiState): String = healthStyle(state)?.label ?: "Not checked"

private fun hintText(state: HomeUiState): String = when (val c = state.check) {
    HomeCheck.NotChecked -> "Your network has not been checked yet."
    HomeCheck.Checking -> "This takes a few seconds."
    HomeCheck.NotOnWifi -> "Connect to Wi-Fi to check it."
    is HomeCheck.Checked -> when {
        state.unsafe -> "Don't log in or pay for anything on this network."
        c.exitPath == ExitPathStatus.PENDING -> "Exit-path check in progress…"
        else -> "Checked just now."
    }
}

// ---- Previews: verified, warning, unknown, loading (+ not checked) ----

@Composable
private fun PreviewOf(state: HomeUiState) = WAVETheme {
    WaveBackground { HomeScreen(state, {}, {}, {}) }
}

@Preview(showSystemUi = true, name = "Verified")
@Composable
private fun HomeVerifiedPreview() = PreviewOf(
    HomeUiState("BATSTATEU-ICT", HomeCheck.Checked(FakeFixtures.campus, ExitPathStatus.PASSED)),
)

@Preview(showSystemUi = true, name = "Warning")
@Composable
private fun HomeWarningPreview() = PreviewOf(
    HomeUiState("BATSTATEU-ICT", HomeCheck.Checked(FakeFixtures.twin, null)),
)

@Preview(showSystemUi = true, name = "Unknown")
@Composable
private fun HomeUnknownPreview() = PreviewOf(
    HomeUiState("Library Guest", HomeCheck.Checked(FakeFixtures.library, ExitPathStatus.PASSED)),
)

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun HomeLoadingPreview() = PreviewOf(HomeUiState("BATSTATEU-ICT", HomeCheck.Checking))

@Preview(showSystemUi = true, name = "Not checked")
@Composable
private fun HomeNotCheckedPreview() = PreviewOf(HomeUiState("BATSTATEU-ICT", HomeCheck.NotChecked))
