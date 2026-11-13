package com.example.wave.di

import android.content.Context
import com.example.wave.data.ExitPathChecker
import com.example.wave.data.LocationPermissionGate
import com.example.wave.data.NetworkClassifier
import com.example.wave.data.QrVerifier
import com.example.wave.data.ScanRepository
import com.example.wave.data.exitpath.UnavailableExitPathChecker
import com.example.wave.data.fake.FakeExitPathChecker
import com.example.wave.data.fake.FakeLocationPermissionGate
import com.example.wave.data.fake.FakeQrVerifier
import com.example.wave.data.fake.FakeScanRepository
import com.example.wave.data.fake.ScenarioStore
import com.example.wave.data.wifi.AndroidLocationPermissionGate
import com.example.wave.data.wifi.AndroidWifiScanner
import com.example.wave.data.wifi.WifiScanRepository
import com.example.wave.domain.RegistryClassifier
import com.example.wave.model.Registry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manual DI. Screens only see the interfaces below; real and fake implementations are swapped here only.
 *
 * [realWifi] (default on, switchable in the debug menu, applied on restart):
 * - on:  real Wi-Fi scanning, connection and location permission. Registry and exit path aren't built yet,
 *        so every network is UNKNOWN and the exit path stays PENDING.
 * - off: the demo scenarios (fake data), exactly as in the frontend phase.
 * QR verification is fake in both modes until lane 3 delivers it.
 */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("wave_prefs", Context.MODE_PRIVATE)

    val onboarding = OnboardingPrefs(prefs)

    /** True = real Wi-Fi data; false = demo scenarios. Read once per process start. */
    val realWifi: Boolean = prefs.getBoolean(KEY_REAL_WIFI, true)

    /** Saves the choice; the caller restarts the app so every screen picks it up. */
    fun setRealWifi(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REAL_WIFI, enabled).commit()
    }

    /** Demo-only state. [demo] is what the debug menu sees (scenarios still drive the fake QR results). */
    private val store = ScenarioStore(
        initialLocationGranted = prefs.getBoolean(KEY_FAKE_LOCATION, false),
        onLocationChanged = { prefs.edit().putBoolean(KEY_FAKE_LOCATION, it).apply() },
    )
    val demo: ScenarioStore? = store

    /** Non-null with real Wi-Fi: MainActivity attaches its permission launcher to it. */
    val androidPermissionGate: AndroidLocationPermissionGate? =
        if (realWifi) AndroidLocationPermissionGate(context) else null

    val locationPermission: LocationPermissionGate = androidPermissionGate ?: FakeLocationPermissionGate(store)

    /** Null until lane 3 delivers signed-registry download + verification. */
    private val registry: StateFlow<Registry?> = MutableStateFlow(null)

    val classifier: NetworkClassifier = RegistryClassifier()

    val scanRepository: ScanRepository =
        if (realWifi) {
            WifiScanRepository(
                scanner = AndroidWifiScanner(context, appScope, locationPermission.granted),
                registry = registry,
                classifier = classifier,
                scope = appScope,
            )
        } else {
            FakeScanRepository(store, appScope)
        }

    val qrVerifier: QrVerifier = FakeQrVerifier(store)

    val exitPathChecker: ExitPathChecker = if (realWifi) UnavailableExitPathChecker() else FakeExitPathChecker(store)

    private companion object {
        const val KEY_FAKE_LOCATION = "fake_location_granted"
        const val KEY_REAL_WIFI = "real_wifi"
    }
}

class OnboardingPrefs(private val prefs: android.content.SharedPreferences) {
    var splashSeen: Boolean
        get() = prefs.getBoolean("splash_seen", false)
        set(value) = prefs.edit().putBoolean("splash_seen", value).apply()
}
