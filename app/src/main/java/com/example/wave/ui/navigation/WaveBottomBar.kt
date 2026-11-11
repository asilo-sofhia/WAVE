package com.example.wave.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wave.ui.components.FitText
import com.example.wave.ui.theme.Blue700
import com.example.wave.ui.theme.BlueTint
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.WaveShapes

private val BarShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/**
 * Compact bottom bar like the mockups (64dp + system nav inset, vs Material's 80dp):
 * outlined icon on a light pill when selected, bold blue label. Each tab is a >= 48dp touch target.
 */
@Composable
fun WaveBottomBar(selected: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .shadow(16.dp, BarShape, spotColor = Color(0x553B82C4))
            .clip(BarShape)
            .background(Color.White.copy(alpha = 0.97f))
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(horizontal = 8.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                val isSelected = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 56.dp, height = 30.dp)
                            .background(if (isSelected) BlueTint else Color.Transparent, WaveShapes.pill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(tab.icon),
                            contentDescription = null, // the label below names the tab
                            tint = if (isSelected) Blue700 else Slate600,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.height(3.dp))
                    FitText(
                        tab.label,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        ),
                        color = if (isSelected) Blue700 else Slate600,
                    )
                }
            }
        }
    }
}
