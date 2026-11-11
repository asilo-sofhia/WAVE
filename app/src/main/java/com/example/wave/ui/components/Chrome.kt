package com.example.wave.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.wave.R
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.SkyBottom
import com.example.wave.ui.theme.SkyTop
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WaveShapes

/** Opens the hidden demo menu. Null in release/non-debuggable builds, so the logo does nothing. */
val LocalOpenDebugMenu = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable
fun WaveBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SkyTop, SkyBottom))),
        content = content,
    )
}

/**
 * Top bar: back button (or the WAVE logo), centered title, optional actions.
 * Long-pressing the logo opens the debug menu in debug builds.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WaveTopBar(
    title: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val openDebug = LocalOpenDebugMenu.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) {
        val tileModifier = Modifier
            .align(Alignment.CenterStart)
            .size(48.dp)
            .shadow(6.dp, WaveShapes.tile, spotColor = Color(0x553B82C4))
            .background(Color.White, WaveShapes.tile)
        if (onBack != null) {
            Box(tileModifier, contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back", tint = Navy900)
                }
            }
        } else {
            Box(
                tileModifier.combinedClickable(
                    onClick = {},
                    onLongClick = openDebug,
                    onLongClickLabel = if (openDebug != null) "Open demo menu" else null,
                    role = Role.Image,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.wave_logo), contentDescription = "WAVE logo", modifier = Modifier.size(36.dp))
            }
        }
        Text(
            text = title.orEmpty(),
            style = MaterialTheme.typography.titleLarge,
            color = Navy900,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp)
                .semantics { heading() },
        )
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Square white action button for the top bar (e.g. refresh, help). */
@Composable
fun TopBarAction(icon: Int, contentDescription: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .shadow(6.dp, WaveShapes.tile, spotColor = Color(0x553B82C4))
            .background(Color.White, WaveShapes.tile),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(painterResource(icon), contentDescription = contentDescription, tint = Navy900)
        }
    }
}
