package com.example.wave.ui.screens.tools

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.wave.R
import com.example.wave.data.LocationPermissionGate
import com.example.wave.data.ScanRepository
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.di.AppContainer
import com.example.wave.model.NetworkUi
import com.example.wave.model.signalQuality
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
import com.example.wave.ui.theme.WaveShapes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ---- State ----

sealed interface RadarUiState {
    data object Loading : RadarUiState
    data object NeedsPermission : RadarUiState

    /** One entry per network (a router's radios are merged), strongest first. */
    data class Loaded(val networks: List<NetworkUi>) : RadarUiState
}

class RadarViewModel(private val repo: ScanRepository, permission: LocationPermissionGate) : ViewModel() {
    private val networks = MutableStateFlow<List<NetworkUi>?>(null)

    val state: StateFlow<RadarUiState> = combine(permission.granted, networks) { granted, list ->
        when {
            !granted -> RadarUiState.NeedsPermission
            list == null -> RadarUiState.Loading
            else -> RadarUiState.Loaded(list)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadarUiState.Loading)

    init {
        viewModelScope.launch {
            permission.granted.collect { granted -> if (granted && networks.value == null) scan() }
        }
    }

    private fun scan() = viewModelScope.launch {
        networks.value = groupRows(repo.scanNearby().networks).map { it.network }.sortedByDescending { it.signal }
    }
}

// ---- Route ----

@Composable
fun RadarRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenNetwork: (bssid: String) -> Unit,
    onNeedPermission: () -> Unit,
) {
    val vm = waveViewModel { RadarViewModel(container.scanRepository, container.locationPermission) }
    val state by vm.state.collectAsStateWithLifecycle()
    RadarScreen(state, onBack, onOpenNetwork, onNeedPermission)
}

// ---- Screen ----

@Composable
fun RadarScreen(
    state: RadarUiState,
    onBack: () -> Unit,
    onOpenNetwork: (bssid: String) -> Unit,
    onNeedPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        WaveTopBar(title = "Wi-Fi radar", onBack = onBack)
        Column(Modifier.fillMaxWidth().weight(1f).padding(horizontal = Spacing.gutter)) {
            val networks = (state as? RadarUiState.Loaded)?.networks.orEmpty()
            Radar(
                networks = networks,
                scanning = state == RadarUiState.Loading,
                modifier = Modifier.fillMaxWidth(0.82f).align(Alignment.CenterHorizontally),
            )
            Text(
                "Inner rings show stronger signals",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate600,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.s, bottom = Spacing.l),
            )
            when (state) {
                RadarUiState.NeedsPermission -> WaveCard(Modifier.fillMaxWidth()) {
                    Text(
                        "WAVE needs location access to see nearby Wi-Fi. Your location never leaves your phone.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Navy900,
                    )
                    Spacer(Modifier.height(Spacing.l))
                    PrimaryButton("Allow location", onClick = onNeedPermission, icon = R.drawable.ic_location)
                }
                RadarUiState.Loading -> Box(Modifier.fillMaxWidth().padding(top = Spacing.l), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                is RadarUiState.Loaded -> {
                    SectionHeader(
                        "Nearby networks",
                        Modifier.semantics { heading() },
                        trailing = if (networks.size == 1) "1 result" else "${networks.size} results",
                    )
                    Spacer(Modifier.height(Spacing.s))
                    // Only the list scrolls, and only when there are many networks.
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.s),
                        contentPadding = PaddingValues(bottom = Spacing.l),
                    ) {
                        items(networks, key = { it.bssid }) { n -> RadarRow(n, onScan = { onOpenNetwork(n.bssid) }) }
                    }
                }
            }
        }
    }
}

/**
 * Rings around the phone with a turning sweep. Each network is a dot in its verdict colour: the stronger the
 * signal, the closer to the centre. The angle is fixed per access point so dots don't jump between scans.
 */
