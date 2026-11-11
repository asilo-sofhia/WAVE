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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wave.R
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.model.WaveyMood
import com.example.wave.ui.components.TopBarAction
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveTextButton
import com.example.wave.ui.components.WaveTopBar
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.components.drawSparkle
import com.example.wave.ui.theme.BlueTint
import com.example.wave.ui.theme.BrandIcon
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme

/**
 * Frontend phase: no camera. The viewfinder is an illustration; tapping it (or Gallery) "scans" a sample
 * payload that the FakeQrVerifier answers according to the active scenario.
 */
@Composable
fun ScanQrRoute(onScanned: (payload: String) -> Unit, onBack: () -> Unit) {
    var flashlightOn by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    ScanQrScreen(
        flashlightOn = flashlightOn,
        onToggleFlashlight = { flashlightOn = !flashlightOn },
        onScan = { onScanned(FakeFixtures.SAMPLE_QR_PAYLOAD) },
        onHelp = { showHelp = true },
        onBack = onBack,
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

// ---- Screen (layout follows docs/mockups/4 - Scan Screen.png; one page, never scrolls) ----

@Composable
fun ScanQrScreen(
    flashlightOn: Boolean,
    onToggleFlashlight: () -> Unit,
    onScan: () -> Unit,
    onHelp: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        ScanBackdrop(Modifier.fillMaxSize())
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            WaveTopBar(
                title = "Scan Wi-Fi QR",
                onBack = onBack,
                actions = { TopBarAction(R.drawable.ic_help_outline, "What is a signed QR code?", onHelp, tint = BrandIcon) },
            )
            // Everything below the header is one group, centered in the space left above the nav bar.
            // The frame is sized to whatever height remains after the fixed text and buttons, so the page never scrolls.
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.l),
                contentAlignment = Alignment.Center,
            ) {
                val fixedBelow = 176.dp // gap + title + subtitle + gap + buttons + bottom gap
                // Frame is ~1.27:1 like the mockup; Wavey peeks over it (peek height = 0.29 x frame width).
                val frameW = minOf(maxWidth, 320.dp, (maxHeight - fixedBelow) / 1.08f)
                val frameH = frameW / 1.27f
                val wavey = frameW * 0.58f
                val peek = wavey * 0.5f
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(width = frameW, height = peek + frameH)) {
                        Canvas(Modifier.fillMaxSize()) {
                            val w = size.width
                            drawSparkle(Offset(w * 0.20f, peek.toPx() * 0.32f), 9.dp.toPx(), Color(0xFF0EA2FF), glow = Color(0x553FB8FF))
                            drawSparkle(Offset(w * 0.80f, peek.toPx() * 0.30f), 8.dp.toPx(), Color.White, glow = Color(0x40FFFFFF))
                            drawCircle(Color.White, 2.5.dp.toPx(), Offset(w * 0.13f, peek.toPx() * 0.72f))
                            drawCircle(Color.White, 2.5.dp.toPx(), Offset(w * 0.87f, peek.toPx() * 0.72f))
                        }
                        Wavey(WaveyMood.QR_PEEK, size = wavey, animate = false, modifier = Modifier.align(Alignment.TopCenter))
                        Viewfinder(onScan, Modifier.align(Alignment.BottomCenter).size(frameW, frameH))
                        Paws(
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = peek - wavey * 0.08f)
                                .size(width = wavey * 0.92f, height = wavey * 0.16f),
                        )
                    }
                    Spacer(Modifier.height(Spacing.l))
                    Text(
                        "Scan a Wi-Fi QR code",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.ExtraBold),
                        color = Navy900,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Align the Wi-Fi QR code within the frame.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate600,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Spacing.s),
                    )
                    Spacer(Modifier.height(Spacing.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                        ToolButton(
                            icon = R.drawable.ic_flash_outline,
                            label = if (flashlightOn) "Flashlight on" else "Flashlight",
                            selected = flashlightOn,
                            onClick = onToggleFlashlight,
                            modifier = Modifier.semantics { stateDescription = if (flashlightOn) "On" else "Off" },
                        )
                        ToolButton(icon = R.drawable.ic_image_outline, label = "Gallery", selected = false, onClick = onScan)
                    }
                    Spacer(Modifier.height(Spacing.m))
                }
            }
        }
    }
}

