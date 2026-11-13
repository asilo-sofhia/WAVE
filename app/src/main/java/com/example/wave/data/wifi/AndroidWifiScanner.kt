package com.example.wave.data.wifi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.example.wave.data.ScanBatch
import com.example.wave.data.WifiConnection
import com.example.wave.data.WifiScanner
import com.example.wave.model.ScanObservation
import com.example.wave.model.WifiStandard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real Wi-Fi readings from Android's WifiManager and ConnectivityManager.
 *
 * - Nearby APs: WifiManager.startScan() + SCAN_RESULTS_AVAILABLE_ACTION. Android allows 4 scans per 2 minutes
 *   in the foreground; when throttled we return the cached results with fresh = false.
 * - Current connection: a Wi-Fi NetworkCallback. On Android 12+ it needs FLAG_INCLUDE_LOCATION_INFO, or the
 *   SSID/BSSID come back hidden. It is re-registered when location permission is granted.
 * - Scan results need ACCESS_FINE_LOCATION and Location turned on; without them the list is empty.
 */
class AndroidWifiScanner(
    context: Context,
    scope: CoroutineScope,
    locationGranted: StateFlow<Boolean>,
) : WifiScanner {

    private val app = context.applicationContext
    private val wifi: WifiManager = app.getSystemService(WifiManager::class.java)
    private val connectivity: ConnectivityManager = app.getSystemService(ConnectivityManager::class.java)

    private val _connection = MutableStateFlow<WifiConnection?>(null)
    override val connection: StateFlow<WifiConnection?> = _connection.asStateFlow()

    private val _latest = MutableStateFlow<ScanBatch?>(null)
    override val latest: StateFlow<ScanBatch?> = _latest.asStateFlow()

    @Volatile private var waiter: CompletableDeferred<Boolean>? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var wifiNetwork: Network? = null

    init {
        // Results arrive for our scans and for scans other apps (or the system) start.
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    val pending = waiter
                    if (pending != null) pending.complete(updated) else publish(fresh = updated)
                }
            },
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED, // system broadcasts still arrive
        )
        watchConnection()
        // Location info is only included for callbacks registered while the permission is held.
        scope.launch { locationGranted.filter { it }.collect { watchConnection(); publish(fresh = false) } }
    }

    override suspend fun scan(): ScanBatch {
        val pending = CompletableDeferred<Boolean>().also { waiter = it }
        try {
            @Suppress("DEPRECATION") // still the only way to request a scan; throttled by Android
            val started = try { wifi.startScan() } catch (_: SecurityException) { false }
            val fresh = started && (withTimeoutOrNull(SCAN_TIMEOUT_MS) { pending.await() } ?: false)
            return publish(fresh)
        } finally {
            waiter = null
        }
    }

    private fun publish(fresh: Boolean): ScanBatch =
        ScanBatch(readResults(), System.currentTimeMillis(), fresh).also { _latest.value = it }

    private fun readResults(): List<ScanObservation> {
        val results = try { wifi.scanResults } catch (_: SecurityException) { null } ?: return emptyList()
        val nowWall = System.currentTimeMillis()
        val nowBootMicros = SystemClock.elapsedRealtimeNanos() / 1000
        return results.mapNotNull { it.toObservation(nowWall, nowBootMicros) }
    }

    @Suppress("DEPRECATION") // SSID/BSSID fields: the replacement (wifiSsid) is API 33+
    private fun ScanResult.toObservation(nowWall: Long, nowBootMicros: Long): ScanObservation? {
        val bssid = bssidOrNull(BSSID) ?: return null
        val caps = capabilities.orEmpty()
        val ageMillis = ((nowBootMicros - timestamp) / 1000).coerceAtLeast(0)
        return ScanObservation(
            ssid = SSID.orEmpty(),
            bssid = bssid,
            capabilities = caps,
            security = securityFromCapabilities(caps),
            band = bandForFrequency(frequency),
            channel = channelForFrequency(frequency),
            frequencyMhz = frequency,
            channelWidthMhz = widthMhz(channelWidth),
            wifiStandard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) standardOf(wifiStandard) else null,
            rssi = level,
            timestampMillis = nowWall - ageMillis,
        )
    }

    private fun watchConnection() {
        callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        val cb = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    wifiNetwork = network
                    update(caps.transportInfo as? WifiInfo)
                }
                override fun onLost(network: Network) = lost(network)
            }
        } else {
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    wifiNetwork = network
                    @Suppress("DEPRECATION") // the only way on Android 10-11
                    update(wifi.connectionInfo)
                }
                override fun onLost(network: Network) = lost(network)
            }
        }
        try {
            connectivity.registerNetworkCallback(request, cb)
            callback = cb
        } catch (_: RuntimeException) {
            callback = null // too many callbacks / missing ACCESS_NETWORK_STATE: connection stays unknown
        }
    }

    private fun lost(network: Network) {
        if (network == wifiNetwork) {
            wifiNetwork = null
            _connection.value = null
        }
    }

    private fun update(info: WifiInfo?) {
        if (info == null) return
        _connection.value = WifiConnection(
            ssid = cleanSsid(info.ssid),
            bssid = bssidOrNull(info.bssid),
            frequencyMhz = info.frequency.takeIf { it > 0 },
            rssi = info.rssi.takeIf { it > -127 },
            capabilitiesHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hintFor(info.currentSecurityType) else null,
        )
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 10_000L

        fun widthMhz(width: Int): Int? = when (width) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> 20
            ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ, ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
            5 -> 320 // ScanResult.CHANNEL_WIDTH_320MHZ (API 33)
            else -> null
        }

        fun standardOf(standard: Int): WifiStandard? = when (standard) {
            ScanResult.WIFI_STANDARD_LEGACY -> WifiStandard.LEGACY
            ScanResult.WIFI_STANDARD_11N -> WifiStandard.WIFI_4
            ScanResult.WIFI_STANDARD_11AC -> WifiStandard.WIFI_5
            ScanResult.WIFI_STANDARD_11AX -> WifiStandard.WIFI_6
            8 -> WifiStandard.WIFI_7 // ScanResult.WIFI_STANDARD_11BE (API 33)
            else -> null
        }

        /** WifiInfo security type -> a capabilities-style string the parser understands. */
        fun hintFor(type: Int): String? = when (type) {
            WifiInfo.SECURITY_TYPE_OPEN -> "[ESS]"
            WifiInfo.SECURITY_TYPE_WEP -> "[WEP]"
            WifiInfo.SECURITY_TYPE_PSK -> "[RSN-PSK-CCMP]"
            WifiInfo.SECURITY_TYPE_SAE -> "[RSN-SAE-CCMP]"
            WifiInfo.SECURITY_TYPE_OWE -> "[RSN-OWE-CCMP]"
            WifiInfo.SECURITY_TYPE_UNKNOWN -> null
            else -> "[RSN-EAP-CCMP]" // all Enterprise / Passpoint variants
        }
    }
}
