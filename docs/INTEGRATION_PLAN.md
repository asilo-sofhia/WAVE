# WAVE: Integration Plan (shared foundation)

> Status: **DRAFT, waiting for approval.** No app code is written until this is approved.
> Written Fri Nov 13, 2026. Foundation build Nov 14–15 → three lanes Nov 15–19 → **integration day Fri Nov 20**.
> Sources of truth: `docs/CONTRACT.md` (data formats) · `docs/FRONTEND_PLAN.md` (UI state names) · this file (code seams and ownership).

**What the foundation does:** before the team splits, one person lands everything that all three lanes touch: shared models, interfaces, fakes, Gradle and manifest changes, and the Fake/Real switch. After that, each lane edits only the files it owns. Screens don't change.

Legend for the code below: **🆕 new** · **✏️ changed (additive, nothing removed)** · **✅ existing, unchanged**

---

## 1. Models and interfaces (signatures only)

### 1.1 `model/` (shared)

```kotlin
// ✅ model/Verdict.kt: unchanged
enum class Verdict { VERIFIED, UNKNOWN, WARNING }
enum class WarningReason(val title: String, val explanation: String) {
    UNREGISTERED_BSSID, LOCALLY_ADMINISTERED, SECURITY_MISMATCH, RSSI_OUT_OF_RANGE, EXIT_ASN_MISMATCH
}

// ✅ model/ExitPathStatus.kt, model/WaveyMood.kt (waveyMoodFor), model/QrResult.kt: unchanged
enum class ExitPathStatus { PENDING, PASSED, FAILED }
enum class WaveyMood { NORMAL, HAPPY, UNCOMFORTABLE, CURIOUS, BRUISED, QR_PEEK }
enum class QrResult { VALID, UNSIGNED, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, EXPIRED }
data class QrVerification(val result: QrResult, val network: NetworkUi?, val signer: String?,
                          val expiresAt: String?, val rawPayload: String)

// ✏️ model/NetworkUi.kt
enum class SecurityType(val label: String) {
    OPEN, WEP, WPA2_PERSONAL, WPA3_PERSONAL, WPA2_WPA3_PERSONAL,   // ✅
    UNSUPPORTED("Enterprise / other"),                              // 🆕 EAP and OWE (contract: "out of scope → Unknown")
}
enum class WifiBand(val label: String) { GHZ_2_4, GHZ_5, GHZ_6 }  // ✅
data class NetworkUi(/* ✅ ssid, bssid, security, band, signal, verdict, reasons */)
data class RegistryInfo(val source: String, val updatedAt: String) // ✅
data class NearbyScan(                                              // ✏️ renamed from ScanResult (see §2)
    val networks: List<NetworkUi>,
    val registry: RegistryInfo,
    val scannedAtMillis: Long,
)
// ✅ SignalQuality, signalQuality(), NetworkUi.withExitPath()

// 🆕 model/ScanObservation.kt. Lane 2 produces it, the classifier consumes it. No Android imports.
data class ScanObservation(
    val ssid: String,
    val bssid: String,           // lowercase, colon-separated (normalizeBssid applied)
    val capabilities: String,    // raw android.net.wifi.ScanResult.capabilities, e.g. "[RSN-PSK-CCMP][ESS]"
    val security: SecurityType,  // parsed from capabilities (CapabilitiesParser)
    val band: WifiBand?,         // null if frequency is outside 2.4/5/6 GHz
    val channel: Int?,           // null if it can't be derived from the frequency
    val frequencyMhz: Int,
    val channelWidthMhz: Int?,   // 🆕 (Nov 13) ScanResult.channelWidth, shown on Network details
    val wifiStandard: WifiStandard?, // 🆕 (Nov 13) ScanResult.getWifiStandard(), API 30+; null on Android 10
    val rssi: Int,               // dBm
    val timestampMillis: Long,   // wall-clock time of the reading
)

// ✅ Already built (Nov 13, model/NetworkMetrics.kt): NetworkUi.metrics and NetworkUi.checks.
// The real classifier MUST fill both, so Network details can show every reading and every check:
//   metrics = NetworkMetrics(frequencyMhz, channel, channelWidthMhz, wifiStandard, capabilities, seenAtMillis)
//   checks  = one VerdictCheck per CheckKind (NETWORK_NAME, ACCESS_POINT, HARDWARE_ADDRESS, SECURITY, SIGNAL),
//             outcome PASSED / FAILED / UNKNOWN / SKIPPED + one plain sentence (measured vs. registry).
//   Rule (tested in NetworkChecksTest on the fakes): FAILED checks == reasons; any FAILED -> WARNING,
//   else any UNKNOWN -> UNKNOWN, else VERIFIED.

// 🆕 model/Registry.kt. CONTRACT.md §2, field for field. Plain Kotlin: the @Serializable DTOs live in
// data/registry/ (lane 3) and map to these, so model/ never depends on a JSON library.
data class Registry(
    val schema: String,                 // "wave-registry/1"
    val version: Int,                   // ≥ 1, rollback check
    val keyId: String,
    val issuedAt: java.time.Instant,
    val expiresAt: java.time.Instant,
    val campusAsns: List<CampusAsn>,
    val networks: List<RegistryNetwork>,
)
data class CampusAsn(val asn: Int, val name: String)
data class RegistryNetwork(
    val ssid: String,                   // exact, case-sensitive
    val security: SecurityType,         // parsed with SecurityMapping.fromContract; unknown value = registry rejected
    val aps: List<RegistryAp>,
)
data class RegistryAp(
    val bssid: String,                  // lowercase
    val band: WifiBand,                 // "2.4GHz" / "5GHz" / "6GHz"
    val channel: Int,
    val building: String,
    val rssiMin: Int,
    val rssiMax: Int,
)

// 🆕 model/ExitPathDetails.kt. Extra info for the Monitor details list (lane 1 may show it later).
data class ExitPathDetails(
    val publicIp: String,
    val asn: Int,
    val asName: String,
    val checkedAtMillis: Long,
    val campusAsn: Boolean,             // asn ∈ registry.campusAsns
)
// Unreachable server (contract: 3 failures in 30 s) is reported as status PENDING + lastError = UNREACHABLE,
// so the UI keeps showing amber "Pending" until we agree on an UNVERIFIED state (open question Q3).
enum class ExitPathError { UNREACHABLE, NOT_ON_WIFI, NO_REGISTRY }

// 🆕 model/SecurityMapping.kt. One mapping for the contract names. Pure Kotlin.
object SecurityMapping {
    /** "OPEN" | "WPA2-PSK" | "WPA3-SAE" | "WPA2-WPA3" -> SecurityType; anything else -> null. */
    fun fromContract(value: String): SecurityType?
    /** Inverse. WEP and UNSUPPORTED have no contract value -> null. */
    fun toContract(type: SecurityType): String?
}

// 🆕 model/WifiBands.kt
// Top-level functions, so the existing enum doesn't need a companion object
fun wifiBandForFrequency(mhz: Int): WifiBand?                  // 2400–2495, 5150–5895, 5925–7125
fun wifiBandFromContract(value: String): WifiBand?             // "2.4GHz" | "5GHz" | "6GHz"
fun normalizeBssid(raw: String): String                        // "8C-3B-AD-…" -> "8c:3b:ad:…"
```

