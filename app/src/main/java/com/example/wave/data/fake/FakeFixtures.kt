package com.example.wave.data.fake

import com.example.wave.model.CheckKind
import com.example.wave.model.CheckOutcome
import com.example.wave.model.NetworkMetrics
import com.example.wave.model.NetworkUi
import com.example.wave.model.QrResult
import com.example.wave.model.QrVerification
import com.example.wave.model.RegistryInfo
import com.example.wave.model.SecurityType
import com.example.wave.model.Verdict
import com.example.wave.model.VerdictCheck
import com.example.wave.model.WarningReason
import com.example.wave.model.WifiBand
import com.example.wave.model.WifiStandard

/** Fake networks used by the Fake* implementations and by @Preview functions. */
object FakeFixtures {
    private fun check(kind: CheckKind, outcome: CheckOutcome, detail: String) = VerdictCheck(kind, outcome, detail)

    val campus = NetworkUi(
        ssid = "BATSTATEU-ICT",
        bssid = "8C:3B:AD:72:10:4F",
        security = SecurityType.WPA2_PERSONAL,
        band = WifiBand.GHZ_5,
        signal = -50,
        verdict = Verdict.VERIFIED,
        metrics = NetworkMetrics(5180, channel = 36, channelWidthMhz = 80, WifiStandard.WIFI_6, "[RSN-PSK-CCMP][ESS]"),
        checks = listOf(
            check(CheckKind.NETWORK_NAME, CheckOutcome.PASSED, "BATSTATEU-ICT is on the school's registry."),
            check(CheckKind.ACCESS_POINT, CheckOutcome.PASSED, "Matches a registered access point (Main Building)."),
            check(CheckKind.HARDWARE_ADDRESS, CheckOutcome.PASSED, "Manufacturer-assigned address, as real routers have."),
            check(CheckKind.SECURITY, CheckOutcome.PASSED, "WPA2 Personal, same as the registry."),
            check(CheckKind.SIGNAL, CheckOutcome.PASSED, "-50 dBm, inside the normal range here (-55 to -40 dBm)."),
        ),
    )

    val lab = NetworkUi(
        ssid = "CICS-LAB-5G",
        bssid = "8C:3B:AD:72:22:91",
        security = SecurityType.WPA3_PERSONAL,
        band = WifiBand.GHZ_5,
        signal = -74,
        verdict = Verdict.VERIFIED,
        metrics = NetworkMetrics(5745, channel = 149, channelWidthMhz = 80, WifiStandard.WIFI_6, "[RSN-SAE-CCMP][ESS]"),
        checks = listOf(
            check(CheckKind.NETWORK_NAME, CheckOutcome.PASSED, "CICS-LAB-5G is on the school's registry."),
            check(CheckKind.ACCESS_POINT, CheckOutcome.PASSED, "Matches a registered access point (CICS Building)."),
            check(CheckKind.HARDWARE_ADDRESS, CheckOutcome.PASSED, "Manufacturer-assigned address, as real routers have."),
            check(CheckKind.SECURITY, CheckOutcome.PASSED, "WPA3 Personal, same as the registry."),
            check(CheckKind.SIGNAL, CheckOutcome.PASSED, "-74 dBm, inside the normal range here (-85 to -60 dBm)."),
        ),
    )

    /** Evil twin (mockup 3): same name as [campus], locally administered BSSID, signal outside the real AP's range. */
    val twin = NetworkUi(
        ssid = "BATSTATEU-ICT",
        bssid = "02:1A:7F:33:9C:01",
        security = SecurityType.WPA2_PERSONAL,
        band = WifiBand.GHZ_2_4,
        signal = -58,
        verdict = Verdict.WARNING,
        reasons = listOf(
            WarningReason.UNREGISTERED_BSSID,
            WarningReason.LOCALLY_ADMINISTERED,
            WarningReason.RSSI_OUT_OF_RANGE,
        ),
        metrics = NetworkMetrics(2437, channel = 6, channelWidthMhz = 20, WifiStandard.WIFI_4, "[RSN-PSK-CCMP][ESS]"),
        checks = listOf(
            check(CheckKind.NETWORK_NAME, CheckOutcome.PASSED, "BATSTATEU-ICT is on the school's registry."),
            check(
                CheckKind.ACCESS_POINT, CheckOutcome.FAILED,
                "02:1A:7F:33:9C:01 is not one of the access points registered for BATSTATEU-ICT.",
            ),
            check(
                CheckKind.HARDWARE_ADDRESS, CheckOutcome.FAILED,
                "The first byte (02) marks a made-up address, typical of phone hotspots and fake routers.",
            ),
            check(CheckKind.SECURITY, CheckOutcome.PASSED, "WPA2 Personal, same as the registry."),
            check(
                CheckKind.SIGNAL, CheckOutcome.FAILED,
                "-58 dBm. The real BATSTATEU-ICT reads -55 to -40 dBm here.",
            ),
        ),
    )

