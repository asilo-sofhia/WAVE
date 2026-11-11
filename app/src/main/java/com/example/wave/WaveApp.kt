package com.example.wave

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.wave.di.AppContainer
import com.example.wave.ui.components.LocalOpenDebugMenu
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.debug.DebugMenuSheet
import com.example.wave.ui.navigation.Routes
import com.example.wave.ui.navigation.Tab
import com.example.wave.ui.navigation.WaveBottomBar
import com.example.wave.ui.navigation.WaveNavHost
import com.example.wave.ui.navigation.navigateToTab

@Composable
fun WaveApp(container: AppContainer) {
    val context = LocalContext.current
    val debuggable = remember { (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
    val demo = if (debuggable) container.demo else null
    var debugOpen by rememberSaveable { mutableStateOf(false) }
    var resetEpoch by rememberSaveable { mutableIntStateOf(0) }
    val scenario = demo?.scenario?.collectAsStateWithLifecycle()?.value

    CompositionLocalProvider(LocalOpenDebugMenu provides demo?.let { { debugOpen = true } }) {
        WaveBackground {
            // A new scenario (or onboarding reset) restarts navigation so every screen reloads its fake data.
            key(scenario, resetEpoch) {
                val nav = rememberNavController()
                val backStack by nav.currentBackStackEntryAsState()
                val destination = backStack?.destination
                val currentRoute = destination?.route
                val selectedTab = Tab.entries.firstOrNull { tab ->
                    destination?.hierarchy?.any { it.route == tab.route } == true
                } ?: when (currentRoute) {
                    Routes.DETAIL -> Tab.NETWORKS
                    Routes.QR_RESULT -> Tab.SCAN_QR
                    else -> null
                }
                val start = remember { if (container.onboarding.splashSeen) Routes.HOME else Routes.SPLASH }

                Scaffold(
                    containerColor = Color.Transparent,
                    bottomBar = {
                        if (currentRoute in Routes.withBottomBar) {
                            WaveBottomBar(selected = selectedTab, onSelect = { nav.navigateToTab(it) })
                        }
                    },
                ) { padding ->
                    WaveNavHost(
                        nav = nav,
                        container = container,
                        startDestination = start,
                        // Splash draws edge to edge (its sky runs behind the status bar) and pads itself.
                        modifier = if (currentRoute == Routes.SPLASH) Modifier else Modifier.padding(padding),
                    )
                }
            }
        }

        if (debugOpen && demo != null) {
            DebugMenuSheet(
                store = demo,
                onDismiss = { debugOpen = false },
                onResetOnboarding = {
                    container.onboarding.splashSeen = false
                    demo.setLocationGranted(false)
                    resetEpoch++
                },
            )
        }
    }
}
