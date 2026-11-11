package com.example.wave.ui.screens.splash

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wave.R
import com.example.wave.di.AppContainer
import com.example.wave.model.WaveyMood
import com.example.wave.ui.components.FitText
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WaveShapes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Landing colors sampled from docs/mockups/1 - Landing page.png.
private val SkyDeep = Color(0xFF6CCDFE)
private val SkyMid = Color(0xFFA6E2FD)
private val SkyLight = Color(0xFFCDEFFD)
private val SkyPale = Color(0xFFE2F5FD)
private val TitleNavy = Color(0xFF0B1B4D)
private val BubbleCyan = Color(0xFF19C3F5)
private val StarBlue = Color(0xFF0EA2FF)

// Button: bright cyan at the knob end like the mockup, deepening to #0874CC under the text so white text is 4.8:1.
private val PillGradient = listOf(Color(0xFF4FD8FD), Color(0xFF1C9DEB), Color(0xFF0874CC), Color(0xFF0870C8))
private val KnobGradient = listOf(Color(0xFF1A8BFF), Color(0xFF0060EE))

@Composable
fun SplashRoute(container: AppContainer, onDone: (needsPermission: Boolean) -> Unit) {
    SplashScreen(
        onGetStarted = {
            container.onboarding.splashSeen = true
            onDone(!container.locationPermission.granted.value)
        },
    )
}

@Composable
fun SplashScreen(onGetStarted: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        LandingBackground(Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val stageMaxHeight = maxHeight * 0.5f
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                // Vertical rhythm from the mockup: top gap : stage : gap : title : gap : button : bottom.
                Spacer(Modifier.weight(0.75f))
                MascotStage(Modifier.fillMaxWidth().heightIn(max = stageMaxHeight))
                Spacer(Modifier.weight(0.28f))
                LandingTitle(Modifier.padding(horizontal = 28.dp))
                Spacer(Modifier.weight(0.5f))
                SwipeToStartButton(
                    text = "Get Started",
                    onComplete = onGetStarted,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.weight(0.42f))
            }
        }
    }
}

// ---- Background: diagonal sky gradient + the big soft arc ----

@Composable
private fun LandingBackground(modifier: Modifier) {
    Canvas(modifier) {
        drawRect(
            Brush.linearGradient(
                0f to SkyDeep,
                0.38f to SkyMid,
                0.68f to SkyLight,
                1f to SkyPale,
                start = Offset(size.width, 0f),
                end = Offset(0f, size.height),
            ),
        )
        // Arc: a huge circle through three points read off the mockup (fractions of the screen).
        val (c, r) = circleThrough(
            Offset(0f, size.height * 0.232f),
            Offset(size.width * 0.72f, size.height * 0.089f),
            Offset(size.width, size.height * 0.10f),
        )
        drawCircle(Color.White.copy(alpha = 0.09f), radius = r, center = c)
        drawCircle(Color.White.copy(alpha = 0.22f), radius = r, center = c, style = Stroke(width = 1.dp.toPx()))
    }
}

private fun circleThrough(a: Offset, b: Offset, c: Offset): Pair<Offset, Float> {
    val d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
    val a2 = a.x * a.x + a.y * a.y
    val b2 = b.x * b.x + b.y * b.y
    val c2 = c.x * c.x + c.y * c.y
    val ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d
    val uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
    val center = Offset(ux, uy)
    return center to sqrt((a.x - ux) * (a.x - ux) + (a.y - uy) * (a.y - uy))
}

// ---- Mascot stage: Wavey, sparkles, bubbles, floor shadow ----

private data class Bubble(val x: Float, val y: Float, val d: Float, val cyan: Boolean)

// Positions as fractions of the stage (mockup stage = x 30..865, y 280..980 px).
private val Bubbles = listOf(
    Bubble(0.120f, 0.461f, 0.018f, cyan = true),
    Bubble(0.240f, 0.241f, 0.013f, cyan = false),
    Bubble(0.764f, 0.233f, 0.019f, cyan = true),
    Bubble(0.887f, 0.410f, 0.017f, cyan = false),
    Bubble(0.170f, 0.804f, 0.017f, cyan = true),
    Bubble(0.790f, 0.824f, 0.017f, cyan = true),
    Bubble(0.165f, 0.526f, 0.010f, cyan = false),
    Bubble(0.820f, 0.780f, 0.010f, cyan = false),
    Bubble(0.835f, 0.470f, 0.009f, cyan = false),
)

@Composable
private fun MascotStage(modifier: Modifier) {
    val twinkle = if (motionAllowed()) {
        rememberInfiniteTransition(label = "twinkle").animateFloat(
            initialValue = 0.82f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "twinkle-scale",
        ).value
    } else {
        1f
    }
    BoxWithConstraints(modifier.aspectRatio(835f / 700f), contentAlignment = Alignment.TopCenter) {
        val mascot = minOf(maxWidth * 0.82f, maxHeight * 0.97f)
        val mascotTop = (maxHeight - mascot) * 0.5f
        val feetY = with(LocalDensity.current) { (mascotTop + mascot * 0.955f).toPx() }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // Soft floor shadow at Wavey's feet: a radial glow squashed into an ellipse.
            val floor = Offset(w / 2, feetY)
            scale(scaleX = 1f, scaleY = 0.16f, pivot = floor) {
                drawCircle(
                    Brush.radialGradient(
                        0f to Color(0xA04FA8E6),
                        0.55f to Color(0x554FA8E6),
                        1f to Color(0x004FA8E6),
                        center = floor,
                        radius = w * 0.36f,
                    ),
                    radius = w * 0.36f,
                    center = floor,
                )
            }
            Bubbles.forEach { b ->
                val center = Offset(w * b.x, h * b.y)
                val radius = w * b.d / 2
                if (b.cyan) {
                    drawCircle(
                        Brush.radialGradient(listOf(Color(0xFF9BEFFF), BubbleCyan), center = center - Offset(radius / 3, radius / 3), radius = radius * 1.4f),
                        radius,
                        center,
                    )
                } else {
                    drawCircle(Color.White.copy(alpha = 0.9f), radius, center)
                }
            }
            sparkle(Offset(w * 0.14f, h * 0.36f), w * 0.052f * twinkle, StarBlue, glow = Color(0x553FB8FF))
            sparkle(Offset(w * 0.86f, h * 0.35f), w * 0.048f * (1.82f - twinkle), Color.White, glow = Color(0x40FFFFFF))
        }
        Wavey(
            mood = WaveyMood.NORMAL,
            size = mascot,
            modifier = Modifier.padding(top = mascotTop),
        )
    }
}

