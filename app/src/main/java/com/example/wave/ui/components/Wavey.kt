package com.example.wave.ui.components

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.provider.Settings
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.viewinterop.AndroidView
import com.example.wave.R
import com.example.wave.model.WaveyMood
import com.example.wave.ui.theme.UnknownBg
import com.example.wave.ui.theme.UnknownFg
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.ui.theme.WarningBg
import com.example.wave.ui.theme.WarningFg

/**
 * The ONLY composable that draws the mascot. Screens pass a mood; this decides the image.
 *
 * Real art: NORMAL, HAPPY (still) and WAVING, SCARED, SAD (animated WebP, 48 frames, 2 s loop, exported
 * from the Wavey mood viewer). CURIOUS and UNCOMFORTABLE are still the base image plus a badge, and
 * QR_PEEK is the top half of HAPPY. When their art arrives, only this file changes.
 */
@Composable
fun Wavey(
    mood: WaveyMood,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    animate: Boolean = true,
) {
    val description = waveyDescription(mood)
    val play = animate && motionAllowed()
    val animated = animatedArt(mood)
    // Animated art already moves, so only still art gets the gentle bob.
    val bob = if (play && animated == null) {
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
        if (animated != null) {
            AnimatedArt(animated, play, Modifier.fillMaxSize())
        } else {
            Image(
                painter = painterResource(if (mood == WaveyMood.HAPPY) R.drawable.wavey_happy else R.drawable.wavey_normal),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
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
    WaveyMood.SCARED -> "Wavey the dolphin, scared: you may be connected to a fake network"
    WaveyMood.QR_PEEK -> "Wavey the dolphin, peeking at the QR code"
    WaveyMood.WAVING -> "Wavey the dolphin, waving hello"
    WaveyMood.SAD -> "Wavey the dolphin, sad: this QR code could not be verified"
}

@DrawableRes
private fun animatedArt(mood: WaveyMood): Int? = when (mood) {
    WaveyMood.WAVING -> R.drawable.wavey_waving
    WaveyMood.SCARED -> R.drawable.wavey_scared
    WaveyMood.SAD -> R.drawable.wavey_sad
    else -> null
}

private fun placeholderBadge(mood: WaveyMood): Pair<Int, Pair<Color, Color>>? = when (mood) {
    WaveyMood.CURIOUS -> R.drawable.ic_help to (UnknownFg to UnknownBg)
    WaveyMood.UNCOMFORTABLE -> R.drawable.ic_warning to (WarningFg to WarningBg)
    else -> null
}

/**
 * Plays an animated WebP with the platform decoder (API 28+, no extra library).
 * When [play] is false (reduced motion, or the caller turned animation off) it holds the first frame.
 */
@Composable
private fun AnimatedArt(@DrawableRes res: Int, play: Boolean, modifier: Modifier) {
    if (LocalInspectionMode.current) {
        // Previews can't run ImageDecoder; the first frame is enough there.
        Image(painterResource(res), contentDescription = null, modifier = modifier)
        return
    }
    val context = LocalContext.current
    val drawable = remember(res) {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.resources, res))
    }
    DisposableEffect(drawable, play) {
        (drawable as? AnimatedImageDrawable)?.let { if (play) it.start() else it.stop() }
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { it.setImageDrawable(drawable) },
        modifier = modifier,
    )
}

@Composable
private fun motionAllowed(): Boolean {
    if (LocalInspectionMode.current) return false
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFE8F5FF, widthDp = 860)
@Composable
private fun WaveyMoodsPreview() = WAVETheme {
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WaveyMood.entries.forEach { Wavey(it, size = 96.dp) }
    }
}
