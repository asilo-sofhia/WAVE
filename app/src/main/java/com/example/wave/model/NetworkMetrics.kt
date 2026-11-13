package com.example.wave.model

/**
 * Radio readings for one access point. Android's scan results give all of these BEFORE you connect,
 * so a user can see why WAVE trusts or distrusts a network without joining it.
 * IP address, link speed and the exit-path check only exist after connecting, so they aren't here.
 */
data class NetworkMetrics(
    val frequencyMhz: Int,
    /** Null when the frequency doesn't map to a known channel. */
    val channel: Int?,
    /** 20 / 40 / 80 / 160 / 320. Null when the phone doesn't report it. */
    val channelWidthMhz: Int?,
    /** Null on Android 10, which doesn't report it (ScanResult.getWifiStandard is API 30+). */
    val wifiStandard: WifiStandard?,
    /** Raw ScanResult.capabilities, e.g. "[RSN-PSK-CCMP][ESS]". Security is parsed from this. */
    val capabilities: String,
    /** Wall-clock time the AP was last heard. Null when unknown. */
    val seenAtMillis: Long? = null,
)

enum class WifiStandard(val label: String) {
    LEGACY("802.11a/b/g"),
    WIFI_4("Wi-Fi 4 (802.11n)"),
    WIFI_5("Wi-Fi 5 (802.11ac)"),
    WIFI_6("Wi-Fi 6 (802.11ax)"),
    WIFI_7("Wi-Fi 7 (802.11be)"),
}

/**
 * One check behind a verdict, so the screen can show what WAVE measured and what it compared it with.
 * The classifier produces these; [WarningReason] stays the short list of failures.
 */
data class VerdictCheck(
    val kind: CheckKind,
    val outcome: CheckOutcome,
    /** One plain-language sentence: what was measured, and what the registry expects. */
    val detail: String,
)

/** The checks, in the order the screen shows them. Names are a contract with the backend: keep stable. */
enum class CheckKind(val title: String, val reason: WarningReason?) {
    NETWORK_NAME("Network name", null),
    ACCESS_POINT("Access point", WarningReason.UNREGISTERED_BSSID),
    HARDWARE_ADDRESS("Hardware address", WarningReason.LOCALLY_ADMINISTERED),
    SECURITY("Security", WarningReason.SECURITY_MISMATCH),
    SIGNAL("Signal strength", WarningReason.RSSI_OUT_OF_RANGE),
}

/** PASSED = green check, FAILED = red triangle, UNKNOWN = amber "?", SKIPPED = gray (nothing to compare with). */
enum class CheckOutcome { PASSED, FAILED, UNKNOWN, SKIPPED }

/** True when the first octet has the "locally administered" bit (0x02) set: typical of hotspots and fake APs. */
fun isLocallyAdministered(bssid: String): Boolean =
    bssid.take(2).toIntOrNull(16)?.let { it and 0x02 != 0 } ?: false