**Security mapping decisions**

| Android `capabilities` (CONTRACT §2 table, checked in this order) | `SecurityType` | Contract value |
|---|---|---|
| contains `PSK` and `SAE` | `WPA2_WPA3_PERSONAL` | `WPA2-WPA3` |
| contains `SAE` only | `WPA3_PERSONAL` | `WPA3-SAE` |
| contains `PSK` only (incl. `FT/PSK`, `PSK-SHA256`, and legacy `WPA-PSK`) | `WPA2_PERSONAL` | `WPA2-PSK` |
| contains `EAP` | 🆕 `UNSUPPORTED` → classifier gives **UNKNOWN** | – |
| contains `OWE` | 🆕 `UNSUPPORTED` → **UNKNOWN** | – |
| 🆕 contains `WEP` | `WEP` | – (not in contract) |
| none of the above | `OPEN` | `OPEN` |

- **WEP:** the contract table has no WEP row, so `[WEP][ESS]` would fall through to `OPEN`. That's wrong, and the UI already has `SecurityType.WEP`. Decision: check `WEP` before the fallback. The registry can never list WEP, so a WEP network with a registered SSID gets `SECURITY_MISMATCH` (WARNING), and an unregistered one gets UNKNOWN. The contract table needs a WEP row (Q1).
- **Why `UNSUPPORTED` instead of `null`:** `NetworkUi.security` is non-null and campus Enterprise networks (e.g. eduroam) *will* show up in scans. The UI only reads `.label`, so a new enum value doesn't change any screen code.

