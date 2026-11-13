package com.example.wave.data

import com.example.wave.model.ExitPathStatus
import com.example.wave.model.NetworkUi
import com.example.wave.model.QrVerification
import com.example.wave.model.Registry
import com.example.wave.model.ScanObservation
import com.example.wave.model.ScanResult
import com.example.wave.model.WifiStandard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * The seam between UI and backend. Screens only ever see these interfaces.
 * Today: Fake* implementations in data/fake. Later: real ones, swapped in AppContainer only.
 */

interface ScanRepository {
    /** The Wi-Fi the phone is on right now, with its registry verdict. Null = not on Wi-Fi. */
    val connection: StateFlow<NetworkUi?>

    /** Scan nearby networks and verify each against the registry. */
    suspend fun scanNearby(): ScanResult

    /** Re-read and re-verify the current connection. */
    suspend fun checkConnection(): NetworkUi?

    /** A network from the latest scan or the current connection, by BSSID. */
    fun findNetwork(bssid: String): NetworkUi?
}

interface QrVerifier {
    /** Verify a scanned Wi-Fi QR payload (signature, key, expiry). */
    suspend fun verify(payload: String): QrVerification
}

interface ExitPathChecker {
    /** Emits PENDING first, then PASSED or FAILED. */
    fun check(network: NetworkUi): Flow<ExitPathStatus>
}

/** The Wi-Fi the phone is on, as Android reports it. Raw readings only. */
data class WifiConnection(
    /** Null when Android hides it (no location permission). */
    val ssid: String?,
    /** Lowercase. Null when Android hides it (no location permission / FLAG_INCLUDE_LOCATION_INFO). */
    val bssid: String?,
    val frequencyMhz: Int?,
    val rssi: Int?,
    /** Synthesized capabilities string from WifiInfo's security type (API 31+), for when the AP isn't in the scan. */
    val capabilitiesHint: String?,
    /** Current link speed (Mbps), and per direction where Android reports it. */
    val linkSpeedMbps: Int? = null,
    val txLinkSpeedMbps: Int? = null,
    val rxLinkSpeedMbps: Int? = null,
    /** Null on Android 10 (WifiInfo.getWifiStandard is API 30+). */
    val wifiStandard: WifiStandard? = null,
    /** From the network's LinkProperties: this phone's address, the router, and the DNS servers. */
    val ipAddress: String? = null,
    val gateway: String? = null,
    val dnsServers: List<String> = emptyList(),
)

/** One round of scan results. */
data class ScanBatch(
    val observations: List<ScanObservation>,
    val scannedAtMillis: Long,
    /** False = Android throttled or refused the scan and these are the last cached results. */
    val fresh: Boolean,
)

/** Android's Wi-Fi radio: nearby access points + the current connection. Never decides a verdict. */
interface WifiScanner {
    /** Null = not on Wi-Fi. */
    val connection: StateFlow<WifiConnection?>

    /** The last results Android delivered (from our scan or another app's). */
    val latest: StateFlow<ScanBatch?>

    /** Ask for a fresh scan and wait for results (with a timeout; falls back to cached results). */
    suspend fun scan(): ScanBatch
}

/** Where the signed school registry stands. Only a registry whose signature checked out is ever here. */
sealed interface RegistryState {
    /** No verified registry: nothing can be Verified. [problem] says why (null = none bundled/downloaded yet). */
    data class Missing(val problem: String? = null) : RegistryState

    data class Ready(val registry: Registry) : RegistryState

    /** Signature fine but past expires_at: still used offline, shown as out of date (CONTRACT §2). */
    data class Stale(val registry: Registry) : RegistryState
}

val RegistryState.registryOrNull: Registry?
    get() = when (this) {
        is RegistryState.Ready -> registry
        is RegistryState.Stale -> registry
        is RegistryState.Missing -> null
    }

interface RegistryRepository {
    val state: StateFlow<RegistryState>

    /** Load again (today: the bundled file; later: download from /v1/registry). Never replaces a good registry with a bad one. */
    suspend fun refresh(): RegistryState
}

/** Pure: no Android imports, so it runs in plain JUnit. registry == null -> every network is UNKNOWN. */
fun interface NetworkClassifier {
    fun classify(observation: ScanObservation, registry: Registry?): NetworkUi
}

interface LocationPermissionGate {
    /** Android needs location permission to list nearby Wi-Fi. */
    val granted: StateFlow<Boolean>

    /** Ask for the permission. Returns whether it was granted. */
    suspend fun request(): Boolean
}
