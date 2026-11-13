package com.example.wave.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wave.R
import com.example.wave.model.WaveyMood
import com.example.wave.ui.theme.Danger
import com.example.wave.ui.theme.DangerBg
import com.example.wave.ui.theme.UnknownBg
import com.example.wave.ui.theme.UnknownFg
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WarningBg
import com.example.wave.ui.theme.WarningFg

/**
 * The ONLY composable that draws the mascot. Screens pass a mood; this decides the image.
 *
 * Today: two real images (NORMAL, HAPPY). The other moods are placeholders built from the base image
 * plus a badge. When the real (animated WebP) art arrives, only this file changes.
 */
@Composable
fun Wavey(
    mood: WaveyMood,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    animate: Boolean = true,
) {
    val description = waveyDescription(mood)
    val bob = if (animate && motionAllowed()) {
        val transition = rememberInfiniteTransition(label = "wavey-bob")
        transition.animateFloat(
            initialValue = -1f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "wavey-bob-y",
        ).value
    } else {
        0f
    }
    val bobPx = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.025f).toPx() }

    val boxModifier = modifier
        .clearAndSetSemantics {
            contentDescription = description
            role = Role.Image
        }
        .graphicsLayer { translationY = bob * bobPx }

    if (mood == WaveyMood.QR_PEEK) {
        // Top half of Wavey peeking over the scan frame.
        Box(boxModifier.size(width = size, height = size * 0.5f).clipToBounds()) {
            Image(
                painter = painterResource(R.drawable.wavey_happy),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    Box(boxModifier.size(size)) {
        Image(
            painter = painterResource(if (mood == WaveyMood.HAPPY) R.drawable.wavey_happy else R.drawable.wavey_normal),
            contentDescription = null,
            colorFilter = if (mood == WaveyMood.BRUISED) bruisedFilter else null,
            modifier = Modifier.fillMaxSize(),
        )
        if (mood == WaveyMood.BRUISED) Bandage(Modifier.fillMaxSize())
        placeholderBadge(mood)?.let { (icon, colors) ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = size * 0.08f, end = size * 0.06f)
                    .size(size * 0.24f)
                    .background(colors.second, CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), null, tint = colors.first, modifier = Modifier.size(size * 0.14f))
            }
        }
    }
}

fun waveyDescription(mood: WaveyMood): String = when (mood) {
    WaveyMood.NORMAL -> "Wavey the dolphin, calm"
    WaveyMood.HAPPY -> "Wavey the dolphin, happy: this connection is safe"
    WaveyMood.UNCOMFORTABLE -> "Wavey the dolphin, worried"
    WaveyMood.CURIOUS -> "Wavey the dolphin, curious"
    WaveyMood.BRUISED -> "Wavey the dolphin, hurt: this connection is not safe"
    WaveyMood.QR_PEEK -> "Wavey the dolphin, peeking at the QR code"
}

private fun placeholderBadge(mood: WaveyMood): Pair<Int, Pair<Color, Color>>? = when (mood) {
    WaveyMood.CURIOUS -> R.drawable.ic_help to (UnknownFg to UnknownBg)
    WaveyMood.UNCOMFORTABLE -> R.drawable.ic_warning to (WarningFg to WarningBg)
    WaveyMood.BRUISED -> R.drawable.ic_error to (Danger to DangerBg)
    else -> null
}

private val bruisedFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.45f) })

@Composable
private fun Bandage(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width * 0.26f
        val h = size.width * 0.085f
        val center = Offset(size.width * 0.33f, size.height * 0.36f)
        rotate(-28f, pivot = center) {
            drawRoundRect(
                color = Color(0xFFF2C9A0),
                topLeft = Offset(center.x - w / 2, center.y - h / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(h / 2),
            )
            drawRoundRect(
                color = Color(0xFFE2AE80),
                topLeft = Offset(center.x - h * 0.6f, center.y - h / 2),
                size = Size(h * 1.2f, h),
                cornerRadius = CornerRadius(h * 0.2f),
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

@Preview(showBackground = true, backgroundColor = 0xFFE8F5FF, widthDp = 640)
@Composable
private fun WaveyMoodsPreview() = WAVETheme {
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WaveyMood.entries.forEach { Wavey(it, size = 96.dp) }
    }
}
