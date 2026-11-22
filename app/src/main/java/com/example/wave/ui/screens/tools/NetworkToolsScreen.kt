package com.example.wave.ui.screens.tools

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.wave.R
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveTopBar
import com.example.wave.ui.theme.Blue700
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme

/** Opened from the ⋮ on Networks: Wi-Fi radar and Manage registry (admin). */
@Composable
fun NetworkToolsScreen(
    onBack: () -> Unit,
    onOpenRadar: () -> Unit,
    onManageRegistry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        WaveTopBar(title = "Network tools", onBack = onBack)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            ToolCard(R.drawable.ic_radar, "Wi-Fi radar", "View nearby signal strength", onOpenRadar)
            ToolCard(R.drawable.ic_shield_lock, "Manage registry", "Campus admin access required", onManageRegistry)
        }
    }
}

/** White card: blue line icon, bold title, one-line subtitle, chevron. The whole card is the button. */
@Composable
private fun ToolCard(@DrawableRes icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    WaveCard(Modifier.fillMaxWidth().clip(shape), color = Color.White, shape = shape, padding = 0.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .heightIn(min = 72.dp)
                .padding(horizontal = Spacing.l, vertical = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = Blue700, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold), color = Navy900)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Slate600)
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = Slate600)
        }
    }
}

@Preview(showSystemUi = true, name = "Network tools")
@Composable
private fun NetworkToolsPreview() = WAVETheme { WaveBackground { NetworkToolsScreen({}, {}, {}) } }
