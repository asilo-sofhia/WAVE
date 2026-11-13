package com.example.wave.data.fake

import com.example.wave.data.ExitPathChecker
import com.example.wave.data.LocationPermissionGate
import com.example.wave.data.QrVerifier
import com.example.wave.data.ScanRepository
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.NetworkUi
import com.example.wave.model.QrVerification
import com.example.wave.model.ScanResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

class FakeScanRepository(
    private val store: ScenarioStore,
    scope: CoroutineScope,
) : ScanRepository {

    override val connection: StateFlow<NetworkUi?> =
        combine(store.scenario, store.userDisconnected) { scenario, disconnected ->
            if (disconnected) null else scenario.connection
        }.stateIn(scope, SharingStarted.Eagerly, store.scenario.value.connection)

    /** Latest scan, with "last seen" times. Kept with its scenario so a scenario switch never shows stale data. */
    @Volatile private var lastScan: Pair<Scenario, ScanResult>? = null

    override suspend fun scanNearby(): ScanResult {
        delay(store.delayMs.value)
        val scenario = store.scenario.value
        val now = System.currentTimeMillis()
        return ScanResult(
            // Curated order: the twin sits right under the real one. Weaker APs were heard a little earlier.
            networks = scenario.nearby.map { n ->
                n.copy(metrics = n.metrics?.copy(seenAtMillis = now - (-n.signal - 40) * 100L))
            },
            registry = FakeFixtures.registry,
            scannedAtMillis = now,
        ).also { lastScan = scenario to it }
    }

    override suspend fun checkConnection(): NetworkUi? {
        delay(store.delayMs.value)
        return connection.value
    }

    override fun findNetwork(bssid: String): NetworkUi? {
        val scenario = store.scenario.value
        val scanned = lastScan?.takeIf { it.first == scenario }?.second?.networks.orEmpty()
        return (scanned + scenario.nearby + listOfNotNull(connection.value)).firstOrNull { it.bssid == bssid }
    }
}

class FakeQrVerifier(private val store: ScenarioStore) : QrVerifier {
    override suspend fun verify(payload: String): QrVerification {
        delay(store.delayMs.value)
        val result = store.qrOverride.value ?: store.scenario.value.qr
        return FakeFixtures.qr(result)
    }
}

class FakeExitPathChecker(private val store: ScenarioStore) : ExitPathChecker {
    override fun check(network: NetworkUi): Flow<ExitPathStatus> = flow {
        emit(ExitPathStatus.PENDING)
        delay(store.delayMs.value * 2)
        emit(store.scenario.value.exitPath)
    }
}

class FakeLocationPermissionGate(private val store: ScenarioStore) : LocationPermissionGate {
    override val granted: StateFlow<Boolean> = store.locationGranted

    override suspend fun request(): Boolean {
        delay(300)
        val ok = !store.denyLocationRequests.value
        store.setLocationGranted(ok)
        return ok
    }
}