### 1.2 `data/Repositories.kt` (shared)

```kotlin
// ✏️ ScanRepository: only the return type's name changes
interface ScanRepository {
    val connection: StateFlow<NetworkUi?>               // ✅
    suspend fun scanNearby(): NearbyScan                // ✏️ was ScanResult
    suspend fun checkConnection(): NetworkUi?           // ✅
    fun findNetwork(bssid: String): NetworkUi?          // ✅
}

interface QrVerifier { suspend fun verify(payload: String): QrVerification }        // ✅
interface LocationPermissionGate { val granted: StateFlow<Boolean>; suspend fun request(): Boolean } // ✅

// ✏️ ExitPathChecker: check() unchanged; two read-only properties added
interface ExitPathChecker {
    fun check(network: NetworkUi): Flow<ExitPathStatus>          // ✅ PENDING, then PASSED/FAILED
    val lastDetails: StateFlow<ExitPathDetails?>                  // 🆕 null until a check reached the server
    val lastError: StateFlow<ExitPathError?>                      // 🆕 null when the last check reached the server
}

// 🆕 RegistryRepository (lane 3)
sealed interface RegistryState {
    data object Missing : RegistryState                                   // no verified registry yet
    data class Ready(val registry: Registry, val syncedAtMillis: Long) : RegistryState
    data class Stale(val registry: Registry, val syncedAtMillis: Long,    // still used offline
                     val reason: StaleReason) : RegistryState
}
enum class StaleReason { EXPIRED, SYNC_FAILED }
val RegistryState.registryOrNull: Registry?                                // helper for the classifier

enum class RefreshOutcome { UPDATED, UNCHANGED, OFFLINE, BAD_SIGNATURE, UNKNOWN_KEY, ROLLBACK, MALFORMED }
interface RegistryRepository {
    val state: StateFlow<RegistryState>
    /** Download, verify the raw bytes (CONTRACT Rule #1), reject rollback, then cache. Never replaces a good cache with a bad file. */
    suspend fun refresh(): RefreshOutcome
}

// 🆕 WifiScanner (lane 2). Raw readings only; it never decides a verdict.
data class WifiConnection(
    val ssid: String?,            // null if Android hides it
    val bssid: String?,           // lowercase; null without location permission / FLAG_INCLUDE_LOCATION_INFO
    val frequencyMhz: Int?,
    val rssi: Int?,
)
data class ScanBatch(
    val observations: List<ScanObservation>,
    val scannedAtMillis: Long,
    val fresh: Boolean,           // false = Android throttled startScan() and we returned the cached results
)
interface WifiScanner {
    val connection: StateFlow<WifiConnection?>          // null = not on Wi-Fi
    val latest: StateFlow<ScanBatch?>                   // last results Android delivered
    suspend fun scan(): ScanBatch                       // ask for a scan, wait for results (with a timeout)
}

// 🆕 NetworkClassifier (lane 2). Pure: no Android imports, so it runs in plain JUnit.
fun interface NetworkClassifier {
    /** registry == null (Missing) -> UNKNOWN. */
    fun classify(observation: ScanObservation, registry: Registry?): NetworkUi
}
```

**How the real `ScanRepository` is put together (lane 2, `data/wifi/WifiScanRepository.kt`).** It sees only interfaces, so every piece can be fake or real on its own:

```kotlin
class WifiScanRepository(
    private val scanner: WifiScanner,
    private val registry: RegistryRepository,
    private val classifier: NetworkClassifier,
    scope: CoroutineScope,
) : ScanRepository {

    override val connection: StateFlow<NetworkUi?> =
        combine(scanner.connection, scanner.latest, registry.state) { conn, batch, reg ->
            conn?.bssid?.let { bssid ->
                val obs = batch?.observations?.firstOrNull { it.bssid == bssid } ?: conn.toObservation()
                classifier.classify(obs, reg.registryOrNull)
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun scanNearby(): NearbyScan {
        val batch = scanner.scan()
        val reg = registry.state.value
        val networks = batch.observations.map { classifier.classify(it, reg.registryOrNull) }
            .sortedWith(/* group by SSID, VERIFIED first, then strongest signal, so a twin sits under the real one */)
        return NearbyScan(networks, reg.toRegistryInfo(), batch.scannedAtMillis).also { last = it }
    }

    override suspend fun checkConnection(): NetworkUi? { scanner.scan(); return connection.value }
    override fun findNetwork(bssid: String): NetworkUi? = (last?.networks.orEmpty() + listOfNotNull(connection.value))
        .firstOrNull { it.bssid.equals(bssid, ignoreCase = true) }
}

// RegistryState -> the existing RegistryInfo, so the Nearby "registry line" needs no UI change:
//   Ready  -> RegistryInfo("signed registry v<version>", "<synced date>")
//   Stale  -> RegistryInfo("registry out of date", "<synced date>")
//   Missing-> RegistryInfo("no registry yet", "—")
```

