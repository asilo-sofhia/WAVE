package com.example.wave.data.registry

import com.example.wave.data.RegistryRepository
import com.example.wave.data.RegistryState
import com.example.wave.data.wifi.bandForFrequency
import com.example.wave.data.wifi.channelForFrequency
import com.example.wave.data.wifi.securityFromCapabilities
import com.example.wave.domain.RegistryClassifier
import com.example.wave.model.BlockedAp
import com.example.wave.model.CampusAsn
import com.example.wave.model.Registry
import com.example.wave.model.RegistryAp
import com.example.wave.model.RegistryNetwork
import com.example.wave.model.ScanObservation
import com.example.wave.model.SecurityType
import com.example.wave.model.Verdict
import com.example.wave.model.WifiBand
import com.example.wave.verify.PlaceList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CombinedRegistryTest {
    private val t0: Instant = Instant.parse("2026-11-24T00:00:00Z")

    private fun ap(bssid: String) = RegistryAp(bssid, WifiBand.GHZ_5, 36, "Main", -85, -30)

    private fun reg(
        version: Int,
        networks: List<RegistryNetwork> = emptyList(),
        blocked: List<BlockedAp> = emptyList(),
        asns: List<CampusAsn> = emptyList(),
        key: String = "k$version",
    ) = Registry("wave-registry/1", version, key, t0, t0.plusSeconds(86_400L * version), asns, networks, blocked)

    private fun net(ssid: String, vararg bssids: String, security: SecurityType = SecurityType.WPA2_PERSONAL) =
        RegistryNetwork(ssid, security, bssids.map(::ap))

    @Test
    fun joinsSameNetworkAcrossPlaces() {
        val merged = mergeRegistries(
            reg(3, listOf(net("BSU-WiFi", "00:00:5e:00:53:01"))),
            listOf(
                reg(2, listOf(net("BSU-WiFi", "00:00:5e:00:53:02", "00:00:5e:00:53:01"))),
                reg(1, listOf(net("BSU-WiFi", "00:00:5e:00:53:03", security = SecurityType.WPA3_PERSONAL))),
            ),
        )!!
        assertEquals(2, merged.networks.size)
        val wpa2 = merged.networks.first { it.security == SecurityType.WPA2_PERSONAL }
        assertEquals(listOf("00:00:5e:00:53:01", "00:00:5e:00:53:02"), wpa2.aps.map { it.bssid })
    }

    @Test
    fun approvalBeatsAnotherPlacesBlock() {
        val merged = mergeRegistries(
            null,
            listOf(
                reg(1, blocked = listOf(BlockedAp("00:00:5e:00:53:02", "SM-WiFi", "not ours"), BlockedAp("00:00:5e:00:53:09", "x", "fake"))),
                reg(1, listOf(net("SM-WiFi", "00:00:5e:00:53:02"))),
            ),
        )!!
        assertEquals(listOf("00:00:5e:00:53:09"), merged.blocked.map { it.bssid })
    }

    @Test
    fun asnsAreJoined() {
        val merged = mergeRegistries(reg(1, asns = listOf(CampusAsn(9299, "PLDT"))), listOf(reg(1, asns = listOf(CampusAsn(9299, "PLDT"), CampusAsn(4775, "Globe")))))!!
        assertEquals(listOf(9299, 4775), merged.campusAsns.map { it.asn })
    }

    @Test
    fun versionIsTheSum() {
        assertEquals(10, mergeRegistries(reg(3), listOf(reg(2), reg(5)))!!.version)
        assertEquals(7, mergeRegistries(null, listOf(reg(2), reg(5)))!!.version)
        assertEquals("k3", mergeRegistries(reg(3), listOf(reg(2)))!!.keyId)
        assertEquals("places", mergeRegistries(null, listOf(reg(2)))!!.keyId)
    }

    @Test
    fun nothingIsNull() {
        assertNull(mergeRegistries(null, emptyList()))
    }

    private class FakeCampus(initial: RegistryState) : RegistryRepository {
        override val state = MutableStateFlow(initial)
        var refreshed = 0
        override suspend fun refresh(): RegistryState { refreshed++; return state.value }
    }

    private fun place(id: String, r: Registry) = PlaceList(id, id, r, "{}")

    @Test
    fun combinedStateFollowsBoth() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            val campus = FakeCampus(RegistryState.Missing("no registry yet"))
            val places = MutableStateFlow<Map<String, PlaceList>>(emptyMap())
            var placesRefreshed = 0
            val combined = CombinedRegistryRepository(campus, places, { placesRefreshed++ }, scope)
            suspend fun await(p: (RegistryState) -> Boolean) = withTimeout(2_000) { combined.state.first(p) }

            assertEquals(RegistryState.Missing("no registry yet"), await { it is RegistryState.Missing })
            places.value = mapOf("x" to place("x", reg(2, listOf(net("X", "00:00:5e:00:53:02")))))
            assertTrue(await { it is RegistryState.Ready } is RegistryState.Ready)
            campus.state.value = RegistryState.Stale(reg(3))
            val stale = await { it is RegistryState.Stale } as RegistryState.Stale
            assertEquals(5, stale.registry.version)
            combined.refresh()
            assertEquals(1, campus.refreshed)
            assertEquals(1, placesRefreshed)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun classifierVerifiesAPlaceNetwork() {
        val merged = mergeRegistries(null, listOf(reg(1, listOf(net("SM-WiFi", "00:00:5e:00:53:02")))))
        fun obs(bssid: String) = ScanObservation(
            ssid = "SM-WiFi", bssid = bssid, capabilities = "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]",
            security = securityFromCapabilities("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"),
            band = bandForFrequency(5180), channel = channelForFrequency(5180), frequencyMhz = 5180,
            channelWidthMhz = 20, wifiStandard = null, rssi = -50, timestampMillis = 1_000L,
        )
        val classifier = RegistryClassifier()
        assertEquals(Verdict.VERIFIED, classifier.classify(obs("00:00:5e:00:53:02"), merged).verdict)
        assertEquals(Verdict.WARNING, classifier.classify(obs("00:00:5e:00:53:66"), merged).verdict)
    }
}
