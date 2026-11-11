package com.example.wave.ui.screens.splash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.wave.R
import com.example.wave.di.AppContainer
import com.example.wave.model.WaveyMood
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.Wavey
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.WAVETheme

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
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(Spacing.xxl))
        Wavey(WaveyMood.NORMAL, size = 240.dp)
        Spacer(Modifier.height(Spacing.xxl))
        Text(
            "Your Wi-Fi safety buddy",
            style = MaterialTheme.typography.displaySmall,
            color = Navy900,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.m))
        Text(
            "WAVE spots fake Wi-Fi networks and tampered Wi-Fi QR codes before you connect.",
            style = MaterialTheme.typography.bodyLarge,
            color = Slate600,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xxl))
        PrimaryButton("Get Started", onClick = onGetStarted, icon = R.drawable.ic_chevron_right)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun SplashPreview() = WAVETheme {
    WaveBackground { SplashScreen(onGetStarted = {}) }
}