**Classifier rules (proposal, lane 2 owns the final version and its tests):**

| Situation | Verdict | Reasons |
|---|---|---|
| Registry missing | UNKNOWN | – |
| `security == UNSUPPORTED` | UNKNOWN | – |
| SSID not in registry | UNKNOWN | – |
| SSID in registry, BSSID not in its `aps` | WARNING | `UNREGISTERED_BSSID` (+ `LOCALLY_ADMINISTERED` if bit 0x02 of the first octet is set) |
| BSSID registered, security differs | WARNING | `SECURITY_MISMATCH` |
| BSSID registered, RSSI outside `rssiMin − margin … rssiMax + margin` | WARNING | `RSSI_OUT_OF_RANGE` (margin to be tuned in lab trials) |
| BSSID registered, everything matches | VERIFIED | – |

---

## 2. Rename `model.ScanResult` → `NearbyScan`

**Why:** `android.net.wifi.ScanResult` is the main type lane 2 works with. Two `ScanResult`s means import aliases and confusion in every Wi-Fi file. `NearbyScan` says what it is: the result of `scanNearby()`. The fields stay the same.

Every file the rename touches (from a grep of `\bScanResult\b` across the repo):

| File | What changes |
|---|---|
| `app/src/main/java/com/example/wave/model/NetworkUi.kt` | class declaration |
| `app/src/main/java/com/example/wave/data/Repositories.kt` | import + `scanNearby()` return type |
| `app/src/main/java/com/example/wave/data/fake/Fakes.kt` | import + `FakeScanRepository.scanNearby()` |
| `app/src/main/java/com/example/wave/ui/screens/nearby/NearbyScreen.kt` | import, `NearbyUiState.Loaded(val result: NearbyScan)`, `MutableStateFlow<NearbyScan?>`, preview helper `loaded()` |
| `docs/FRONTEND_PLAN.md` | §2 and §3 code snippets |

The NearbyScreen change is type names only. Nothing visible changes.

---

## 3. Package and file ownership

