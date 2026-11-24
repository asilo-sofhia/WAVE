package com.example.wave.domain

import com.example.wave.model.NetworkUi
import com.example.wave.model.QrResult
import com.example.wave.model.SecurityType
import com.example.wave.model.Verdict

/**
 * Whether to join the network a scanned QR code describes. The code alone isn't enough (CONTRACT §3 security
 * note: a genuine code can be stuck next to a fake router), so WAVE first looks at the access points actually
 * broadcasting that name nearby, judged against the registry and against what the code itself says.
 */
sealed interface QrJoinAdvice {
    /** The code itself must not be used: changed, expired or not a Wi-Fi code. */
    data object CodeUnsafe : QrJoinAdvice

    /** Nothing nearby broadcasts this name. */
    data object NotInRange : QrJoinAdvice

    /** A Verified access point is nearby. [pinBssid] is set when fakes share the name, so only the real one is joined. */
    data class Connect(val accessPoint: NetworkUi, val pinBssid: String?) : QrJoinAdvice

    /** Only possible evil twins broadcast this name here; [why] says what gave the copy away. */
    data class FakeNearby(val accessPoint: NetworkUi, val why: FakeReason = FakeReason.REGISTRY) : QrJoinAdvice

    /** Only unregistered access points: joining is possible but WAVE can't vouch for them. */
    data class Unverified(val accessPoint: NetworkUi) : QrJoinAdvice

    /** What makes an access point look like an evil twin of the code's network. */
    enum class FakeReason {
        /** The registry check failed (Warning): wrong access point, security or signal for a registered name. */
        REGISTRY,

        /** The code has a password but this copy is open: the classic evil twin, no registry needed. */
        OPEN_COPY,

        /** The code says WPA3-only but this copy offers older WPA2: a downgrade, no registry needed. */
        DOWNGRADE,
    }

    companion object {
        /**
         * [nearby]: access points broadcasting the code's network name, from a fresh scan.
         * [codeSecurity]: the security the code asks for (its T field), used to spot copies that differ from it.
         */
        fun of(result: QrResult, ssid: String?, nearby: List<NetworkUi>, codeSecurity: SecurityType? = null): QrJoinAdvice {
            if (result == QrResult.BAD_SIGNATURE || result == QrResult.EXPIRED || result == QrResult.MALFORMED) return CodeUnsafe
            if (ssid.isNullOrEmpty()) return CodeUnsafe
            val named = nearby.filter { it.ssid == ssid }
            if (named.isEmpty()) return NotInRange
            val verified = named.filter { it.verdict == Verdict.VERIFIED }.maxByOrNull { it.signal }
            // A registry-verified access point is trusted over the code (the code may be out of date).
            val fakes = named.filter { it.verdict != Verdict.VERIFIED }
                .mapNotNull { ap -> fakeReason(ap, codeSecurity)?.let { ap to it } }
            return when {
                verified != null -> Connect(verified, pinBssid = verified.bssid.takeIf { fakes.isNotEmpty() })
                fakes.isNotEmpty() -> fakes.maxBy { it.first.signal }.let { (ap, why) -> FakeNearby(ap, why) }
                else -> Unverified(named.maxBy { it.signal })
            }
        }

        private fun fakeReason(ap: NetworkUi, codeSecurity: SecurityType?): FakeReason? = when {
            ap.verdict == Verdict.WARNING -> FakeReason.REGISTRY
            codeSecurity != null && codeSecurity != SecurityType.OPEN && ap.security == SecurityType.OPEN -> FakeReason.OPEN_COPY
            codeSecurity == SecurityType.WPA3_PERSONAL && ap.security == SecurityType.WPA2_PERSONAL -> FakeReason.DOWNGRADE
            else -> null
        }
    }
}
