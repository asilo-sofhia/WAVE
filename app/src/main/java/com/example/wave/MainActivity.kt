package com.example.wave

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.example.wave.ui.theme.WAVETheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as WaveApplication).container

    /** Runtime permission dialog for the real location gate. Registered before onCreate, as Android requires. */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            container.androidPermissionGate?.onResult()
        }
    private val launchPermissions: (Array<String>) -> Unit = { permissionLauncher.launch(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Light theme only: always dark system-bar icons on our light sky background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        container.androidPermissionGate?.attach(launchPermissions)
        setContent {
            WAVETheme {
                WaveApp(container)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed the permission in system settings while we were away.
        container.androidPermissionGate?.refresh()
    }

    override fun onDestroy() {
        container.androidPermissionGate?.detach(launchPermissions)
        super.onDestroy()
    }
}