@Composable
private fun Radar(networks: List<NetworkUi>, scanning: Boolean, modifier: Modifier = Modifier) {
    val sweep = rememberSweepAngle()
    BoxWithConstraints(
        modifier
            .aspectRatio(1f)
            .clearAndSetSemantics {
                contentDescription = if (scanning) "Radar, scanning" else "Radar showing ${networks.size} networks"
            },
        contentAlignment = Alignment.Center,
    ) {
        val centre = maxWidth * 0.22f
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color.White.copy(alpha = 0.55f), r, c)
            // Sweep: a soft wedge that fades out behind the leading edge.
            rotate(sweep.value, c) {
                drawArc(
                    Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.84f to Color.Transparent,
                        1f to BrandIcon.copy(alpha = 0.35f),
                        center = c,
                    ),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = true,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                )
            }
            listOf(0.40f, 0.60f, 0.80f, 1f).forEach { f ->
                drawCircle(BrandIcon.copy(alpha = if (f == 1f) 0.9f else 0.55f), r * f - 1.dp.toPx(), c, style = Stroke(width = 1.5.dp.toPx()))
            }
            networks.forEach { n ->
                // -30 dBm (very strong) sits just outside the phone; -90 dBm on the outer ring.
                val strength = ((-n.signal - 30) / 60f).coerceIn(0f, 1f)
                val dist = r * (0.30f + 0.66f * strength)
                val angle = Math.toRadians(((n.bssid.hashCode() and 0x7fffffff) % 360).toDouble())
                val p = Offset(c.x + dist * Math.cos(angle).toFloat(), c.y + dist * Math.sin(angle).toFloat())
                drawCircle(Color.White, 7.dp.toPx(), p)
                drawCircle(n.verdict.style().fg, 5.dp.toPx(), p)
            }
        }
        Box(
            Modifier
                .size(centre)
                .shadow(8.dp, CircleShape, spotColor = Color(0x553B82C4))
                .background(Color.White, CircleShape)
                .border(2.dp, BrandIcon.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_phone), contentDescription = null, tint = Blue700, modifier = Modifier.size(centre * 0.5f))
        }
    }
}

/** 0..360 turning every 3 s; still when animations are off (and in previews). */
@Composable
private fun rememberSweepAngle(): State<Float> {
    val still = remember { mutableFloatStateOf(300f) }
    if (LocalInspectionMode.current) return still
    val context = LocalContext.current
    val motion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    if (!motion) return still
    return rememberInfiniteTransition(label = "radar").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart),
        label = "radar-sweep",
    )
}

/** Name + "Verified · Strong" and a blue "Scan" pill that opens the network's check. */
@Composable
private fun RadarRow(network: NetworkUi, onScan: () -> Unit) {
    val style = network.verdict.style()
    WaveCard(Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(18.dp), padding = 0.dp) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = Spacing.l, end = Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                FitText(network.ssid, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Navy900)
                Text(
                    "${style.label} · ${signalQuality(network.signal).label} · ${network.signal} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = Slate600,
                )
            }
            Spacer(Modifier.width(Spacing.s))
            Box(
                Modifier
                    .heightIn(min = 48.dp)
                    .clip(WaveShapes.pill)
                    .clickable(onClickLabel = "Check ${network.ssid}", role = Role.Button, onClick = onScan),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Scan",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    modifier = Modifier
                        .background(Blue700, WaveShapes.pill)
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
        }
    }
}

// ---- Previews: verified, warning, unknown (one radar), loading, permission ----

@Composable
private fun PreviewOf(state: RadarUiState) = WAVETheme { WaveBackground { RadarScreen(state, {}, {}, {}) } }

@Preview(showSystemUi = true, name = "Loaded (verified, warning, unknown)")
@Composable
private fun RadarLoadedPreview() = PreviewOf(RadarUiState.Loaded(listOf(FakeFixtures.campus, FakeFixtures.twin, FakeFixtures.library)))

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun RadarLoadingPreview() = PreviewOf(RadarUiState.Loading)

@Preview(showSystemUi = true, name = "Needs permission")
@Composable
private fun RadarPermissionPreview() = PreviewOf(RadarUiState.NeedsPermission)
