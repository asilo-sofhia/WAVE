package com.example.wave.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.wave.di.AppContainer
import com.example.wave.ui.screens.detail.DetailRoute
import com.example.wave.ui.screens.disconnect.DisconnectRoute
import com.example.wave.ui.screens.home.HomeRoute
import com.example.wave.ui.screens.monitor.MonitorRoute
import com.example.wave.ui.screens.nearby.NearbyRoute
import com.example.wave.ui.screens.permission.PermissionRoute
import com.example.wave.ui.screens.qrresult.QrResultRoute
import com.example.wave.ui.screens.scanqr.ScanQrRoute
import com.example.wave.ui.screens.splash.SplashRoute

@Composable
fun WaveNavHost(
    nav: NavHostController,
    container: AppContainer,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(navController = nav, startDestination = startDestination, modifier = modifier) {
        composable(Routes.SPLASH) {
            SplashRoute(
                container = container,
                onDone = { needsPermission ->
                    nav.navigate(if (needsPermission) Routes.PERMISSION else Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.PERMISSION) {
            PermissionRoute(
                container = container,
                onDone = {
                    // From onboarding there's nothing to go back to: continue to Home.
                    if (nav.previousBackStackEntry != null) {
                        nav.popBackStack()
                    } else {
                        nav.navigate(Routes.HOME) { popUpTo(Routes.PERMISSION) { inclusive = true } }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeRoute(
                container = container,
                onOpenTab = { nav.navigateToTab(it) },
                onDisconnectNow = { nav.navigate(Routes.disconnect(it)) },
            )
        }
        composable(Routes.NEARBY) {
            NearbyRoute(
                container = container,
                onOpenNetwork = { nav.navigate(Routes.detail(it)) },
                onNeedPermission = { nav.navigate(Routes.PERMISSION) },
            )
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument(Routes.ARG_BSSID) { type = NavType.StringType }),
        ) { entry ->
            DetailRoute(
                container = container,
                bssid = entry.arguments?.getString(Routes.ARG_BSSID).orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenNetwork = { nav.navigate(Routes.detail(it)) },
            )
        }
        composable(Routes.SCAN_QR) {
            ScanQrRoute(
                container = container,
                onScanned = { nav.navigate(Routes.qrResult(it)) },
                onBack = { nav.navigateToTab(Tab.HOME) },
            )
        }
        composable(
            Routes.QR_RESULT,
            arguments = listOf(navArgument(Routes.ARG_PAYLOAD) { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            QrResultRoute(
                container = container,
                payload = entry.arguments?.getString(Routes.ARG_PAYLOAD).orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenTab = { nav.navigateToTab(it) },
            )
        }
        composable(Routes.MONITOR) {
            MonitorRoute(
                container = container,
                onDisconnectNow = { nav.navigate(Routes.disconnect(it)) },
                onOpenTab = { nav.navigateToTab(it) },
                onOpenNetwork = { nav.navigate(Routes.detail(it)) },
            )
        }
        composable(
            Routes.DISCONNECT,
            arguments = listOf(navArgument(Routes.ARG_REASONS) { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            DisconnectRoute(
                container = container,
                extraReasons = parseReasons(entry.arguments?.getString(Routes.ARG_REASONS)),
                onDone = {
                    nav.navigate(Routes.HOME) {
                        popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                    }
                },
            )
        }
    }
}

/** Standard bottom-nav behavior: one copy of each tab, state kept when switching. */
fun NavHostController.navigateToTab(tab: Tab) {
    // Tapping the tab you're already in (e.g. Monitor while on a details page opened from Monitor)
    // goes back to that tab's first screen instead of restoring the same page.
    if (currentTab() == tab && popBackStack(tab.route, inclusive = false)) return
    navigate(tab.route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * The tab the current screen belongs to, found from the back stack: a details page opened from Monitor belongs
 * to Monitor, one opened from Networks to Networks. Tabs replace each other ([navigateToTab] pops to Home), so at
 * most one tab other than Home is ever in the stack. Null on full-screen pages before Home exists (Splash).
 */
fun NavController.currentTab(): Tab? {
    Tab.entries.firstOrNull { it.route == currentDestination?.route }?.let { return it }
    fun inStack(route: String) = runCatching { getBackStackEntry(route) }.isSuccess
    return Tab.entries.firstOrNull { it != Tab.HOME && inStack(it.route) } ?: Tab.HOME.takeIf { inStack(Routes.HOME) }
}
