package com.example.wave.ui.screens.permission

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.example.wave.R
import com.example.wave.data.LocationPermissionGate
import com.example.wave.di.AppContainer
import com.example.wave.model.WaveyMood
import com.example.wave.ui.components.IconTile
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveTextButton
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.screens.waveViewModel
import com.example.wave.ui.theme.Blue400
import com.example.wave.ui.theme.BlueTint
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WarningBg
import com.example.wave.ui.theme.WarningFg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PermissionUiState {
    data object FirstAsk : PermissionUiState
    data object Requesting : PermissionUiState
    data object Denied : PermissionUiState
    data object Granted : PermissionUiState
}

class PermissionViewModel(private val gate: LocationPermissionGate) : ViewModel() {
    private val _state = MutableStateFlow<PermissionUiState>(
        if (gate.granted.value) PermissionUiState.Granted else PermissionUiState.FirstAsk,
    )
    val state: StateFlow<PermissionUiState> = _state.asStateFlow()

    fun request() {
        viewModelScope.launch {
            _state.value = PermissionUiState.Requesting
            _state.value = if (gate.request()) PermissionUiState.Granted else PermissionUiState.Denied
        }
    }
}

@Composable
fun PermissionRoute(container: AppContainer, onDone: () -> Unit) {
    val vm = waveViewModel { PermissionViewModel(container.locationPermission) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state) { if (state == PermissionUiState.Granted) onDone() }
    PermissionScreen(
        state = state,
        onAllow = vm::request,
        onNotNow = onDone,
        onOpenSettings = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        },
    )
}

@Composable
fun PermissionScreen(
    state: PermissionUiState,
    onAllow: () -> Unit,
    onNotNow: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val denied = state == PermissionUiState.Denied
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Wavey(WaveyMood.CURIOUS, size = 168.dp)
        Text(
            if (denied) "Location is still off" else "Let WAVE see nearby Wi-Fi",
            style = MaterialTheme.typography.headlineSmall,
            color = Navy900,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            if (denied) {
                "Without it, WAVE can't list nearby networks. You can still scan Wi-Fi QR codes."
            } else {
                "Android only shows nearby Wi-Fi networks to apps that have location access."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Slate600,
            textAlign = TextAlign.Center,
        )
        WaveCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
                Point(R.drawable.ic_wifi, "Used only to list Wi-Fi", "WAVE reads network names and access points, nothing else.")
                Point(R.drawable.ic_lock, "Never stored or sent", "Your location never leaves your phone.")
                Point(R.drawable.ic_location, "Only while you scan", "No background tracking.")
            }
        }
        if (denied) {
            WaveCard(Modifier.fillMaxWidth(), color = WarningBg) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(R.drawable.ic_warning, tint = WarningFg, background = WarningBg, size = 36.dp)
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        "Permission was refused. Try again, or turn it on in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Navy900,
                    )
                }
            }
        }
        Spacer(Modifier.height(Spacing.s))
        PrimaryButton(
            text = if (denied) "Try again" else "Allow location",
            onClick = onAllow,
            loading = state == PermissionUiState.Requesting,
            icon = R.drawable.ic_location,
        )
        if (denied) SecondaryButton("Open settings", onClick = onOpenSettings)
        WaveTextButton("Not now", onClick = onNotNow)
    }
}

@Composable
private fun Point(@DrawableRes icon: Int, title: String, body: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
        IconTile(icon, tint = Blue400, background = BlueTint, size = 40.dp)
        Spacer(Modifier.width(Spacing.m))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Navy900)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Slate600)
        }
    }
}

@Composable
private fun PreviewOf(state: PermissionUiState) = WAVETheme {
    WaveBackground { PermissionScreen(state, {}, {}, {}) }
}

@Preview(showSystemUi = true, name = "First ask")
@Composable
private fun PermissionFirstAskPreview() = PreviewOf(PermissionUiState.FirstAsk)

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun PermissionLoadingPreview() = PreviewOf(PermissionUiState.Requesting)

@Preview(showSystemUi = true, name = "Denied")
@Composable
private fun PermissionDeniedPreview() = PreviewOf(PermissionUiState.Denied)
