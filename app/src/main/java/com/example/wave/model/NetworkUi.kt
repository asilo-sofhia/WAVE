package com.example.wave.model

/** Personal-mode security only. WPA2/3-Enterprise is out of scope. */
enum class SecurityType(val label: String) {
    OPEN("Open (no password)"),
    WEP("WEP"),
    WPA2_PERSONAL("WPA2 Personal"),
    WPA3_PERSONAL("WPA3 Personal"),
    WPA2_WPA3_PERSONAL("WPA2/WPA3 Personal"),
}

enum class WifiBand(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
}

/** One Wi-Fi network as the UI shows it. [signal] is in dBm. */
data class NetworkUi(
    val ssid: String,
    val bssid: String,
    val security: SecurityType,
    val band: WifiBand,
    val signal: Int,
    val verdict: Verdict,
    val reasons: List<WarningReason> = emptyList(),
)

data class RegistryInfo(val source: String, val updatedAt: String)

data class ScanResult(
    val networks: List<NetworkUi>,
    val registry: RegistryInfo,
    val scannedAtMillis: Long,
)

enum class SignalQuality(val label: String, val bars: Int) {
    EXCELLENT("Excellent", 4),
    GOOD("Good", 3),
    FAIR("Fair", 2),
    WEAK("Weak", 1),
}

fun signalQuality(dBm: Int): SignalQuality = when {
    dBm >= -55 -> SignalQuality.EXCELLENT
    dBm >= -67 -> SignalQuality.GOOD
    dBm >= -75 -> SignalQuality.FAIR
    else -> SignalQuality.WEAK
}

/** When the exit-path check fails, the network becomes a WARNING with [WarningReason.EXIT_ASN_MISMATCH]. */
fun NetworkUi.withExitPath(status: ExitPathStatus?): NetworkUi =
    if (status == ExitPathStatus.FAILED && WarningReason.EXIT_ASN_MISMATCH !in reasons) {
        copy(verdict = Verdict.WARNING, reasons = reasons + WarningReason.EXIT_ASN_MISMATCH)
    } else {
        this
    }
