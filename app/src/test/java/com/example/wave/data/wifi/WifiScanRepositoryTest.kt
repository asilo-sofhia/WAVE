package com.example.wave.data.wifi

import com.example.wave.data.RegistryState
import com.example.wave.data.ScanBatch
import com.example.wave.data.WifiConnection
import com.example.wave.data.WifiScanner
import com.example.wave.domain.RegistryClassifier
import com.example.wave.model.NetworkUi
import com.example.wave.model.Registry
import com.example.wave.model.RegistryAp
import com.example.wave.model.RegistryNetwork
import com.example.wave.model.ScanObservation
import com.example.wave.model.SecurityType
import com.example.wave.model.Verdict
import com.example.wave.model.WifiBand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** The connected network is judged against the registry and keeps its readings live. */
class WifiScanRepositoryTest {

    private val scope = CoroutineScope(Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private class FakeScanner : WifiScanner {
        override val connection = MutableStateFlow<WifiConnection?>(null)
        override val latest = MutableStateFlow<ScanBatch?>(null)
        override suspend fun scan(): ScanBatch = latest.value ?: ScanBatch(emptyList(), 0, fresh = false)
    }

    /** Same shape as backend/registry/registry.home-dev.json. */
    private val home = Registry(
        schema = "wave-registry/1", version = 1, keyId = "wave-dev",
        issuedAt = Instant.parse("2026-11-10T00:00:00Z"), expiresAt = Instant.parse("2026-12-31T00:00:00Z"),
        campusAsns = emptyList(),
        networks = listOf(
            RegistryNetwork(
                "GFiber_8C809", SecurityType.WPA2_PERSONAL,
                listOf(RegistryAp("d8:42:f7:78:c8:0d", WifiBand.GHZ_2_4, 12, "Home router", -85, -30)),
            ),
        ),
    )

    private fun scanned(rssi: Int) = ScanObservation(
        ssid = "GFiber_8C809", bssid = "d8:42:f7:78:c8:0d", capabilities = "[RSN-PSK-CCMP][ESS]",
        security = SecurityType.WPA2_PERSONAL, band = WifiBand.GHZ_2_4, channel = 12, frequencyMhz = 2467,
        channelWidthMhz = 20, wifiStandard = null, rssi = rssi, timestampMillis = 0,
    )

    private fun connected(rssi: Int) = WifiConnection(
        ssid = "GFiber_8C809", bssid = "d8:42:f7:78:c8:0d", frequencyMhz = 2467, rssi = rssi,
        capabilitiesHint = "[RSN-PSK-CCMP]", linkSpeedMbps = 144, ipAddress = "192.168.1.23",
        gateway = "192.168.1.1", dnsServers = listOf("192.168.1.1"),
    )

    private fun repo(scanner: FakeScanner, registry: StateFlow<RegistryState>) =
        WifiScanRepository(scanner, registry, RegistryClassifier(), scope)

    private fun StateFlow<NetworkUi?>.awaitFirst(predicate: (NetworkUi?) -> Boolean) =
        runBlocking { withTimeout(2_000) { first(predicate) } }

    @Test
    fun registeredConnectionIsVerifiedWithLiveDetails() {
        val scanner = FakeScanner().apply {
            latest.value = ScanBatch(listOf(scanned(rssi = -70)), 0, fresh = true)
            connection.value = connected(rssi = -56)
        }
        val repo = repo(scanner, MutableStateFlow(RegistryState.Ready(home)))

        val network = repo.connection.awaitFirst { it != null }!!
        assertEquals(Verdict.VERIFIED, network.verdict)
        assertEquals(-56, network.signal) // live WifiInfo reading, not the older scan's -70
        assertEquals("192.168.1.23", network.connection?.ipAddress)
        assertEquals(144, network.connection?.linkSpeedMbps)

        scanner.connection.value = connected(rssi = -61) // signal changes while connected
        assertEquals(-61, repo.connection.awaitFirst { it?.signal == -61 }!!.signal)
        assertEquals(Verdict.VERIFIED, repo.connection.value!!.verdict)
    }

    @Test
    fun withoutAVerifiedRegistryTheSameConnectionIsUnknown() {
        val scanner = FakeScanner().apply { connection.value = connected(rssi = -56) }
        val repo = repo(scanner, MutableStateFlow(RegistryState.Missing("signature doesn't match the file")))
        assertEquals(Verdict.UNKNOWN, repo.connection.awaitFirst { it != null }!!.verdict)
    }

    @Test
    fun hiddenBssidMeansNoConnectionToJudge() {
        val scanner = FakeScanner().apply { connection.value = connected(-56).copy(bssid = null) }
        val repo = repo(scanner, MutableStateFlow(RegistryState.Ready(home)))
        runBlocking { kotlinx.coroutines.delay(200) }
        assertNull(repo.connection.value)
    }
}