/** Four-point concave sparkle, like the stars in the mockup. */
private fun DrawScope.sparkle(center: Offset, r: Float, color: Color, glow: Color) {
    drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center, r * 1.6f), r * 1.6f, center)
    val k = r * 0.27f
    val (cx, cy) = center
    val path = Path().apply {
        moveTo(cx, cy - r)
        quadraticTo(cx + k, cy - k, cx + r, cy)
        quadraticTo(cx + k, cy + k, cx, cy + r)
        quadraticTo(cx - k, cy + k, cx - r, cy)
        quadraticTo(cx - k, cy - k, cx, cy - r)
        close()
    }
    drawPath(path, color)
}

// ---- Title ----

@Composable
private fun LandingTitle(modifier: Modifier) {
    // Big and heavy like the mockup; shrinks (floor 22dp) instead of overflowing at large font scales.
    val floor = with(LocalDensity.current) { 22.dp.toSp() }
    BasicText(
        text = "Your Wi-Fi Safety Buddy.",
        modifier = modifier.fillMaxWidth().semantics { heading() },
        style = TextStyle(
            color = TitleNavy,
            fontSize = 38.sp,
            lineHeight = 44.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            textAlign = TextAlign.Center,
        ),
        maxLines = 3,
        autoSize = TextAutoSize.StepBased(minFontSize = minOf(floor.value, 38f).sp, maxFontSize = 38.sp, stepSize = 1.sp),
    )
}

// ---- Swipe-to-start pill ----

/** Swipe the knob to the right (or just tap the pill) to continue. */
@Composable
private fun SwipeToStartButton(text: String, onComplete: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val knob = 52.dp
    val inset = 9.dp
    val offset = remember { Animatable(0f) }
    var maxOffset by remember { mutableFloatStateOf(1f) }
    var done by remember { mutableStateOf(false) }
    val progress = (offset.value / maxOffset).coerceIn(0f, 1f)

    fun finish() {
        if (done) return
        done = true
        scope.launch {
            offset.animateTo(maxOffset, tween(220))
            onComplete()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(70.dp)
            .shadow(18.dp, WaveShapes.pill, ambientColor = Color(0xFF29B6F6), spotColor = Color(0xFF1C9DEB))
            .background(Brush.horizontalGradient(PillGradient), WaveShapes.pill)
            .border(1.dp, Color.White.copy(alpha = 0.35f), WaveShapes.pill)
            .onSizeChanged { maxOffset = (it.width - with(density) { (knob + inset * 2).toPx() }).coerceAtLeast(1f) }
            .clickable(onClickLabel = "Get started", role = Role.Button) { finish() },
        contentAlignment = Alignment.Center,
    ) {
        FitText(
            text,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = knob + inset * 2).alpha(1f - progress),
        )
        // Decorative hint; dropped at large font sizes so it never collides with the label.
        if (density.fontScale <= 1.3f) {
            Chevrons(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 22.dp)
                    .alpha(1f - progress),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = inset)
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .size(knob)
                .shadow(8.dp, CircleShape, spotColor = Color(0xFF0050C8))
                .background(Brush.linearGradient(KnobGradient), CircleShape)
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = !done,
                    state = rememberDraggableState { delta ->
                        scope.launch { offset.snapTo((offset.value + delta).coerceIn(0f, maxOffset)) }
                    },
                    onDragStopped = {
                        if (offset.value > maxOffset * 0.7f) finish() else offset.animateTo(0f, spring())
                    },
                )
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

/** ">>>" hint: three chevrons, a light sweeping left to right. */
@Composable
private fun Chevrons(modifier: Modifier) {
    val phase = if (motionAllowed()) {
        rememberInfiniteTransition(label = "chevrons").animateFloat(
            initialValue = 0f,
            targetValue = 3f,
            animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
            label = "chevron-phase",
        ).value
    } else {
        2f
    }
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
        repeat(3) { i ->
            val lit = 1f - (((phase - i) % 3f + 3f) % 3f) / 3f
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                null,
                tint = Color.White.copy(alpha = 0.35f + 0.65f * lit),
                modifier = Modifier.size(30.dp),
            )
        }
    }
}

@Composable
private fun motionAllowed(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

@Preview(showSystemUi = true)
@Composable
private fun SplashPreview() = WAVETheme { SplashScreen(onGetStarted = {}) }

@Preview(showSystemUi = true, device = "spec:width=360dp,height=640dp", name = "Small phone")
@Composable
private fun SplashSmallPreview() = WAVETheme { SplashScreen(onGetStarted = {}) }
