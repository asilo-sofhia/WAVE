package com.example.wave.ui.navigation

import android.net.Uri
import com.example.wave.R
import com.example.wave.model.WarningReason

/** Plain string routes (no serialization plugin needed). */
object Routes {
    const val SPLASH = "splash"
    const val PERMISSION = "permission"
    const val HOME = "home"
    const val NEARBY = "nearby"
    const val SCAN_QR = "scanqr"
    const val MONITOR = "monitor"

    const val ARG_BSSID = "bssid"
    const val DETAIL = "detail/{$ARG_BSSID}"
    fun detail(bssid: String) = "detail/${Uri.encode(bssid)}"

    const val ARG_PAYLOAD = "payload"
    const val QR_RESULT = "qrresult?$ARG_PAYLOAD={$ARG_PAYLOAD}"
    fun qrResult(payload: String) = "qrresult?$ARG_PAYLOAD=${Uri.encode(payload)}"

    const val ARG_REASONS = "reasons"
    const val DISCONNECT = "disconnect?$ARG_REASONS={$ARG_REASONS}"
    fun disconnect(reasons: List<WarningReason>) =
        "disconnect?$ARG_REASONS=${Uri.encode(reasons.joinToString(",") { it.name })}"

    /** Routes that show the bottom bar. Splash, permission and Disconnect-now are full-screen. */
    val withBottomBar = setOf(HOME, NEARBY, SCAN_QR, MONITOR, DETAIL, QR_RESULT)
}

enum class Tab(val route: String, val label: String, val icon: Int) {
    HOME(Routes.HOME, "Home", R.drawable.ic_home_outline),
    NETWORKS(Routes.NEARBY, "Networks", R.drawable.ic_wifi),
    SCAN_QR(Routes.SCAN_QR, "Scan QR", R.drawable.ic_grid),
    MONITOR(Routes.MONITOR, "Monitor", R.drawable.ic_monitor_heart),
}

fun parseReasons(raw: String?): List<WarningReason> =
    raw.orEmpty().split(',').mapNotNull { name -> WarningReason.entries.firstOrNull { it.name == name } }
