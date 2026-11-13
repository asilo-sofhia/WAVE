package com.example.wave.domain

import com.example.wave.data.fake.FakeFixtures
import com.example.wave.data.wifi.WifiScanRepository
import com.example.wave.data.wifi.bandForFrequency
import com.example.wave.data.wifi.channelForFrequency
import com.example.wave.data.wifi.normalizeBssid
import com.example.wave.data.wifi.securityFromCapabilities
import com.example.wave.model.CheckKind
import com.example.wave.model.CheckOutcome
import com.example.wave.model.NetworkUi
import com.example.wave.model.Registry
import com.example.wave.model.RegistryAp
import com.example.wave.model.RegistryNetwork
import com.example.wave.model.ScanObservation
import com.example.wave.model.SecurityType
import com.example.wave.model.Verdict
import com.example.wave.model.WarningReason
import com.example.wave.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RegistryClassifierTest {

    private val classifier = RegistryClassifier()

    private fun obs(
        ssid: String = "BSU-ICT-TESTLAB",
        bssid: String = "00:00:5e:00:53:01",
        caps: String = "[RSN-PSK-CCMP][ESS]",
        freq: Int = 2437,
        rssi: Int = -50,
    ) = ScanObservation(
        ssid = ssid, bssid = bssid, capabilities = caps, security = securityFromCapabilities(caps),
        band = bandForFrequency(freq), channel = channelForFrequency(freq), frequencyMhz = freq,
        channelWidthMhz = 20, wifiStandard = null, rssi = rssi, timestampMillis = 1_000L,
    )

    /** Same content as backend/registry/registry.json. */
    private val registry = Registry(
        schema = "wave-registry/1", version = 1, keyId = "wave-2026-a",
        issuedAt = Instant.parse("2026-11-10T00:00:00Z"), expiresAt = Instant.parse("2026-12-10T00:00:00Z"),
        campusAsns = emptyList(),
        networks = listOf(
            RegistryNetwork(
                "BSU-ICT-TESTLAB", SecurityType.WPA2_PERSONAL,
                listOf(
                    RegistryAp("00:00:5e:00:53:01", WifiBand.GHZ_2_4, 6, "CICS Lab", -85, -25),
                    RegistryAp("00:00:5e:00:53:02", WifiBand.GHZ_5, 36, "CICS Lab", -85, -25),
                ),
            ),
        ),
    )

    private fun NetworkUi.outcome(kind: CheckKind) = checks.first { it.kind == kind }.outcome

    @Test
    fun noRegistryMeansUnknownWithRealMetrics() {
        val n = classifier.classify(obs(), registry = null)
        assertEquals(Verdict.UNKNOWN, n.verdict)
        assertEquals(CheckOutcome.UNKNOWN, n.outcome(CheckKind.NETWORK_NAME))
        assertEquals(CheckOutcome.PASSED, n.outcome(CheckKind.HARDWARE_ADDRESS))
        assertEquals(2437, n.metrics?.frequencyMhz)
        assertEquals(6, n.metrics?.channel)
        assertEquals("[RSN-PSK-CCMP][ESS]", n.metrics?.capabilities)
        assertTrue(n.reasons.isEmpty())
    }

    @Test
    fun registeredApThatMatchesIsVerified() {
        val n = classifier.classify(obs(), registry)
        assertEquals(Verdict.VERIFIED, n.verdict)
        assertTrue(n.checks.all { it.outcome == CheckOutcome.PASSED })
    }

    @Test
    fun evilTwinIsWarningWithReasons() {
        // Registry range -85..-25 dBm plus a 5 dB margin: anything above -20 dBm is unusual.
        val twin = classifier.classify(obs(bssid = "02:1a:7f:33:9c:01", rssi = -10), registry)
        assertEquals(Verdict.WARNING, twin.verdict)
        assertEquals(
            listOf(WarningReason.UNREGISTERED_BSSID, WarningReason.LOCALLY_ADMINISTERED, WarningReason.RSSI_OUT_OF_RANGE),
            twin.reasons,
        )
    }

    @Test
    fun securityMismatchIsWarning() {
        val n = classifier.classify(obs(caps = "[ESS]"), registry)
        assertEquals(Verdict.WARNING, n.verdict)
        assertEquals(listOf(WarningReason.SECURITY_MISMATCH), n.reasons)
    }

    @Test
    fun unregisteredHotspotIsUnknownNotWarning() {
        val hotspot = classifier.classify(obs(ssid = "Juan's iPhone", bssid = "da:a1:19:00:00:01"), registry)
        assertEquals(Verdict.UNKNOWN, hotspot.verdict)
        assertEquals(CheckOutcome.UNKNOWN, hotspot.outcome(CheckKind.HARDWARE_ADDRESS))
        assertTrue(hotspot.reasons.isEmpty())
    }

    @Test
    fun hiddenNetworkGetsAName() {
        val n = classifier.classify(obs(ssid = ""), registry)
        assertEquals(RegistryClassifier.HIDDEN_NAME, n.ssid)
        assertEquals(Verdict.UNKNOWN, n.verdict)
    }

    /** The real rules, fed a registry built from the fake fixtures, reach the same verdicts the fakes show. */
    @Test
    fun realRulesMatchTheFakeFixtures() {
        val fixtureRegistry = registry.copy(
            networks = listOf(
                RegistryNetwork(
                    "BATSTATEU-ICT", SecurityType.WPA2_PERSONAL,
                    listOf(RegistryAp(normalizeBssid(FakeFixtures.campus.bssid), WifiBand.GHZ_5, 36, "Main Building", -55, -40)),
                ),
                RegistryNetwork(
                    "CICS-LAB-5G", SecurityType.WPA3_PERSONAL,
                    listOf(RegistryAp(normalizeBssid(FakeFixtures.lab.bssid), WifiBand.GHZ_5, 149, "CICS Building", -85, -60)),
                ),
            ),
        )
        val strict = RegistryClassifier(rssiMarginDb = 0)
        for (f in listOf(FakeFixtures.campus, FakeFixtures.lab, FakeFixtures.twin, FakeFixtures.library, FakeFixtures.cafe)) {
            val m = f.metrics!!
            val o = obs(f.ssid, normalizeBssid(f.bssid), m.capabilities, m.frequencyMhz, f.signal)
            val real = strict.classify(o, fixtureRegistry)
            assertEquals(f.ssid, f.verdict, real.verdict)
            assertEquals(f.ssid, f.reasons, real.reasons)
            assertEquals(f.ssid, f.checks.map { it.outcome }, real.checks.map { it.outcome })
        }
    }

    @Test
    fun listSortKeepsTwinUnderTheRealNetwork() {
        val sorted = WifiScanRepository.sortForList(
            listOf(FakeFixtures.library, FakeFixtures.twin, FakeFixtures.lab, FakeFixtures.campus),
        )
        assertEquals(
            listOf(FakeFixtures.campus, FakeFixtures.twin, FakeFixtures.library, FakeFixtures.lab),
            sorted,
        )
    }
}
