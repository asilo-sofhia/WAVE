package com.example.wave.ui.navigation

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.wave.ui.theme.Blue700
import com.example.wave.ui.theme.BlueTint
import com.example.wave.ui.theme.Slate600

private val BarShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

@Composable
fun WaveBottomBar(selected: Tab?, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(
        containerColor = Color.White.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
        modifier = modifier
            .shadow(16.dp, BarShape, spotColor = Color(0x553B82C4))
            .clip(BarShape),
    ) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Blue700,
                    selectedTextColor = Blue700,
                    indicatorColor = BlueTint,
                    unselectedIconColor = Slate600,
                    unselectedTextColor = Slate600,
                ),
            )
        }
    }
}
