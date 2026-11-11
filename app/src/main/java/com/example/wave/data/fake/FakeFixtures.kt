package com.example.wave.data.fake

import com.example.wave.model.NetworkUi
import com.example.wave.model.QrResult
import com.example.wave.model.QrVerification
import com.example.wave.model.RegistryInfo
import com.example.wave.model.SecurityType
import com.example.wave.model.Verdict
import com.example.wave.model.WarningReason
import com.example.wave.model.WifiBand

/** Fake networks used by the Fake* implementations and by @Preview functions. */
object FakeFixtures {
    val campus = NetworkUi(
        ssid = "BATSTATEU-ICT",
        bssid = "8C:3B:AD:72:10:4F",
        security = SecurityType.WPA2_PERSONAL,
        band = WifiBand.GHZ_5,
        signal = -54,
        verdict = Verdict.VERIFIED,
    )

    val lab = NetworkUi(
        ssid = "CICS-LAB-5G",
        bssid = "8C:3B:AD:72:22:91",
        security = SecurityType.WPA3_PERSONAL,
        band = WifiBand.GHZ_5,
        signal = -70,
        verdict = Verdict.VERIFIED,
    )

    /** Evil twin: same name as [campus], locally administered BSSID, open, suspiciously strong. */
    val twin = NetworkUi(
        ssid = "BATSTATEU-ICT",
        bssid = "02:1A:7F:33:9C:01",
        security = SecurityType.OPEN,
        band = WifiBand.GHZ_2_4,
        signal = -38,
        verdict = Verdict.WARNING,
        reasons = listOf(
            WarningReason.UNREGISTERED_BSSID,
            WarningReason.LOCALLY_ADMINISTERED,
            WarningReason.SECURITY_MISMATCH,
            WarningReason.RSSI_OUT_OF_RANGE,
        ),
    )

    val library = NetworkUi(
        ssid = "Library Guest",
        bssid = "A4:2B:B0:11:5E:7C",
        security = SecurityType.WPA2_PERSONAL,
        band = WifiBand.GHZ_2_4,
        signal = -63,
        verdict = Verdict.UNKNOWN,
    )

    val cafe = NetworkUi(
        ssid = "Cafe-Free-WiFi",
        bssid = "F0:9F:C2:4A:01:B3",
        security = SecurityType.OPEN,
        band = WifiBand.GHZ_2_4,
        signal = -74,
        verdict = Verdict.UNKNOWN,
    )

    val registry = RegistryInfo(source = "Offline registry", updatedAt = "today")

    const val SAMPLE_QR_PAYLOAD = "WIFI:T:WPA;S:BATSTATEU-ICT;P:********;;SIG:MEUCIQ…"

    fun qr(result: QrResult): QrVerification = when (result) {
        QrResult.VALID -> QrVerification(result, campus, "BatStateU ICT Office", "Dec 31, 2026", SAMPLE_QR_PAYLOAD)
        QrResult.BAD_SIGNATURE -> QrVerification(
            result,
            campus.copy(bssid = "unknown", security = SecurityType.OPEN, verdict = Verdict.WARNING),
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
