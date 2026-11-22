package com.example.wave.di

import android.content.Context
import com.example.wave.data.ExitPathChecker
import com.example.wave.data.LocationPermissionGate
import com.example.wave.data.QrVerifier
import com.example.wave.data.ScanRepository
import com.example.wave.data.fake.FakeExitPathChecker
import com.example.wave.data.fake.FakeLocationPermissionGate
import com.example.wave.data.fake.FakeQrVerifier
import com.example.wave.data.fake.FakeScanRepository
import com.example.wave.data.fake.ScenarioStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI. The backend plugs in by replacing the four Fake* lines below; screens don't change. */
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("wave_prefs", Context.MODE_PRIVATE)

    val onboarding = OnboardingPrefs(prefs)

    /** Demo-only state. [demo] is what the debug menu sees; it becomes null when real implementations are in. */
    private val store = ScenarioStore(
        initialLocationGranted = prefs.getBoolean(KEY_FAKE_LOCATION, false),
        onLocationChanged = { prefs.edit().putBoolean(KEY_FAKE_LOCATION, it).apply() },
    )
    val demo: ScenarioStore? = store

    val scanRepository: ScanRepository = FakeScanRepository(store, appScope)
    val qrVerifier: QrVerifier = FakeQrVerifier(store)
    val exitPathChecker: ExitPathChecker = FakeExitPathChecker(store)
    val locationPermission: LocationPermissionGate = FakeLocationPermissionGate(store)

    private companion object {
        const val KEY_FAKE_LOCATION = "fake_location_granted"
    }
}

class OnboardingPrefs(private val prefs: android.content.SharedPreferences) {
    var splashSeen: Boolean
        get() = prefs.getBoolean("splash_seen", false)
        set(value) = prefs.edit().putBoolean("splash_seen", value).apply()
}
