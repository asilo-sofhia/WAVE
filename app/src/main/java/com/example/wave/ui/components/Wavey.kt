package com.example.wave.ui.components

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.provider.Settings
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
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

/**
 * The ONLY composable that draws the mascot. Screens pass a mood; this decides the image.
 *
 * All art is rendered from the Wavey mood viewer (the team's 3D model): animated WebP, 480 px, 48 frames, 2 s loop,
 * with its own breathing and blinking. Every mood has its own look, except: CURIOUS (still checking) is the NORMAL
 * face plus an amber clock badge, and QR_PEEK is the top of HAPPY peeking over the scan frame.
 * When new looks arrive (curious, QR peek), only this file changes.
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
    val boxModifier = modifier.clearAndSetSemantics {
        contentDescription = description
        role = Role.Image
    }

    if (mood == WaveyMood.QR_PEEK) {
        // Wavey from the fin down to just under the eyes, peeking over the scan frame (the frame's paws cover the rest).
        Box(boxModifier.size(width = size, height = size * 0.5f).clipToBounds()) {
            AnimatedArt(
                R.drawable.wavey_happy,
                play,
                Modifier
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .requiredSize(size)
                    .offset(y = -size * PEEK_TOP),
            )
        }
        return
    }

    Box(boxModifier.size(size)) {
        AnimatedArt(art(mood), play, Modifier.fillMaxSize())
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
    WaveyMood.HAPPY -> "Wavey the dolphin, happy: this network is verified"
    WaveyMood.UNCOMFORTABLE -> "Wavey the dolphin, worried"
    WaveyMood.CURIOUS -> "Wavey the dolphin, curious: still checking"
    WaveyMood.CONFUSED -> "Wavey the dolphin, confused: this network is unknown"
    WaveyMood.SCARED -> "Wavey the dolphin, scared: this may be a fake network"
    WaveyMood.QR_PEEK -> "Wavey the dolphin, peeking at the QR code"
    WaveyMood.WAVING -> "Wavey the dolphin, waving hello"
    WaveyMood.SAD -> "Wavey the dolphin, sad: this QR code could not be verified"
}

/** Where the peek starts: the fin tip sits ~15% down the art, so the peek shows fin, face and eyes. */
private const val PEEK_TOP = 0.12f

@DrawableRes
private fun art(mood: WaveyMood): Int = when (mood) {
    WaveyMood.NORMAL, WaveyMood.CURIOUS -> R.drawable.wavey_normal
    WaveyMood.HAPPY, WaveyMood.QR_PEEK -> R.drawable.wavey_happy
    WaveyMood.UNCOMFORTABLE -> R.drawable.wavey_uncomfortable
    WaveyMood.CONFUSED -> R.drawable.wavey_confused
    WaveyMood.SCARED -> R.drawable.wavey_scared
    WaveyMood.SAD -> R.drawable.wavey_sad
    WaveyMood.WAVING -> R.drawable.wavey_waving
}

private fun placeholderBadge(mood: WaveyMood): Pair<Int, Pair<Color, Color>>? = when (mood) {
    // Pending = amber clock (CLAUDE.md), so "still checking" never looks like the Unknown "?".
    WaveyMood.CURIOUS -> R.drawable.ic_schedule to (UnknownFg to UnknownBg)
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
