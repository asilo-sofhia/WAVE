package com.example.wave.data.qr

import com.example.wave.data.QrVerifier
import com.example.wave.data.RegistryState
import com.example.wave.data.registryOrNull
import com.example.wave.model.NetworkUi
import com.example.wave.model.QrResult
import com.example.wave.model.QrVerification
import com.example.wave.model.Registry
import com.example.wave.model.SecurityType
import com.example.wave.model.Verdict
import com.example.wave.model.WifiAuth
import com.example.wave.model.WifiCredentials
import com.example.wave.model.WifiBand
import com.example.wave.verify.WaveKeys
import com.example.wave.verify.WifiQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The real QrVerifier: checks the signature (CONTRACT §3, [WifiQr]) and then looks the network up in the signed
 * registry. A valid signature only proves the CODE is genuine; the access point still has to be checked on
 * Networks (CONTRACT §3 security note).
 */
class SignedQrVerifier(
    private val registry: StateFlow<RegistryState>,
    private val today: () -> LocalDate = LocalDate::now,
    private val trustedKeys: Map<String, String> = WaveKeys.trusted,
) : QrVerifier {

    override suspend fun verify(payload: String): QrVerification = withContext(Dispatchers.Default) {
        val check = WifiQr.verify(payload.trim(), trustedKeys, today())
        val reg = registry.value.registryOrNull
        QrVerification(
            result = check.result,
            network = networkFrom(check, reg),
            signer = check.keyId,
            expiresAt = check.until?.format(DISPLAY_DATE),
            rawPayload = WifiQr.maskPassword(payload), // never show the Wi-Fi password on screen
            credentials = credentialsFrom(check),
            inRegistry = reg?.let { r -> check.ssid?.let { s -> r.networks.any { it.ssid == s && qrTypeMatches(check.type, it.security) } } },
        )
    }

    /** Only codes that may be used: genuine, or ordinary unsigned ones. Changed / expired / unreadable -> null. */
    private fun credentialsFrom(check: WifiQr.Check): WifiCredentials? {
        if (check.result !in setOf(QrResult.VALID, QrResult.UNSIGNED, QrResult.UNKNOWN_KEY)) return null
        val ssid = check.ssid?.takeIf { it.isNotEmpty() } ?: return null
        val auth = when (check.type) {
            "nopass", null -> if (check.fields["P"].isNullOrEmpty()) WifiAuth.OPEN else WifiAuth.WPA
            "WPA" -> WifiAuth.WPA
            "SAE" -> WifiAuth.SAE
            else -> WifiAuth.OTHER // e.g. WEP: Android can't add these for apps
        }
        return WifiCredentials(ssid, check.fields["P"]?.takeIf { it.isNotEmpty() }, auth, check.fields["H"].equals("true", ignoreCase = true))
    }

    /** What the code says (name + security). Null when it isn't a readable Wi-Fi code. */
    private fun networkFrom(check: WifiQr.Check, registry: Registry?): NetworkUi? {
        val ssid = check.ssid ?: return null
        val security = when (check.type) {
            "SAE" -> SecurityType.WPA3_PERSONAL
            "nopass" -> SecurityType.OPEN
            else -> SecurityType.WPA2_PERSONAL // "WPA" = WPA2 / WPA2-WPA3 transition
        }
        val inRegistry = registry?.networks?.any { it.ssid == ssid && qrTypeMatches(check.type, it.security) } == true
        val verdict = when (check.result) {
            QrResult.VALID -> if (inRegistry) Verdict.VERIFIED else Verdict.UNKNOWN // §3: VALID + not in registry = Unknown
            QrResult.BAD_SIGNATURE -> Verdict.WARNING
            else -> Verdict.UNKNOWN
        }
        // A QR code has no radio readings: no BSSID, band or signal. Those come from Networks.
        return NetworkUi(ssid = ssid, bssid = "", security = security, band = WifiBand.GHZ_2_4, signal = 0, verdict = verdict)
    }

    /** wave_sign.py QR_TYPE_TO_SECURITY. */
    private fun qrTypeMatches(type: String?, security: SecurityType): Boolean = when (type) {
        "WPA" -> security == SecurityType.WPA2_PERSONAL || security == SecurityType.WPA2_WPA3_PERSONAL
        "SAE" -> security == SecurityType.WPA3_PERSONAL || security == SecurityType.WPA2_WPA3_PERSONAL
        "nopass" -> security == SecurityType.OPEN
        else -> false
    }

    private companion object {
        val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
    }
}
