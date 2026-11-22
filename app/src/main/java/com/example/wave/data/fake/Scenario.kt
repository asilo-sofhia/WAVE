package com.example.wave.data.fake

import com.example.wave.model.ExitPathStatus
import com.example.wave.model.NetworkUi
import com.example.wave.model.QrResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Demo scenarios, switchable from the hidden debug menu. See docs/FRONTEND_PLAN.md §3. */
enum class Scenario(
    val label: String,
    val nearby: List<NetworkUi>,
    val connection: NetworkUi?,
    val exitPath: ExitPathStatus,
    val qr: QrResult,
) {
    ALL_SAFE(
        "All safe",
        listOf(FakeFixtures.campus, FakeFixtures.lab),
        FakeFixtures.campus, ExitPathStatus.PASSED, QrResult.VALID,
    ),
    TWIN_NEARBY(
        "Twin nearby",
        listOf(FakeFixtures.campus, FakeFixtures.twin, FakeFixtures.library, FakeFixtures.lab),
        FakeFixtures.campus, ExitPathStatus.PASSED, QrResult.VALID,
    ),
    UNKNOWN_ONLY(
        "Unknown only",
        listOf(FakeFixtures.library, FakeFixtures.cafe),
        FakeFixtures.library, ExitPathStatus.PASSED, QrResult.UNSIGNED,
    ),
    QR_VALID(
        "QR valid",
        listOf(FakeFixtures.campus, FakeFixtures.lab),
        null, ExitPathStatus.PASSED, QrResult.VALID,
    ),
    QR_CHANGED(
        "QR changed",
        listOf(FakeFixtures.campus, FakeFixtures.lab),
        null, ExitPathStatus.PASSED, QrResult.BAD_SIGNATURE,
    ),
    EXIT_PATH_FAILED(
        "Exit path failed",
        listOf(FakeFixtures.campus, FakeFixtures.lab),
        FakeFixtures.campus, ExitPathStatus.FAILED, QrResult.VALID,
    ),
    CONNECTED_TO_TWIN(
        "Connected to twin",
        listOf(FakeFixtures.campus, FakeFixtures.twin, FakeFixtures.library, FakeFixtures.lab),
        FakeFixtures.twin, ExitPathStatus.FAILED, QrResult.VALID,
    ),
}

/** Demo-only state shared by the Fake* implementations and the debug menu. */
class ScenarioStore(initialLocationGranted: Boolean, private val onLocationChanged: (Boolean) -> Unit) {
    // Starts on the mockup 3 list so Verified, Warning and Unknown rows are all visible.
    private val _scenario = MutableStateFlow(Scenario.TWIN_NEARBY)
    val scenario: StateFlow<Scenario> = _scenario.asStateFlow()

    /** Forces a specific QR result regardless of scenario (null = use the scenario's). */
    val qrOverride = MutableStateFlow<QrResult?>(null)

    /** Fake network delay so loading states are visible. */
    val delayMs = MutableStateFlow(1200L)

    /** When true, "Allow location" is refused, to demo the denied state. */
    val denyLocationRequests = MutableStateFlow(false)

    private val _locationGranted = MutableStateFlow(initialLocationGranted)
    val locationGranted: StateFlow<Boolean> = _locationGranted.asStateFlow()

    /** The fake can't see the real Wi-Fi panel, so "I've disconnected" flips this. */
    val userDisconnected = MutableStateFlow(false)

    fun select(scenario: Scenario) {
        _scenario.value = scenario
        userDisconnected.value = false
    }

    fun setLocationGranted(granted: Boolean) {
        _locationGranted.value = granted
        onLocationChanged(granted)
    }

    fun simulateDisconnect() {
        userDisconnected.value = true
    }
}