| Path | Owner | Rule |
|---|---|---|
| `ui/**` (screens, components, theme, navigation) | **Lane 1** | Only lane 1 edits. Other lanes ask. |
| `ui/debug/DebugMenuSheet.kt` | Lane 1 | The foundation adds the "Implementations" section once (§E). |
| `data/wifi/**` (WifiScanner, CapabilitiesParser, connection watcher, WifiScanRepository, real LocationPermissionGate) | **Lane 2** | |
| `domain/**` (RegistryClassifier) | **Lane 2** | Pure Kotlin only: no `android.*` imports. |
| `di/WifiModule.kt` | **Lane 2** | Its `real*()` factories only (see §E). |
| `verify/**` (Ed25519 verifier, QR parser, RealQrVerifier, built-in public keys) | **Lane 3** | |
| `data/registry/**`, `data/local/**` (Room: registry meta, `scan_log`), `data/exitpath/**` | **Lane 3** | |
| `di/RegistryModule.kt` | **Lane 3** | Its `real*()` factories only. |
| `backend/**`, `app/src/main/assets/registry/**`, `app/src/test/resources/test_vectors.json` | **Lane 3** | |
| `app/src/test/java/…/<package>/**` | Same lane as the package under test | |
| `model/**`, `data/Repositories.kt` | **Shared** | Team agreement. Additive only. |
| `data/fake/**` | **Shared** | Lanes may *add* fixtures; don't change existing ones (previews depend on them). |
| `di/AppContainer.kt`, `MainActivity.kt`, `WaveApplication.kt` | **Shared** | Changed by the foundation and on integration day only. |
| `gradle/libs.versions.toml`, `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `AndroidManifest.xml` | **Shared** | Done once in the foundation. Later changes need team agreement. |
| `docs/CONTRACT.md` (+ `backend/CONTRACT.md`), `CLAUDE.md`, `.gitignore`, `.gitattributes` | **Shared** | Contract changes bump its version. Keep both copies identical. |

**Branches:** `main` holds the foundation. Each lane works on `lane/ui`, `lane/wifi` and `lane/registry`, rebases on `main` daily, and merges by PR. Merging a lane must leave the default (all Fake) app working exactly as today.

---

## 4. Dependencies (proposal, **not added yet**: please approve)

Checked against what this project actually builds with: **AGP 9.3.3, built-in Kotlin with KGP 2.2.10** (from `gradlew buildEnvironment`), Gradle 9.5.0, compileSdk 37, minSdk 29. kotlinx-coroutines resolves to 1.9.0 today.

| Library | Version | License | Lane | Why | Compatibility notes |
|---|---|---|---|---|---|
| KSP plugin `com.google.devtools.ksp` | **2.3.13** | Apache-2.0 | 3 | Room's annotation processor | Needs ≥ **2.3.1** for AGP 9 built-in Kotlin. 2.3.10 fixed R-class resolution with built-in Kotlin. Since 2.3.x, KSP isn't tied to a Kotlin version. |
| Room `room-runtime`, `room-ktx`, `room-compiler` (ksp) | **2.8.5** | Apache-2.0 | 3 | `scan_log` table, registry cache metadata | Room 2.8 is KSP2-only (OK with KSP 2.3). minSdk 23. |
| kotlinx-serialization-json + plugin `org.jetbrains.kotlin.plugin.serialization` | **1.9.0** + plugin **2.2.10** | Apache-2.0 | 3 | Registry, `.sig`, `/v1/registry`, `/v1/exit-path` JSON | The plugin must match KGP (2.2.10). Runtime 1.10.0+ is built on Kotlin 2.3/2.3.20, so we **stay on 1.9.0** (built for Kotlin 2.2) unless we bump Kotlin. Works in plain JUnit, unlike Android's `org.json`. |
| Tink `com.google.crypto.tink:tink-android` | **1.24.0** | Apache-2.0 | 3 | Ed25519 verify (`subtle.Ed25519Verify`) | The platform only has Ed25519 from API 33; our minSdk is 29. Pure Java, so no Kotlin-version risk. |
| CameraX `camera-camera2`, `camera-lifecycle`, `camera-view` | **1.6.2** | Apache-2.0 | 1 (see Q6) | Live viewfinder on Scan QR | Latest stable (1.7.0 is RC). minSdk 23. |
| ML Kit `com.google.mlkit:barcode-scanning` (bundled) | **17.3.0** | **Proprietary, free**: ML Kit / Google APIs Terms of Service | 1 (see Q6) | Decode QR → raw `WIFI:` text | Bundled model (~2–3 MB per ABI) works offline and without Play services. The unbundled alternative is smaller but downloads the model on first use, which is risky at a demo. |
| `kotlinx-coroutines-test` (testImplementation) | **1.9.0** (match runtime) | Apache-2.0 | 2, 3 | Testing flows in repositories | Keep it equal to the resolved coroutines-core. |
| ~~OkHttp~~ | ~~5.5.1~~ | Apache-2.0 | – | **Not recommended** | The contract requires sending over the Wi-Fi `Network`. `network.openConnection(url)` (HttpsURLConnection) does exactly that with zero dependencies. OkHttp would also need a bound `socketFactory` *and* `Dns`, which is easy to get wrong. |

**Server (lane 3, Python, in `backend/requirements*.txt`, not Gradle):** `fastapi` 0.162.5 (MIT) · `uvicorn` 0.54.0 (BSD-3-Clause) · `httpx` 0.28.1 (BSD-3-Clause, tests). Both need Python ≥ 3.10; we have 3.13. The ASN data source is still open (Q7).

**How they'll be added:** one commit per library group, with `./gradlew assembleDebug test` after each. If a library turns out to need newer Kotlin metadata than 2.2 can read, drop to the previous minor version and note it here.

---

## 5. Manifest (foundation, done once)

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />   <!-- 🆕 required alongside FINE on Android 12+ -->
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />        <!-- WifiManager.startScan() -->
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" />      <!-- no neverForLocation: we need BSSIDs -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera.any" android:required="false" />
<uses-feature android:name="android.hardware.wifi" android:required="true" />
```

No `ACCESS_BACKGROUND_LOCATION`: background monitoring is out of scope. Declaring permissions doesn't change any screen. The fake permission gate stays the default.

---

## A–F. What the foundation build contains (after approval)

