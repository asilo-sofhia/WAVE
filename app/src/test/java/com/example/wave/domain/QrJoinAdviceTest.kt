package com.example.wave.domain

import com.example.wave.data.RegistryState
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.data.qr.SignedQrVerifier
import com.example.wave.model.QrResult
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
