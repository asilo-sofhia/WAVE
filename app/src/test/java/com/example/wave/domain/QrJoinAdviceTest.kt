package com.example.wave.domain

import com.example.wave.data.RegistryState
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.data.qr.SignedQrVerifier
import com.example.wave.model.QrResult
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Scan the code, check the network nearby, then decide." */
class QrJoinAdviceTest {

    private val ssid = FakeFixtures.campus.ssid // BATSTATEU-ICT: campus = Verified, twin = Warning (same name)

    @Test
    fun aChangedExpiredOrUnreadableCodeIsNeverUsed() {
        for (r in listOf(QrResult.BAD_SIGNATURE, QrResult.EXPIRED, QrResult.MALFORMED)) {
            assertEquals(r.name, QrJoinAdvice.CodeUnsafe, QrJoinAdvice.of(r, ssid, listOf(FakeFixtures.campus)))
        }
        assertEquals(QrJoinAdvice.CodeUnsafe, QrJoinAdvice.of(QrResult.VALID, null, listOf(FakeFixtures.campus)))
    }

    @Test
    fun nothingNearbyMeansNotInRange() {
        assertEquals(QrJoinAdvice.NotInRange, QrJoinAdvice.of(QrResult.VALID, ssid, emptyList()))
        assertEquals(QrJoinAdvice.NotInRange, QrJoinAdvice.of(QrResult.VALID, ssid, listOf(FakeFixtures.lab)))
    }

    @Test
    fun aVerifiedAccessPointCanBeJoinedEvenFromAnUnsignedCode() {
        val a = QrJoinAdvice.of(QrResult.UNSIGNED, ssid, listOf(FakeFixtures.campus))
        assertEquals(QrJoinAdvice.Connect(FakeFixtures.campus, pinBssid = null), a)
    }

    @Test
    fun withAFakeNearbyTheJoinIsPinnedToTheVerifiedAccessPoint() {
        val a = QrJoinAdvice.of(QrResult.VALID, ssid, listOf(FakeFixtures.twin, FakeFixtures.campus))
        assertEquals(QrJoinAdvice.Connect(FakeFixtures.campus, pinBssid = FakeFixtures.campus.bssid), a)
    }

    @Test
    fun onlyAFakeNearbyMeansDontConnect() {
        val a = QrJoinAdvice.of(QrResult.VALID, ssid, listOf(FakeFixtures.twin))
        assertEquals(QrJoinAdvice.FakeNearby(FakeFixtures.twin), a)
    }

    @Test
    fun anUnregisteredNetworkIsUnverified() {
        val a = QrJoinAdvice.of(QrResult.UNSIGNED, FakeFixtures.library.ssid, listOf(FakeFixtures.library))
        assertEquals(QrJoinAdvice.Unverified(FakeFixtures.library), a)
    }

    @Test
    fun anOpenCopyOfAProtectedNetworkIsAnEvilTwinEvenWithoutARegistry() {
        val real = FakeFixtures.library // Unknown (not registered), WPA2
        val openCopy = real.copy(bssid = "02:11:22:33:44:55", security = SecurityType.OPEN, signal = -40)
        val a = QrJoinAdvice.of(QrResult.UNSIGNED, real.ssid, listOf(real, openCopy), SecurityType.WPA2_PERSONAL)
        assertEquals(QrJoinAdvice.FakeNearby(openCopy, QrJoinAdvice.FakeReason.OPEN_COPY), a)
        // Same scan, but the code itself is for an open network: nothing suspicious.
        assertEquals(
            QrJoinAdvice.Unverified(openCopy),
            QrJoinAdvice.of(QrResult.UNSIGNED, real.ssid, listOf(real, openCopy), SecurityType.OPEN),
        )
    }

    @Test
    fun aWpa2CopyOfAWpa3OnlyNetworkIsADowngrade() {
        val wpa2 = FakeFixtures.library.copy(security = SecurityType.WPA2_PERSONAL)
        val a = QrJoinAdvice.of(QrResult.UNSIGNED, wpa2.ssid, listOf(wpa2), SecurityType.WPA3_PERSONAL)
        assertEquals(QrJoinAdvice.FakeNearby(wpa2, QrJoinAdvice.FakeReason.DOWNGRADE), a)
    }

    @Test
    fun aRegistryVerifiedAccessPointIsTrustedOverTheCodesSecurity() {
        val a = QrJoinAdvice.of(QrResult.UNSIGNED, ssid, listOf(FakeFixtures.campus), SecurityType.WPA3_PERSONAL)
        assertEquals(QrJoinAdvice.Connect(FakeFixtures.campus, pinBssid = null), a)
    }

    @Test
    fun looselyWrittenRouterCodesAreStillWifiCodes() = runBlocking {
        val verifier = SignedQrVerifier(MutableStateFlow(RegistryState.Missing()))
        // No final ";;", unescaped ':' in the password.
        val loose = verifier.verify("WIFI:S:HomeNet;T:WPA;P:pa:ss;")
        assertEquals(QrResult.UNSIGNED, loose.result)
        assertEquals("HomeNet", loose.credentials!!.ssid)
        assertEquals("pa:ss", loose.credentials!!.password)
        assertFalse("password must be masked", loose.rawPayload.contains("pa:ss"))
        // Lowercase prefix and type, password last without ';'.
        val lower = verifier.verify("wifi:T:wpa2;S:Cafe;P:secret123")
        assertEquals(QrResult.UNSIGNED, lower.result)
        assertEquals(WifiAuth.WPA, lower.credentials!!.auth)
        assertFalse("password must be masked", lower.rawPayload.contains("secret123"))
        // Open network written without a T field.
        assertEquals(SecurityType.OPEN, verifier.verify("WIFI:S:Lobby;;").network!!.security)
        // Still not Wi-Fi codes.
        assertEquals(QrResult.MALFORMED, verifier.verify("https://example.com/free-wifi").result)
        assertNull(verifier.verify("WIFI:T:WPA;P:nossid;;").network) // no network name: shown as an invalid QR
        // A damaged WAVE-signed code is never read loosely.
        assertEquals(QrResult.MALFORMED, verifier.verify("WIFI:T:WPA;S:X;WAVE-KID:k;WAVE-UNTIL:20261210;WAVE-SIG:short;;").result)
    }

    @Test
    fun credentialsComeOnlyFromCodesThatMayBeUsed() = runBlocking {
        val verifier = SignedQrVerifier(MutableStateFlow(RegistryState.Missing()))
        // An ordinary router / "Share Wi-Fi" code: unsigned, any field order.
        val shared = verifier.verify("WIFI:S:Home\\;Net;T:WPA;P:pass\\:word1;H:false;;")
        assertEquals(QrResult.UNSIGNED, shared.result)
        val c = shared.credentials!!
        assertEquals("Home;Net", c.ssid)
        assertEquals("pass:word1", c.password)
        assertEquals(WifiAuth.WPA, c.auth)
        assertFalse(c.hidden)
        assertFalse("password must not leak via toString", c.toString().contains("pass:word1"))

        val open = verifier.verify("WIFI:T:nopass;S:Cafe;;").credentials!!
        assertEquals(WifiAuth.OPEN, open.auth)
        assertNull(open.password)

        assertEquals(WifiAuth.OTHER, verifier.verify("WIFI:T:WEP;S:Old;P:12345;;").credentials!!.auth)
        assertNull(verifier.verify("https://example.com/free-wifi").credentials) // not Wi-Fi
        assertTrue(verifier.verify("WIFI:T:SAE;S:New;P:longpassword;H:true;;").credentials!!.hidden)
    }
}
