package com.example.wave.domain

import com.example.wave.model.NetworkUi
import com.example.wave.model.QrResult
import com.example.wave.model.Verdict

/**
 * Whether to join the network a scanned QR code describes. The code alone isn't enough (CONTRACT §3 security
 * note: a genuine code can be stuck next to a fake router), so WAVE first looks at the access points actually
 * broadcasting that name nearby, judged against the registry.
 */
sealed interface QrJoinAdvice {
    /** The code itself must not be used: changed, expired or not a Wi-Fi code. */
    data object CodeUnsafe : QrJoinAdvice

    /** Nothing nearby broadcasts this name. */
    data object NotInRange : QrJoinAdvice

    /** A Verified access point is nearby. [pinBssid] is set when fakes share the name, so only the real one is joined. */
    data class Connect(val accessPoint: NetworkUi, val pinBssid: String?) : QrJoinAdvice

    /** Only fake copies (Warning) broadcast this name here. */
    data class FakeNearby(val accessPoint: NetworkUi) : QrJoinAdvice

    /** Only unregistered access points: joining is possible but WAVE can't vouch for them. */
    data class Unverified(val accessPoint: NetworkUi) : QrJoinAdvice

    companion object {
        /** [nearby]: access points broadcasting the code's network name, from a fresh scan. */
        fun of(result: QrResult, ssid: String?, nearby: List<NetworkUi>): QrJoinAdvice {
            if (result == QrResult.BAD_SIGNATURE || result == QrResult.EXPIRED || result == QrResult.MALFORMED) return CodeUnsafe
            if (ssid.isNullOrEmpty()) return CodeUnsafe
            val named = nearby.filter { it.ssid == ssid }
            if (named.isEmpty()) return NotInRange
            val verified = named.filter { it.verdict == Verdict.VERIFIED }.maxByOrNull { it.signal }
            val fakes = named.filter { it.verdict == Verdict.WARNING }
            return when {
                verified != null -> Connect(verified, pinBssid = verified.bssid.takeIf { fakes.isNotEmpty() })
                fakes.isNotEmpty() -> FakeNearby(fakes.maxBy { it.signal })
                else -> Unverified(named.maxBy { it.signal })
            }
        }
    }
}