/** Soft light arcs behind the content, like the mockup background. */
@Composable
private fun ScanBackdrop(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawCircle(Color.White.copy(alpha = 0.16f), radius = h * 1.05f, center = Offset(w / 2, h * 1.22f))
        drawCircle(Color.White.copy(alpha = 0.35f), radius = h * 1.05f, center = Offset(w / 2, h * 1.22f), style = Stroke(1.dp.toPx()))
        val wave = Path().apply {
            moveTo(0f, h * 0.80f)
            cubicTo(w * 0.30f, h * 0.74f, w * 0.70f, h * 0.74f, w, h * 0.79f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(wave, Color.White.copy(alpha = 0.14f))
    }
}

/** Glassy rim, deep-blue inside, glowing cyan corner brackets, QR glyph and a sweeping scan line. */
@Composable
private fun Viewfinder(onScan: () -> Unit, modifier: Modifier = Modifier) {
    val scanY = if (LocalInspectionMode.current) {
        0.55f
    } else {
        rememberInfiniteTransition(label = "scan").animateFloat(
            initialValue = 0.22f,
            targetValue = 0.78f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
            label = "scan-line",
        ).value
    }
    val rim = RoundedCornerShape(28.dp)
    val inner = RoundedCornerShape(22.dp)
    Box(
        modifier = modifier
            .shadow(18.dp, rim, ambientColor = Color(0x553B82C4), spotColor = Color(0x663B82C4))
            .background(Color.White.copy(alpha = 0.55f), rim)
            .border(1.5.dp, Color.White.copy(alpha = 0.95f), rim)
            .padding(7.dp)
            .background(
                Brush.radialGradient(listOf(Color(0xFF2C8DC6), Color(0xFF1A6CA6), Color(0xFF135486))),
                inner,
            )
            .clickable(onClickLabel = "Scan sample QR code", role = Role.Button, onClick = onScan),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_qr), null, tint = Color.White, modifier = Modifier.fillMaxSize(0.44f))
        Canvas(Modifier.fillMaxSize()) {
            val inset = 18.dp.toPx()
            val len = size.minDimension * 0.17f
            val r = 10.dp.toPx()
            listOf(
                Triple(Offset(inset, inset), 1f, 1f),
                Triple(Offset(size.width - inset, inset), -1f, 1f),
                Triple(Offset(inset, size.height - inset), 1f, -1f),
                Triple(Offset(size.width - inset, size.height - inset), -1f, -1f),
            ).forEach { (corner, sx, sy) -> bracket(corner, sx, sy, len, r) }

            // Scan line: soft glow band + bright core, fading out at both ends.
            val y = size.height * scanY
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color(0x557FE6FF), Color.Transparent), startY = y - 10.dp.toPx(), endY = y + 10.dp.toPx()),
                topLeft = Offset(inset, y - 10.dp.toPx()),
                size = Size(size.width - inset * 2, 20.dp.toPx()),
            )
            drawLine(
                Brush.horizontalGradient(listOf(Color.Transparent, Color(0xFFE6FDFF), Color.Transparent), startX = inset, endX = size.width - inset),
                Offset(inset, y),
                Offset(size.width - inset, y),
                strokeWidth = 2.5.dp.toPx(),
            )
        }
    }
}

/** Rounded L-shaped corner bracket with a cyan glow. [sx]/[sy] point the arms into the frame. */
private fun DrawScope.bracket(corner: Offset, sx: Float, sy: Float, len: Float, r: Float) {
    val path = Path().apply {
        moveTo(corner.x, corner.y + sy * len)
        lineTo(corner.x, corner.y + sy * r)
        quadraticTo(corner.x, corner.y, corner.x + sx * r, corner.y)
        lineTo(corner.x + sx * len, corner.y)
    }
    drawPath(path, Color(0x4D5FE3FF), style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round))
    drawPath(path, Color(0xFF8AF0FF), style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
}

/** Wavey's two paws resting on the frame's top edge. */
@Composable
private fun Paws(modifier: Modifier) {
    Canvas(modifier) {
        val pawW = size.width * 0.24f
        val pawH = size.height
        listOf(0f, size.width - pawW).forEach { x ->
            drawOval(
                Brush.verticalGradient(listOf(Color(0xFFBDE8FE), Color(0xFF6CC5F6)), startY = 0f, endY = pawH),
                topLeft = Offset(x, 0f),
                size = Size(pawW, pawH),
            )
            drawOval(
                Color.White.copy(alpha = 0.55f),
                topLeft = Offset(x + pawW * 0.22f, pawH * 0.14f),
                size = Size(pawW * 0.38f, pawH * 0.28f),
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
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .size(width = 118.dp, height = 82.dp)
            .shadow(10.dp, shape, ambientColor = Color(0x403B82C4), spotColor = Color(0x553B82C4))
            .background(if (selected) BlueTint else Color.White, shape)
            .border(1.dp, Color.White, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Spacing.m),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(icon), null, tint = BrandIcon, modifier = Modifier.size(30.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold),
            color = Navy900,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
}

// Scan QR has one state in this phase (idle viewfinder); verifying/results live on the QR result screen.
@Preview(showSystemUi = true, name = "Idle")
@Composable
private fun ScanQrPreview() = WAVETheme {
    WaveBackground { ScanQrScreen(false, {}, {}, {}, {}) }
}

@Preview(showSystemUi = true, name = "Flashlight on")
@Composable
private fun ScanQrFlashPreview() = WAVETheme {
    WaveBackground { ScanQrScreen(true, {}, {}, {}, {}) }
}
