package com.example.wave.data.wifi

import com.example.wave.data.NetworkClassifier
import com.example.wave.data.RegistryState
import com.example.wave.data.ScanBatch
import com.example.wave.data.ScanRepository
import com.example.wave.data.WifiConnection
import com.example.wave.data.WifiScanner
import com.example.wave.data.registryOrNull
import com.example.wave.domain.RegistryClassifier
import com.example.wave.model.ConnectionDetails
import com.example.wave.model.NetworkUi
import com.example.wave.model.RegistryInfo
import com.example.wave.model.ScanObservation
import com.example.wave.model.ScanResult
import com.example.wave.model.SecurityType
import com.example.wave.model.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The real ScanRepository: Android's Wi-Fi readings ([scanner]) + the school registry ([registry])
 * + the verdict rules ([classifier]). Screens see the same ScanRepository interface as with the fake.
 *
 * Only a registry whose signature checked out ever reaches [registry]; without one every network is UNKNOWN.
 * The connected network carries LIVE readings (signal, frequency, link speed, IP) that update while connected.
 */
class WifiScanRepository(
    private val scanner: WifiScanner,
    private val registry: StateFlow<RegistryState>,
    private val classifier: NetworkClassifier,
    scope: CoroutineScope,
) : ScanRepository {

    @Volatile private var lastScan: ScanResult? = null

    override val connection: StateFlow<NetworkUi?> =
        combine(scanner.connection, scanner.latest, registry) { conn, batch, reg -> classifyConnection(conn, batch, reg) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun scanNearby(): ScanResult {
        val batch = scanner.scan()
        val state = registry.value
        val networks = batch.observations
            .groupBy { it.bssid }.map { (_, same) -> same.maxBy { it.rssi } } // one row per access point
            .map { classifier.classify(it, state.registryOrNull) }
        return ScanResult(sortForList(networks), registryInfo(state), batch.scannedAtMillis).also { lastScan = it }
    }

    override suspend fun checkConnection(): NetworkUi? {
        scanner.scan()
        return classifyConnection(scanner.connection.value, scanner.latest.value, registry.value)
    }

    /** The connected network first, so its details (live) match what Home and Monitor show. */
    override fun findNetwork(bssid: String): NetworkUi? =
        (listOfNotNull(connection.value) + lastScan?.networks.orEmpty())
            .firstOrNull { it.bssid.equals(bssid, ignoreCase = true) }

    override fun accessPointsOf(network: NetworkUi): List<NetworkUi> {
        if (network.ssid == RegistryClassifier.HIDDEN_NAME) return listOf(network) // hidden names aren't comparable
        return (listOfNotNull(connection.value) + lastScan?.networks.orEmpty())
            .filter { it.ssid == network.ssid }
            .distinctBy { it.bssid.lowercase() }
            .sortedWith(compareBy({ it.verdict.ordinal }, { -it.signal }))
            .ifEmpty { listOf(network) }
    }

    /** Null when not on Wi-Fi, or when Android hides the BSSID (no location permission). */
    private fun classifyConnection(conn: WifiConnection?, batch: ScanBatch?, state: RegistryState): NetworkUi? {
        val bssid = conn?.bssid ?: return null
        val scanned = batch?.observations?.firstOrNull { it.bssid == bssid } ?: conn.toObservation(bssid, batch)
        // While connected, WifiInfo is fresher than the last scan: use its live signal and frequency.
        val freq = conn.frequencyMhz ?: scanned.frequencyMhz
        val live = scanned.copy(
            rssi = conn.rssi ?: scanned.rssi,
            frequencyMhz = freq,
            band = bandForFrequency(freq),
            channel = channelForFrequency(freq),
            wifiStandard = conn.wifiStandard ?: scanned.wifiStandard,
            timestampMillis = System.currentTimeMillis(),
        )
        return classifier.classify(live, state.registryOrNull).copy(
            connection = ConnectionDetails(
                ipAddress = conn.ipAddress,
                gateway = conn.gateway,
                dnsServers = conn.dnsServers,
                linkSpeedMbps = conn.linkSpeedMbps,
                txLinkSpeedMbps = conn.txLinkSpeedMbps,
                rxLinkSpeedMbps = conn.rxLinkSpeedMbps,
            ),
        )
    }

    /** The connected AP wasn't in the last scan: build an observation from what WifiInfo tells us. */
    private fun WifiConnection.toObservation(bssid: String, batch: ScanBatch?): ScanObservation {
        val caps = capabilitiesHint
            ?: batch?.observations?.firstOrNull { it.ssid == ssid }?.capabilities // same network, other AP
        val freq = frequencyMhz ?: 0
        return ScanObservation(
            ssid = ssid.orEmpty(),
            bssid = bssid,
            capabilities = caps.orEmpty(),
            security = caps?.let(::securityFromCapabilities) ?: SecurityType.UNSUPPORTED,
            band = bandForFrequency(freq),
            channel = channelForFrequency(freq),
            frequencyMhz = freq,
            channelWidthMhz = null,
            wifiStandard = null,
            rssi = rssi ?: -100,
            timestampMillis = System.currentTimeMillis(),
        )
    }

    private fun registryInfo(state: RegistryState): RegistryInfo {
        val reg = state.registryOrNull
            ?: return RegistryInfo(source = "no school registry yet", updatedAt = "", loaded = false)
        val date = reg.issuedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d"))
        val source = if (state is RegistryState.Stale) "out-of-date registry v${reg.version}" else "signed registry v${reg.version}"
        return RegistryInfo(source = source, updatedAt = date)
    }

    companion object {
        /**
         * Same name together, strongest network first; inside a name, Verified first, then by signal.
         * So a twin sits right under the real network, as in mockup 3. Hidden networks go last.
         */
        fun sortForList(networks: List<NetworkUi>): List<NetworkUi> {
            val verdictOrder = mapOf(Verdict.VERIFIED to 0, Verdict.UNKNOWN to 1, Verdict.WARNING to 2)
            return networks
                .groupBy { it.ssid }
                .entries
                .sortedWith(
                    compareBy<Map.Entry<String, List<NetworkUi>>> { it.key == RegistryClassifier.HIDDEN_NAME }
                        .thenByDescending { group -> group.value.maxOf { it.signal } },
                )
                .flatMap { (_, group) -> group.sortedWith(compareBy({ verdictOrder.getValue(it.verdict) }, { -it.signal })) }
        }
    }
}
