package com.example.wave.ui.screens.qrresult

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.example.wave.data.QrVerifier
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.di.AppContainer
import com.example.wave.model.QrResult
import com.example.wave.model.QrVerification
import com.example.wave.model.Verdict
import com.example.wave.model.WaveyMood
import com.example.wave.model.verdict
import com.example.wave.model.waveyMoodFor
import com.example.wave.ui.components.DetailRow
import com.example.wave.ui.components.HeroCard
import com.example.wave.ui.components.PrimaryButton
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
import kotlinx.coroutines.launch

// ---- State ----

sealed interface QrResultUiState {
    data object Loading : QrResultUiState
    data class Done(val verification: QrVerification) : QrResultUiState
}

class QrResultViewModel(verifier: QrVerifier, payload: String) : ViewModel() {
    private val _state = MutableStateFlow<QrResultUiState>(QrResultUiState.Loading)
    val state: StateFlow<QrResultUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.value = QrResultUiState.Done(verifier.verify(payload)) }
    }
}

// ---- Route ----

@Composable
fun QrResultRoute(
    container: AppContainer,
    payload: String,
    onBack: () -> Unit,
    onOpenTab: (Tab) -> Unit,
) {
    val vm = waveViewModel { QrResultViewModel(container.qrVerifier, payload) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    QrResultScreen(
        state = state,
        onBack = onBack,
        onAction = { v ->
            when {
                // Joining is done in the system Wi-Fi panel (Android 10+ apps can't join for the user silently).
                v.result == QrResult.VALID && !v.signedButUnregistered -> openWifiSettings(context)
                v.result == QrResult.VALID -> onOpenTab(Tab.NETWORKS)
                v.result == QrResult.UNSIGNED || v.result == QrResult.UNKNOWN_KEY -> onOpenTab(Tab.NETWORKS)
                else -> onBack()
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

private data class QrCopy(val chip: String, val title: String, val body: String, val action: String)

private fun copyFor(v: QrVerification): QrCopy {
    val ssid = v.network?.ssid ?: "this network"
    return when (v.result) {
        QrResult.VALID -> if (v.signedButUnregistered) {
            QrCopy(
                "Not registered", "Signed by ${v.signer}",
                "The code is genuine, but $ssid isn't on the registry, so WAVE can't vouch for the network itself.",
                "Check nearby networks",
            )
        } else {
            QrCopy(
                "Valid", "Signed by ${v.signer}",
                "This QR code is genuine and hasn't been changed. Before you join $ssid, check that its access " +
                    "point shows Verified on Networks: a real code can be stuck next to a fake router.",
                "Connect to $ssid",
            )
        }
        QrResult.BAD_SIGNATURE -> QrCopy(
            "Changed", "This QR code was changed",
            "Someone edited it after it was signed. It may send you to a fake network. Don't use it.",
            "Scan another code",
        )
        QrResult.EXPIRED -> QrCopy(
            "Expired", "This QR code has expired",
            "It was valid until ${v.expiresAt}. Ask the ICT office for a new one.",
            "Scan another code",
        )
        QrResult.UNSIGNED -> QrCopy(
            "Unsigned", "This QR code isn't signed",
            "Anyone can make a code like this, so WAVE can't confirm it's official. Check nearby networks instead.",
            "Check nearby networks",
        )
        QrResult.UNKNOWN_KEY -> QrCopy(
            "Unknown signer", "Signed by someone WAVE doesn't know",
            "The signature isn't from a trusted issuer, so WAVE can't confirm this network.",
            "Check nearby networks",
        )
        QrResult.MALFORMED -> QrCopy(
            "Not Wi-Fi", "This isn't a Wi-Fi QR code",
            "The code doesn't contain any Wi-Fi details.",
            "Scan another code",
        )
    }
}

@Composable
fun QrResultScreen(
    state: QrResultUiState,
    onBack: () -> Unit,
    onAction: (QrVerification) -> Unit,
    modifier: Modifier = Modifier,
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
            is QrResultUiState.Done -> Done(state.verification, onAction)
        }
    }
}

@Composable
private fun Done(v: QrVerification, onAction: (QrVerification) -> Unit) {
    val copy = copyFor(v)
    val verdict = v.shownVerdict
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

    v.network?.let { network ->
        WaveCard(Modifier.fillMaxWidth()) {
            Text("What the code says", style = MaterialTheme.typography.titleMedium, color = Navy900)
            DetailRow("Network", network.ssid)
            DetailRow("Security", network.security.label, showDivider = false)
        }
    }

    PrimaryButton(
        copy.action,
        onClick = { onAction(v) },
        icon = when (v.result) {
            QrResult.VALID -> R.drawable.ic_wifi
            QrResult.UNSIGNED, QrResult.UNKNOWN_KEY -> R.drawable.ic_wifi
            else -> R.drawable.ic_qr
        },
    )

    TechDetailsToggle(
        buildList {
            add("Result code" to v.result.name)
            add("Signer" to (v.signer ?: "None"))
            add("Expires" to (v.expiresAt ?: "-"))
            add("Raw payload" to v.rawPayload)
        },
    )
}

// ---- Previews: verified, warning, unknown, loading (+ every QrResult) ----

@Composable
private fun PreviewOf(state: QrResultUiState) = WAVETheme {
    WaveBackground { QrResultScreen(state, {}, {}) }
}

@Preview(showSystemUi = true, name = "Verified (VALID)")
@Composable
private fun QrValidPreview() = PreviewOf(QrResultUiState.Done(FakeFixtures.qr(QrResult.VALID)))

@Preview(showSystemUi = true, name = "Warning (BAD_SIGNATURE)")
@Composable
private fun QrChangedPreview() = PreviewOf(QrResultUiState.Done(FakeFixtures.qr(QrResult.BAD_SIGNATURE)))

@Preview(showSystemUi = true, name = "Warning (EXPIRED)")
@Composable
private fun QrExpiredPreview() = PreviewOf(QrResultUiState.Done(FakeFixtures.qr(QrResult.EXPIRED)))

@Preview(showSystemUi = true, name = "Unknown (UNSIGNED)")
@Composable
private fun QrUnsignedPreview() = PreviewOf(QrResultUiState.Done(FakeFixtures.qr(QrResult.UNSIGNED)))

@Preview(showSystemUi = true, name = "Unknown (UNKNOWN_KEY)")
@Composable
private fun QrUnknownKeyPreview() = PreviewOf(QrResultUiState.Done(FakeFixtures.qr(QrResult.UNKNOWN_KEY)))

@Preview(showSystemUi = true, name = "Unknown (MALFORMED)")
@Composable
private fun QrMalformedPreview() = PreviewOf(QrResultUiState.Done(FakeFixtures.qr(QrResult.MALFORMED)))

@Preview(showSystemUi = true, name = "Loading")
@Composable
private fun QrLoadingPreview() = PreviewOf(QrResultUiState.Loading)
