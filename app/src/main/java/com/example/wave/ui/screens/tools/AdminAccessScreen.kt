package com.example.wave.ui.screens.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveTopBar
import com.example.wave.ui.theme.Blue700
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme

/**
 * "Manage registry" gate. There are no accounts in WAVE (out of scope), so this prototype only simulates the
 * campus admin check: "Continue as admin" opens Manage registry (the admin's radar).
 */
@Composable
fun AdminAccessScreen(onBack: () -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        WaveTopBar(title = null, onBack = onBack)
        WaveCard(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.l),
        ) {
            Text(
                "Campus admin access",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                color = Navy900,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(Spacing.m))
            Text(
                "This prototype simulates the campus admin access check.",
                style = MaterialTheme.typography.bodyLarge,
                color = Slate600,
            )
            Spacer(Modifier.height(Spacing.s))
            Text(
                "Continue as admin",
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline),
                color = Blue700,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onContinue)
                    .padding(vertical = Spacing.m),
            )
        }
    }
}

@Preview(showSystemUi = true, name = "Campus admin access")
@Composable
private fun AdminAccessPreview() = WAVETheme { WaveBackground { AdminAccessScreen({}, {}) } }
