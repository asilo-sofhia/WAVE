package com.example.wave.ui.navigation

import android.net.Uri
import com.example.wave.R
import com.example.wave.model.WarningReason
import com.example.wave.ui.screens.tools.AdminTab

/** Plain string routes (no serialization plugin needed). */
object Routes {
    const val SPLASH = "splash"
    const val PERMISSION = "permission"
    const val HOME = "home"
    const val NEARBY = "nearby"
    const val SCAN_QR = "scanqr"
    const val MONITOR = "monitor"

    // Networks ⋮ menu: Network tools and the pages it opens.
    const val NETWORK_TOOLS = "networktools"
    const val RADAR = "radar"
    const val ADMIN_ACCESS = "adminaccess"
    /** Manage registry: the campus admin's radar (after the admin gate), optionally opened on one tab. */
    const val ARG_TAB = "tab"
    const val SURVEY = "survey?$ARG_TAB={$ARG_TAB}"
    fun survey(tab: AdminTab? = null) = if (tab == null) "survey" else "survey?$ARG_TAB=${tab.name}"

    const val ARG_BSSID = "bssid"
    const val DETAIL = "detail/{$ARG_BSSID}"
    fun detail(bssid: String) = "detail/${Uri.encode(bssid)}"

    /** The admin's view of a network from the Wi-Fi radar (no registry checks; Approve / Block). */
    const val ADMIN_SCAN = "adminscan/{$ARG_BSSID}"
    fun adminScan(bssid: String) = "adminscan/${Uri.encode(bssid)}"

    const val ARG_PAYLOAD = "payload"
    const val QR_RESULT = "qrresult?$ARG_PAYLOAD={$ARG_PAYLOAD}"
    fun qrResult(payload: String) = "qrresult?$ARG_PAYLOAD=${Uri.encode(payload)}"

    const val ARG_REASONS = "reasons"
    const val DISCONNECT = "disconnect?$ARG_REASONS={$ARG_REASONS}"
    fun disconnect(reasons: List<WarningReason>) =
        "disconnect?$ARG_REASONS=${Uri.encode(reasons.joinToString(",") { it.name })}"

    /**
     * Routes that show the bottom bar. Splash, permission, Disconnect-now and the Network tools sub-pages
     * (radar, admin access, survey) are full-screen.
     */
    val withBottomBar = setOf(HOME, NEARBY, SCAN_QR, MONITOR, DETAIL, QR_RESULT, NETWORK_TOOLS)
}

enum class Tab(val route: String, val label: String, val icon: Int) {
    HOME(Routes.HOME, "Home", R.drawable.ic_home_outline),
    NETWORKS(Routes.NEARBY, "Networks", R.drawable.ic_wifi),
    SCAN_QR(Routes.SCAN_QR, "Scan QR", R.drawable.ic_grid),
    MONITOR(Routes.MONITOR, "Monitor", R.drawable.ic_monitor_heart),
}

fun parseReasons(raw: String?): List<WarningReason> =
    raw.orEmpty().split(',').mapNotNull { name -> WarningReason.entries.firstOrNull { it.name == name } }