| # | Content | Commit |
|---|---|---|
| A | Rename `ScanResult` → `NearbyScan`. Add `ScanObservation`, `Registry*`, `CampusAsn`, `ExitPathDetails`, `ExitPathError`, `SecurityType.UNSUPPORTED`, `SecurityMapping`, `WifiBand` helpers, `normalizeBssid` | 1–2 |
| B | New interfaces + `RegistryState` in `data/Repositories.kt`. `ExitPathChecker` gets `lastDetails`/`lastError` | 3 |
| C | Fakes wired to `ScenarioStore` (below) | 4 |
| D | Manifest. Gradle deps after you approve §4. `.gitattributes`: `app/src/main/assets/registry/* -text` and `app/src/test/resources/*.json -text` (signed bytes) | 5–7 |
| E | Fake/Real switch + `di/WifiModule.kt` / `di/RegistryModule.kt` stubs + debug-menu section | 8 |
| F | Tests (below) | with each commit |

**C. New fakes** (in `data/fake/`, all read the active `Scenario`, so the app behaves exactly as today):

| Fake | Behaviour |
|---|---|
| `FakeRegistryRepository(store)` | `Ready(FakeFixtures.signedRegistry)`: a new fixture (the existing `FakeFixtures.registry` `RegistryInfo` stays) with a `Registry` that lists the campus + lab APs from `FakeFixtures`. `refresh()` waits `delayMs` → `UNCHANGED`. Optional `store.registryOverride` (Missing / Stale) for the debug menu. |
| `FakeWifiScanner(store)` | Turns `scenario.nearby` / `scenario.connection` back into `ScanObservation`s (capabilities string generated from `SecurityType`, frequency from `WifiBand`). |
| `FakeNetworkClassifier` | Returns the matching `FakeFixtures` network by BSSID (ignoring case) with the observed RSSI. Otherwise UNKNOWN. |
| `FakeExitPathChecker` ✏️ | Also fills `lastDetails`: PASSED → campus ASN, FAILED → an outside ASN. `lastError` stays null. |

Screens keep using `FakeScanRepository` directly, so nothing visible changes. The new fakes let lanes mix real and fake pieces, e.g. **real classifier + real registry + `FakeWifiScanner`** to test the classifier indoors.

**F. Tests** (`app/src/test`, plain JUnit, no Android):
- `SecurityMappingTest`: all 4 contract values round-trip. Unknown strings → null. `WEP`/`UNSUPPORTED` → no contract value.
- `CapabilitiesParserTest` (the foundation ships a parser that follows the contract table; lane 2 owns it afterwards): `[RSN-PSK-CCMP][ESS]`→WPA2, `[RSN-SAE-CCMP][ESS]`→WPA3, `[RSN-PSK+SAE-CCMP][ESS]`→WPA2/3, `[RSN-FT/PSK-CCMP]`, `[RSN-PSK-SHA256-CCMP]`, `[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]`→WPA2, `[WPA2-EAP-CCMP][ESS]`→UNSUPPORTED, `[RSN-OWE-CCMP][ESS]`→UNSUPPORTED, `[WEP][ESS]`→WEP, `[ESS]` and `""`→OPEN.
- `WifiBandTest` (2412/5180/5955/2000 MHz), `NormalizeBssidTest`.
- `FakeParityTest`: each `Scenario` still yields the same `NearbyScan` networks and connection as before the refactor.
- `WaveyRulesTest`: **all 5 existing cases unchanged and passing.**
- Done when: `./gradlew assembleDebug` and `./gradlew test` pass.

---

## E. AppContainer and the Fake/Real switch

