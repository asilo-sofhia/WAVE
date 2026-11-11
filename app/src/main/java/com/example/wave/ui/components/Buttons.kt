package com.example.wave.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.wave.ui.theme.Blue700
import com.example.wave.ui.theme.ButtonHeight
import com.example.wave.ui.theme.Divider
import com.example.wave.ui.theme.MinTouch
import com.example.wave.ui.theme.UnknownBg
import com.example.wave.ui.theme.UnknownFg
import com.example.wave.ui.theme.WaveShapes

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    containerColor: Color = Blue700,
    /** Optional gradient fill (e.g. the mockup's deep-blue "Check Wi-Fi"); ignored when disabled. */
    gradient: Brush? = null,
) {
    val useGradient = gradient != null && enabled
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = WaveShapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (useGradient) Color.Transparent else containerColor,
            contentColor = Color.White,
            disabledContainerColor = if (loading && useGradient) Color.Transparent else if (loading) containerColor else UnknownBg,
            disabledContentColor = if (loading) Color.White else UnknownFg,
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ButtonHeight)
            .then(
                if (useGradient) {
                    Modifier
                        .shadow(10.dp, WaveShapes.pill, spotColor = Color(0xFF0A4AA0))
                        .background(gradient!!, WaveShapes.pill)
                } else {
                    Modifier
                },
            ),
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        } else if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = if (gradient != null) MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold) else MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
) {
    OutlinedButton(
        onClick = onClick,
        shape = WaveShapes.pill,
        border = BorderStroke(1.5.dp, Divider),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Blue700),
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonHeight),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun WaveTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = Blue700),
        modifier = modifier.heightIn(min = MinTouch),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
