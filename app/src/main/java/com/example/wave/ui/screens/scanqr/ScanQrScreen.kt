package com.example.wave.ui.screens.scanqr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.example.wave.R
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.di.AppContainer
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
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live camera in the viewfinder (CameraX), read by ML Kit; the first QR code found goes to [onScanned].
 * Flashlight = the camera's torch. Gallery = pick a picture (Android photo picker, no permission needed) and
 * read the QR code in it. Without camera permission the viewfinder asks for it, and Gallery still works.
 * Demo mode (debug menu) keeps the old shortcut: tap the viewfinder to "scan" a sample code.
 */
@Composable
fun ScanQrRoute(container: AppContainer, onScanned: (payload: String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val scope = rememberCoroutineScope()
    val reader = container.qrImageReader

    fun cameraGranted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var hasCamera by remember { mutableStateOf(cameraGranted()) }
    var askedForCamera by rememberSaveable { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }
    var torchOn by rememberSaveable { mutableStateOf(false) }
    var torchAvailable by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var showHelp by rememberSaveable { mutableStateOf(false) }

    // Camera frames arrive many times a second: hand over only the first code, once.
    val delivered = remember { AtomicBoolean(false) }
    val deliver: (String) -> Unit = { text -> if (delivered.compareAndSet(false, true)) onScanned(text) }
    val analyzer = remember(reader) { reader.analyzer { deliver(it) } }

    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCamera = granted
        if (!granted) message = "Camera is off. You can still pick a QR picture from Gallery."
    }
    val askForCamera: () -> Unit = {
        val permanentlyDenied = askedForCamera && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        if (permanentlyDenied) {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        } else {
            askedForCamera = true
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    // Ask once when the screen first opens; after that only when the user taps the viewfinder.
    LaunchedEffect(Unit) { if (!hasCamera && !askedForCamera) askForCamera() }
    // Back from system settings: the permission may have changed.
    LifecycleResumeEffect(Unit) {
        hasCamera = cameraGranted()
        onPauseOrDispose { }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            message = "Reading the picture…"
            scope.launch {
                val text = reader.readFromImage(uri)
                if (text != null) {
                    message = null
                    deliver(text)
                } else {
                    message = "No QR code found in that picture. Try a sharper, closer photo."
                }
            }
        }
    }

    val demoSample = if (container.realWifi) null else FakeFixtures.SAMPLE_QR_PAYLOAD
    val cameraOn = hasCamera && !cameraFailed
    ScanQrScreen(
        flashlightOn = torchOn && torchAvailable,
        onToggleFlashlight = { torchOn = !torchOn },
        onScan = when {
            !hasCamera -> askForCamera
            demoSample != null -> { { deliver(demoSample) } }
            else -> null
        },
        onHelp = { showHelp = true },
        onBack = onBack,
        onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        flashlightEnabled = cameraOn && torchAvailable,
        message = message,
        viewfinderHint = when {
            !hasCamera -> "Tap to turn on the camera"
            cameraFailed -> "Camera unavailable"
            else -> null
        },
        camera = if (cameraOn) {
            {
                QrCameraPreview(
                    torchOn = torchOn,
                    analyzer = analyzer,
                    onTorchAvailable = { torchAvailable = it },
                    onCameraError = { cameraFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            null
        },
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
    /** Viewfinder tap (turn on the camera, or the demo sample). Null = not tappable. */
    onScan: (() -> Unit)?,
    onHelp: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onGallery: () -> Unit = { onScan?.invoke() },
    flashlightEnabled: Boolean = true,
    /** Replaces the instructions line (e.g. "No QR code found in that picture"). Announced by TalkBack. */
    message: String? = null,
    /** Shown under the QR glyph when there's no live camera. */
    viewfinderHint: String? = null,
    /** The live camera preview, drawn inside the frame under the brackets and scan line. */
    camera: (@Composable () -> Unit)? = null,
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
                        Viewfinder(onScan, camera, viewfinderHint, Modifier.align(Alignment.BottomCenter).size(frameW, frameH))
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
                        message ?: "Align the Wi-Fi QR code within the frame.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate600,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = Spacing.s)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Spacer(Modifier.height(Spacing.l))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                        ToolButton(
                            icon = if (flashlightOn) R.drawable.ic_flash else R.drawable.ic_flash_outline,
                            label = if (flashlightOn) "Flashlight on" else "Flashlight",
                            selected = flashlightOn,
                            onClick = onToggleFlashlight,
                            enabled = flashlightEnabled,
                            modifier = Modifier.semantics {
                                stateDescription = when {
                                    !flashlightEnabled -> "Unavailable"
                                    flashlightOn -> "On"
                                    else -> "Off"
                                }
                            },
                        )
                        ToolButton(icon = R.drawable.ic_image_outline, label = "Gallery", selected = false, onClick = onGallery)
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

/**
 * Glassy rim, glowing cyan corner brackets and a sweeping scan line over either the live [camera] or
 * (no camera) the deep-blue fill with a QR glyph and [hint].
 */
@Composable
private fun Viewfinder(
    onScan: (() -> Unit)?,
    camera: (@Composable () -> Unit)?,
    hint: String?,
    modifier: Modifier = Modifier,
) {
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
            .clip(inner)
            .then(
                if (onScan != null) {
                    Modifier.clickable(onClickLabel = hint ?: "Scan sample QR code", role = Role.Button, onClick = onScan)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (camera != null) {
            camera()
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_qr), null, tint = Color.White, modifier = Modifier.size(64.dp))
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Spacing.s, start = Spacing.l, end = Spacing.l),
                    )
                }
            }
        }
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
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .size(width = 118.dp, height = 82.dp)
            .shadow(10.dp, shape, ambientColor = Color(0x403B82C4), spotColor = Color(0x553B82C4))
            .background(if (selected) BlueTint else Color.White, shape)
            .border(1.dp, Color.White, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
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

// Previews show the screen without a live camera (the camera only runs on a device).
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

@Preview(showSystemUi = true, name = "Camera off + message")
@Composable
private fun ScanQrNoCameraPreview() = WAVETheme {
    WaveBackground {
        ScanQrScreen(
            false, {}, {}, {}, {},
            flashlightEnabled = false,
            message = "No QR code found in that picture. Try a sharper, closer photo.",
            viewfinderHint = "Tap to turn on the camera",
        )
    }
}
