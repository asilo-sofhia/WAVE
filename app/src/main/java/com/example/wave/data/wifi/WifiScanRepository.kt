package com.example.wave.data.wifi

import com.example.wave.data.NetworkClassifier
import com.example.wave.data.ScanBatch
import com.example.wave.data.ScanRepository
import com.example.wave.data.WifiConnection
import com.example.wave.data.WifiScanner
import com.example.wave.domain.RegistryClassifier
import com.example.wave.model.NetworkUi
import com.example.wave.model.Registry
import com.example.wave.model.RegistryInfo
import com.example.wave.model.ScanObservation
import com.example.wave.model.ScanResult
import com.example.wave.model.SecurityType
import com.example.wave.model.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The real ScanRepository: Android's Wi-Fi readings ([scanner]) + the school registry ([registry])
 * + the verdict rules ([classifier]). Screens see the same ScanRepository interface as with the fake.
 *
 * [registry] is null until lane 3 delivers signed-registry verification; until then every network is UNKNOWN.
 */
class WifiScanRepository(
    private val scanner: WifiScanner,
    private val registry: StateFlow<Registry?>,
    private val classifier: NetworkClassifier,
    scope: CoroutineScope,
) : ScanRepository {

    @Volatile private var lastScan: ScanResult? = null

    override val connection: StateFlow<NetworkUi?> =
        combine(
            // RSSI changes many times a minute; only a new AP or name is a new connection.
            scanner.connection.distinctUntilChangedBy { it?.bssid to it?.ssid },
            scanner.latest,
            registry,
        ) { conn, batch, reg -> classifyConnection(conn, batch, reg) }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun scanNearby(): ScanResult {
        val batch = scanner.scan()
        val reg = registry.value
        val networks = batch.observations
            .groupBy { it.bssid }.map { (_, same) -> same.maxBy { it.rssi } } // one row per access point
            .map { classifier.classify(it, reg) }
        return ScanResult(sortForList(networks), registryInfo(reg), batch.scannedAtMillis).also { lastScan = it }
    }

    override suspend fun checkConnection(): NetworkUi? {
        scanner.scan()
        return classifyConnection(scanner.connection.value, scanner.latest.value, registry.value)
    }

    override fun findNetwork(bssid: String): NetworkUi? =
        (lastScan?.networks.orEmpty() + listOfNotNull(connection.value))
            .firstOrNull { it.bssid.equals(bssid, ignoreCase = true) }

    /** Null when not on Wi-Fi, or when Android hides the BSSID (no location permission). */
    private fun classifyConnection(conn: WifiConnection?, batch: ScanBatch?, reg: Registry?): NetworkUi? {
        val bssid = conn?.bssid ?: return null
        val observation = batch?.observations?.firstOrNull { it.bssid == bssid } ?: conn.toObservation(bssid, batch)
        return classifier.classify(observation, reg)
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

    private fun registryInfo(reg: Registry?): RegistryInfo =
        if (reg == null) {
            RegistryInfo(source = "no school registry yet", updatedAt = "", loaded = false)
        } else {
            val date = reg.issuedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d"))
            RegistryInfo(source = "school registry v${reg.version}", updatedAt = date)
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
