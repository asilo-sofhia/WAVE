package com.example.wave.ui.screens.scanqr

import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.wave.R
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.model.WaveyMood
import com.example.wave.ui.components.TopBarAction
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveScreen
import com.example.wave.ui.components.WaveTextButton
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.theme.Blue400
import com.example.wave.ui.theme.Blue700
import com.example.wave.ui.theme.BlueTint
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.ScannerNavy
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WaveShapes

/**
 * Frontend phase: no camera. The viewfinder is an illustration; tapping it (or Gallery) "scans" a sample
 * payload that the FakeQrVerifier answers according to the active scenario.
 */
@Composable
fun ScanQrRoute(onScanned: (payload: String) -> Unit) {
    var flashlightOn by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    ScanQrScreen(
        flashlightOn = flashlightOn,
        onToggleFlashlight = { flashlightOn = !flashlightOn },
        onScan = { onScanned(FakeFixtures.SAMPLE_QR_PAYLOAD) },
        onHelp = { showHelp = true },
    )
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { WaveTextButton("Got it", onClick = { showHelp = false }) },
            title = { Text("Signed Wi-Fi QR codes") },
            text = {
                Text(
                    "Official BatStateU Wi-Fi QR codes carry a digital signature. WAVE checks that signature, " +
                        "so it can tell if someone printed a fake code or edited a real one.",
                )
            },
        )
    }
}

@Composable
fun ScanQrScreen(
    flashlightOn: Boolean,
    onToggleFlashlight: () -> Unit,
    onScan: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WaveScreen(
        title = "Scan Wi-Fi QR",
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        actions = { TopBarAction(R.drawable.ic_help, "What is a signed QR code?", onHelp) },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Wavey(WaveyMood.QR_PEEK, size = 120.dp, modifier = Modifier.padding(top = Spacing.s))
            Viewfinder(onScan, Modifier.widthIn(max = 260.dp).fillMaxWidth())
        }
        Text("Scan a Wi-Fi QR code", style = MaterialTheme.typography.titleLarge, color = Navy900)
        Text(
            "Align the Wi-Fi QR code within the frame.\nDemo: tap the frame to scan a sample code.",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate600,
            textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            ToolButton(
                icon = R.drawable.ic_flash,
                label = if (flashlightOn) "Flashlight on" else "Flashlight",
                selected = flashlightOn,
                onClick = onToggleFlashlight,
                modifier = Modifier.semantics { stateDescription = if (flashlightOn) "On" else "Off" },
            )
            ToolButton(icon = R.drawable.ic_image, label = "Gallery", selected = false, onClick = onScan)
        }
    }
}

@Composable
private fun Viewfinder(onScan: () -> Unit, modifier: Modifier = Modifier) {
    val scanY = if (LocalInspectionMode.current) {
        0.5f
    } else {
        rememberInfiniteTransition(label = "scan").animateFloat(
            initialValue = 0.15f,
            targetValue = 0.85f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
            label = "scan-line",
        ).value
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .shadow(16.dp, WaveShapes.hero, spotColor = Color(0x663B82C4))
            .background(Brush.linearGradient(listOf(ScannerNavy, Color(0xFF1565C0))), WaveShapes.hero)
            .border(6.dp, Color.White.copy(alpha = 0.85f), WaveShapes.hero)
            .clickable(onClickLabel = "Scan sample QR code", role = Role.Button, onClick = onScan),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_qr), null, tint = Color.White, modifier = Modifier.size(120.dp))
        Canvas(Modifier.fillMaxSize().padding(28.dp)) {
            val len = size.minDimension * 0.18f
            val stroke = 6.dp.toPx()
            val c = Color(0xFF7FD3FF)
            val w = size.width
            val h = size.height
            listOf(
                Offset(0f, 0f) to Pair(Offset(len, 0f), Offset(0f, len)),
                Offset(w, 0f) to Pair(Offset(w - len, 0f), Offset(w, len)),
                Offset(0f, h) to Pair(Offset(len, h), Offset(0f, h - len)),
                Offset(w, h) to Pair(Offset(w - len, h), Offset(w, h - len)),
            ).forEach { (corner, ends) ->
                drawLine(c, corner, ends.first, stroke, StrokeCap.Round)
                drawLine(c, corner, ends.second, stroke, StrokeCap.Round)
            }
            val y = h * scanY
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFF9BE3FF), Color.Transparent)),
                Offset(0f, y),
                Offset(w, y),
                strokeWidth = 3.dp.toPx(),
            )
        }
    }
}

@Composable
private fun ToolButton(
    @DrawableRes icon: Int,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .size(width = 132.dp, height = 96.dp)
            .shadow(8.dp, WaveShapes.card, spotColor = Color(0x553B82C4))
            .background(if (selected) BlueTint else Color.White, WaveShapes.card)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(Spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, tint = if (selected) Blue700 else Blue400, modifier = Modifier.size(28.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Navy900, modifier = Modifier.padding(top = Spacing.s))
    }
}

// Scan QR has one state in this phase (idle viewfinder); verifying/results live on the QR result screen.
@Preview(showSystemUi = true, name = "Idle")
@Composable
private fun ScanQrPreview() = WAVETheme {
    WaveBackground { ScanQrScreen(false, {}, {}, {}) }
}

@Preview(showSystemUi = true, name = "Flashlight on")
@Composable
private fun ScanQrFlashPreview() = WAVETheme {
    WaveBackground { ScanQrScreen(true, {}, {}, {}) }
}