```kotlin
enum class Impl { FAKE, REAL }
enum class Component { LOCATION, REGISTRY, CLASSIFIER, WIFI_SCANNER, SCAN_REPOSITORY, QR_VERIFIER, EXIT_PATH }

/** Persisted choice per component (SharedPreferences). Default FAKE for every component until Nov 20. */
class ImplSwitches(prefs: SharedPreferences) {
    fun get(c: Component): Impl
    fun set(c: Component, impl: Impl)    // applied on next app start
}

// di/WifiModule.kt (lane 2 edits only this file). Return null = "not built yet".
object WifiModule {
    fun realScanner(context: Context, scope: CoroutineScope): WifiScanner? = null
    fun realClassifier(): NetworkClassifier? = null
    fun realScanRepository(scanner: WifiScanner, registry: RegistryRepository,
                           classifier: NetworkClassifier, scope: CoroutineScope): ScanRepository? = null
    fun realLocationGate(context: Context): LocationPermissionGate? = null
}
// di/RegistryModule.kt (lane 3 edits only this file)
object RegistryModule {
    fun realRegistry(context: Context, scope: CoroutineScope): RegistryRepository? = null
    fun realQrVerifier(registry: RegistryRepository): QrVerifier? = null
    fun realExitPathChecker(context: Context, registry: RegistryRepository): ExitPathChecker? = null
}

class AppContainer(context: Context) {
    val switches: ImplSwitches
    val registryRepository: RegistryRepository   // 🆕
    val wifiScanner: WifiScanner                 // 🆕
    val classifier: NetworkClassifier            // 🆕
    val scanRepository: ScanRepository           // ✅ same four public properties the screens use today
    val qrVerifier: QrVerifier
    val exitPathChecker: ExitPathChecker
    val locationPermission: LocationPermissionGate
    val demo: ScenarioStore?                     // non-null while any component is FAKE
    /** For the debug menu: which components have a real implementation available. */
    val realAvailable: Set<Component>
}
// Each property: if switches.get(c) == REAL, use Module.realX(...) ?: fake (fall back + log), else fake.
```

- **Why apply on restart instead of live:** ViewModels keep references to the repositories, so swapping live would leave stale instances behind. Restarting is simple and safe. The debug menu writes the switch, then restarts the app (relaunch `MainActivity` and end the process).
- **Debug menu "Implementations" section** (debug builds only, below "Scenario"): one Fake/Real row per component. REAL is greyed out with "not built yet" when the module returns null. Plus a "Restart to apply" button. Scenario controls keep working for components that are still FAKE.
- **Lanes test on a phone before Nov 20** by switching only their own component to REAL in a debug build. Nothing in `AppContainer.kt` needs editing.
- **Real `LocationPermissionGate`** needs an Activity to ask for permissions. The foundation adds a small hook: `MainActivity` registers an `ActivityResultLauncher` with the container, and the gate calls it. This is a shared file, so it's done once here.

---

## 6. Integration day: Fri Nov 20

**Before (Thu Nov 19, evening):** all three lanes merged into `main` with every switch on FAKE. `assembleDebug` + `test` green. Tag `pre-integration-2026-11-20`. Every lane has shown its REAL toggle working on at least one phone. Signed `registry.json` + `.sig` for the test lab bundled in `assets/registry/`. Server reachable over HTTPS at its public URL.

