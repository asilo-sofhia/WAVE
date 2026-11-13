package com.example.wave.data

import com.example.wave.model.ExitPathStatus
import com.example.wave.model.NetworkUi
import com.example.wave.model.QrVerification
import com.example.wave.model.ScanResult
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

interface LocationPermissionGate {
    /** Android needs location permission to list nearby Wi-Fi. */
    val granted: StateFlow<Boolean>

    /** Ask for the permission. Returns whether it was granted. */
    suspend fun request(): Boolean
}
