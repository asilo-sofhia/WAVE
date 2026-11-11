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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.wave.ui.theme.Blue700
import com.example.wave.ui.theme.ButtonHeight
import com.example.wave.ui.theme.Divider
import com.example.wave.ui.theme.MinTouch
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
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        shape = WaveShapes.pill,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
            disabledContainerColor = containerColor.copy(alpha = 0.7f),
            disabledContentColor = Color.White,
        ),
        modifier = modifier.fillMaxWidth().heightIn(min = ButtonHeight),
    ) {
        if (loading) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        } else if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
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