**Swaps in order** (each one: flip the switch on the phone → walk through the checklist → if it's good, flip that default in `ImplSwitches` and commit):

| # | Component → REAL | Depends on | Check on phone |
|---|---|---|---|
| 1 | `LOCATION` | – | Permission screen: grant / deny / "Open settings" all work. Fine + coarse requested together. |
| 2 | `REGISTRY` | – | Bundled registry verifies → Ready. Tampered copy (`.sig` edited) → rejected, stays Missing. Expired → Stale. `refresh()` against the server → UPDATED/UNCHANGED. Lower version → ROLLBACK. |
| 3 | `CLASSIFIER` | 2 | With `FakeWifiScanner` still on: every scenario shows the same verdicts as the fake classifier (`FakeParityTest` passing on CI). |
| 4 | `WIFI_SCANNER` | 1 | Debug screen/logcat shows real BSSIDs (lowercase), capabilities, bands. Connection BSSID not `02:00:00:00:00:00`. |
| 5 | `SCAN_REPOSITORY` | 2, 3, 4 | Nearby lists real APs. The lab AP is VERIFIED. A phone hotspot with the lab SSID is WARNING (UNREGISTERED_BSSID + LOCALLY_ADMINISTERED). Home "Check Wi-Fi" works. |
| 6 | `QR_VERIFIER` | 2 | Each QR in `test_vectors.json` + one printed QR from `wave_sign.py`: VALID, UNSIGNED, BAD_SIGNATURE, EXPIRED, UNKNOWN_KEY, MALFORMED. |
| 7 | `EXIT_PATH` | 2, server | On lab Wi-Fi → PASSED with campus ASN. On a phone hotspot → FAILED → Disconnect-now. Server down → stays Pending + `lastError = UNREACHABLE`. |

**Fallback if one breaks:**
1. **On the phone, right away:** debug menu → that component → FAKE → restart. The rest stays real.
2. **Things that depend on it:** if 2, 3 or 4 breaks, set 5 (`SCAN_REPOSITORY`) back to FAKE too. If 2 breaks, also set 6 and 7 back.
3. **In code:** the defaults are one line per component in `ImplSwitches`, so `git revert` of that one commit puts the build back to FAKE. Worst case: `git reset` to the tag `pre-integration-2026-11-20` (only after asking the team).
4. A component that isn't fixed by the end of the day stays FAKE for the demo. Write it down in this file.

---

## 7. Risks and open questions

**Risks**
- **Signed bytes vs. line endings.** `core.autocrlf=true` would turn signed JSON into CRLF on Windows and break every signature. Already fixed for `backend/registry` and `backend/test-vectors` in `.gitattributes`. The foundation adds the same rule for the app's `assets/registry/` and test resources.
- **Kotlin 2.2.10 is pinned by AGP's built-in Kotlin.** Newer Kotlin libraries (serialization ≥ 1.10, etc.) are built with Kotlin 2.3+. Versions in §4 are pinned to be safe. Bumping Kotlin is a separate decision (Q8).
- **Android scan throttling:** 4 `startScan()` calls per 2 minutes in the foreground. `ScanBatch.fresh=false` makes this visible. For lab trials, turn off "Wi-Fi scan throttling" in Developer options. Location services must be ON or scan results come back empty.
- **Connection BSSID** needs location permission plus `FLAG_INCLUDE_LOCATION_INFO` (API 31+). On API 29–30 lane 2 falls back to `WifiManager.connectionInfo`. Without it, the BSSID is `02:00:00:00:00:00` → treat it as null → UNKNOWN.
- **Fake BSSIDs are uppercase** (`8C:3B:…`) but real ones are lowercase. Real code compares case-insensitively. We're not changing the fixtures, because that would change what previews show.
- **Exit-path server must be on the public internet** with HTTPS. Without a public server on Nov 20, swap #7 stays FAKE. Release builds block plain HTTP to a laptop on the LAN. A debug-only `network_security_config` would fix that, but it's a shared manifest change (Q9).
- **QR fallback format** (`WAVE1.<payload>.<sig>`) if Google Lens rejects the extra fields: `QrVerifier.verify(payload: String)` already takes the raw string, so no interface change.
- **Two lanes need the Wi-Fi `android.net.Network`** (lane 2 for the connection, lane 3 for the exit path). Proposal: lane 3 asks `ConnectivityManager` for it directly (~20 lines) instead of sharing a new interface.

**Open questions for you**
1. **WEP:** OK to map `[WEP]` → `SecurityType.WEP` (not OPEN) and add a WEP row to CONTRACT.md §2 (bumping the contract version)?
2. **`SecurityType.UNSUPPORTED`** for EAP/OWE networks, shown as "Enterprise / other" with an UNKNOWN verdict. OK, or do you prefer hiding Enterprise networks from the list?
3. **Exit path unreachable:** the contract says "Unverified – don't enter passwords", but `ExitPathStatus` has no such value, and adding one changes `StatusStyle` (a lane 1 UI change). For now: PENDING + `lastError`. Add an `UNVERIFIED` status later through lane 1?
4. **QR verdicts disagree with the contract.** FRONTEND_PLAN decision 7 (and `QrResult.verdict` + `WaveyRulesTest.qrMoods`): BAD_SIGNATURE and EXPIRED = Warning, MALFORMED = Unknown. CONTRACT §3: BAD_SIGNATURE and **MALFORMED** = Warning, **EXPIRED** = Unknown. Which wins? Changing the app side changes a test and two QR screen states. I won't touch it in the foundation without your answer.
5. **`NearbyScan`** as the new name for the model `ScanResult`: OK?
6. **Camera QR scanning owner:** the Scan QR viewfinder (CameraX + ML Kit) is `ui/` code, so I've put it under lane 1, with lane 3 owning `verify/`. Agree, or move it to lane 3?
7. **ASN lookup for `/v1/exit-path`:** iptoasn.com database (free, PDDL, offline file), MaxMind GeoLite2-ASN (free account, CC BY-SA 4.0), or the ipinfo.io API (token, rate limits)? I recommend iptoasn, because the server has no third-party calls and no key.
8. **Kotlin version:** stay on 2.2.10 (what AGP 9.3.3 ships) for the rest of the project? I recommend yes: no upgrades before the deadline.
9. **Debug-only cleartext:** allow plain HTTP to a LAN server in debug builds (`network_security_config` in `src/debug/`) for indoor testing?
10. **Old local `master` branch:** fully contained in `main` now. Delete it? (Waiting for your OK, as requested.)
