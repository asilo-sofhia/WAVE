package com.example.wave.model

/**
 * One access point as Android's Wi-Fi scan reported it. Raw readings only, no verdict.
 * Produced by data/wifi (lane 2), consumed by the classifier. No Android imports, so it's unit-testable.
 */
data class ScanObservation(
    /** Empty for hidden networks. */
    val ssid: String,
    /** Lowercase, colon-separated (see normalizeBssid). */
    val bssid: String,
    /** Raw android.net.wifi.ScanResult.capabilities, e.g. "[RSN-PSK-CCMP][ESS]". */
    val capabilities: String,
    /** Parsed from [capabilities]. */
    val security: SecurityType,
    val band: WifiBand,
    val channel: Int?,
    val frequencyMhz: Int,
    val channelWidthMhz: Int?,
    /** Null on Android 10 (getWifiStandard is API 30+). */
    val wifiStandard: WifiStandard?,
    /** dBm. */
    val rssi: Int,
    /** Wall-clock time the AP was last heard. */
    val timestampMillis: Long,
)