    val library = NetworkUi(
        ssid = "Library Guest",
        bssid = "A4:2B:B0:11:5E:7C",
        security = SecurityType.WPA2_PERSONAL,
        band = WifiBand.GHZ_2_4,
        signal = -66,
        verdict = Verdict.UNKNOWN,
        metrics = NetworkMetrics(2412, channel = 1, channelWidthMhz = 20, WifiStandard.WIFI_4, "[WPA2-PSK-CCMP][ESS]"),
        checks = unregisteredChecks("Library Guest", "WPA2 Personal", -66),
    )

    val cafe = NetworkUi(
        ssid = "Cafe-Free-WiFi",
        bssid = "F0:9F:C2:4A:01:B3",
        security = SecurityType.OPEN,
        band = WifiBand.GHZ_2_4,
        signal = -74,
        verdict = Verdict.UNKNOWN,
        metrics = NetworkMetrics(2462, channel = 11, channelWidthMhz = 20, WifiStandard.WIFI_4, "[ESS]"),
        checks = unregisteredChecks("Cafe-Free-WiFi", "Open (no password)", -74),
    )

    /** A network that isn't in the registry: WAVE can only check the hardware address. */
    private fun unregisteredChecks(ssid: String, security: String, signal: Int) = listOf(
        check(CheckKind.NETWORK_NAME, CheckOutcome.UNKNOWN, "$ssid isn't on the school's registry, so WAVE has nothing to compare it with."),
        check(CheckKind.ACCESS_POINT, CheckOutcome.SKIPPED, "No registered access points to compare with."),
        check(CheckKind.HARDWARE_ADDRESS, CheckOutcome.PASSED, "Manufacturer-assigned address, as real routers have."),
        check(CheckKind.SECURITY, CheckOutcome.SKIPPED, "Uses $security. No registry entry to compare with."),
        check(CheckKind.SIGNAL, CheckOutcome.SKIPPED, "$signal dBm. No normal range on record for this network."),
    )

    val registry = RegistryInfo(source = "offline registry", updatedAt = "today")

    const val SAMPLE_QR_PAYLOAD = "WIFI:T:WPA;S:BATSTATEU-ICT;P:********;;SIG:MEUCIQ…"

    fun qr(result: QrResult): QrVerification = when (result) {
        QrResult.VALID -> QrVerification(result, campus, "BatStateU ICT Office", "Dec 31, 2026", SAMPLE_QR_PAYLOAD)
        QrResult.BAD_SIGNATURE -> QrVerification(
            result,
            campus.copy(
                bssid = "unknown", security = SecurityType.OPEN, verdict = Verdict.WARNING,
                metrics = null, checks = emptyList(), // a QR code has no radio readings
            ),
            "BatStateU ICT Office",
            "Dec 31, 2026",
            "WIFI:T:nopass;S:BATSTATEU-ICT;;SIG:MEUCIQ…",
        )
        QrResult.EXPIRED -> QrVerification(result, campus, "BatStateU ICT Office", "Jun 30, 2026", SAMPLE_QR_PAYLOAD)
        QrResult.UNSIGNED -> QrVerification(result, library, null, null, "WIFI:T:WPA;S:Library Guest;P:********;;")
        QrResult.UNKNOWN_KEY -> QrVerification(result, cafe, "Unknown signer", null, "WIFI:T:nopass;S:Cafe-Free-WiFi;;SIG:MEYCIQ…")
        QrResult.MALFORMED -> QrVerification(result, null, null, null, "https://example.com/promo")
    }
}
