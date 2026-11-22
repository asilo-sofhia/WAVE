# Exit-Path Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** With real Wi-Fi, the app asks the WAVE server which provider (ASN) the connected Wi-Fi exits through and compares it with the registry's `campus_asns`. The result is Passed, Failed or **Server unreachable**, instead of Pending forever.

**Architecture:**
- A new `ServerExitPathChecker` replaces `UnavailableExitPathChecker` in `AppContainer` (real Wi-Fi mode only). It holds the decision logic: retries, the 30 s limit and the ASN comparison.
- It gets answers from an `ExitPathFetcher`. The real fetcher sends `GET /v1/exit-path` over the Wi-Fi `Network` object (`network.openConnection`), never over mobile data.
- The server address is stored in SharedPreferences. It defaults to a Gradle property and can be changed from the debug menu, which also has a "Test server" button.
- Only **Verified** networks are checked; Unknown networks show "Skipped".

**Tech Stack:** Kotlin, coroutines/Flow, `HttpsURLConnection` from `android.net.Network.openConnection`, Android's built-in `org.json`. **No new dependencies.**

**Spec:** `docs/CONTRACT.md` §4 (`GET /v1/exit-path`) and `backend/server/main.py` (`exit_path`). The user's decisions (2026-11-22):
1. Check Verified networks only.
2. When the server can't be reached, show a status that says so ("Server unreachable"), amber, with "don't enter passwords".

## Global Constraints

- Branch: `feature/exit-path-client`, created from `main` (c3bfedc or later). Don't push; ask first.
- **No new libraries** (CLAUDE.md). Use `HttpsURLConnection` and `org.json` only.
- Shared files need team agreement (CLAUDE.md): this plan changes `model/ExitPathStatus.kt`, `model/NetworkUi.kt`, `AndroidManifest.xml` and `app/build.gradle.kts`. Tell the team in the PR description. `data/Repositories.kt` is **not** changed.
- Screens never call network code directly. Only `data/exitpath/` and `di/AppContainer.kt` know about HTTP.
- The request goes over Wi-Fi: `wifiNetwork.openConnection(url)`. Never use plain `URL.openConnection()`.
- HTTPS only (CONTRACT §4). Reject `http://` addresses. Don't enable cleartext traffic.
- The response is `{"public_ip": str|null, "asn": int|null, "as_name": str|null, "checked_at": str, "note"?: str}`.
- **Decision rule** (CONTRACT §4): `asn` in `campus_asns` → PASSED; otherwise → FAILED.
  - An empty `campus_asns`, or the placeholder ASN `0`, never matches, so it gives FAILED.
- **Unreachable rule:** an attempt fails on any of these: IOException, timeout, HTTP status other than 200, JSON that can't be parsed, `asn == null`, or no Wi-Fi `Network`.
  - Up to **3 attempts**, waiting **2 s** then **4 s** between them.
  - Each attempt has a 5 s connect timeout and an 8 s read timeout.
  - A **30 s** limit covers everything.
  - The final status is `UNREACHABLE`.
- `UNREACHABLE` copy:
  - Chip label: `"Server unreachable"`.
  - Style: amber (`UnknownFg`/`UnknownBg`) with `ic_help`. Never color alone.
  - It does **not** change the verdict and does **not** change Wavey's mood (stays NORMAL).
- Every screen state has a `@Preview` (CLAUDE.md). Add Unreachable previews to Home and Monitor.
- Run `./gradlew assembleDebug` after each task. Run unit tests with `./gradlew testDebugUnitTest`.

## Review Focus

1. **Request leaks onto mobile data.** On a phone with Wi-Fi and mobile data both on, the check must use Wi-Fi, or it reports the mobile carrier's ASN.
   - Cover: Task 3 builds the connection only from the `Network` returned by `wifiNetwork()`. A null network throws `IOException("Not on Wi-Fi")` (a failed attempt) instead of falling back.
   - Manual check in Task 6, Step 3, with mobile data on.
2. **Wi-Fi changes mid-check.** The user moves to another access point while attempts are running. The old result must not be shown for the new access point.
   - Already handled: both view models key results by BSSID (`checkedBssid != network.bssid → PENDING`), and `flatMapLatest` cancels the old flow.
   - Cover: Task 2 test `cancellationStopsRetries`.
3. **Server address typed with junk:** a trailing `/`, a `/v1` suffix, spaces, `http://`, or empty.
   - Cover: Task 4 `normalizeServerUrl` tests.
4. **Server reachable but can't tell the ASN.** This happens when the address points at the laptop on the LAN (private IP) or the IP isn't in the database; the answer has `asn: null` and a `note`.
   - Must show Server unreachable, never PASSED.
   - Cover: Task 2 test `nullAsnCountsAsFailedAttempt`. The Test button in Task 5 shows the `note`.
5. **Server hangs without answering.** It must end in UNREACHABLE within 30 s, not stay Pending.
   - Cover: Task 2 test `hangingServerTimesOut`.

---

### Task 1: `UNREACHABLE` status and the "Verified only" rule

**Files:**
- Modify: `app/src/main/java/com/example/wave/model/ExitPathStatus.kt`
- Modify: `app/src/main/java/com/example/wave/model/NetworkUi.kt` (next to `withExitPath`, ~line 63)
- Modify: `app/src/main/java/com/example/wave/ui/components/StatusStyle.kt:32-36`
- Test: `app/src/test/java/com/example/wave/WaveyRulesTest.kt`

**Interfaces:**
- Produces:
  - `enum class ExitPathStatus { PENDING, PASSED, FAILED, UNREACHABLE }`
  - `fun NetworkUi.needsExitPathCheck(): Boolean` (true only when `verdict == Verdict.VERIFIED`)
  - `ExitPathStatus.UNREACHABLE.style()` returns `StatusStyle(UnknownFg, UnknownBg, R.drawable.ic_help, "Server unreachable")`

- [ ] **Step 1: Write the failing tests** in `WaveyRulesTest`:

```kotlin
@Test
fun unreachableKeepsVerdictAndMood() {
    val n = FakeFixtures.campus // VERIFIED
    assertEquals(n, n.withExitPath(ExitPathStatus.UNREACHABLE))
    assertEquals(WaveyMood.NORMAL, waveyMoodFor(Verdict.VERIFIED, ExitPathStatus.UNREACHABLE, connected = true))
}

@Test
fun onlyVerifiedNetworksGetTheExitPathCheck() {
    assertEquals(true, FakeFixtures.campus.needsExitPathCheck())
    assertEquals(false, FakeFixtures.library.needsExitPathCheck()) // UNKNOWN
    assertEquals(false, FakeFixtures.twin.needsExitPathCheck())    // WARNING
}
```

- [ ] **Step 2: Run them and check they fail.** Run `./gradlew testDebugUnitTest --tests com.example.wave.WaveyRulesTest`. Expected: compile failure (`UNREACHABLE` and `needsExitPathCheck` don't exist yet).
- [ ] **Step 3: Implement.** Add the enum value, `needsExitPathCheck()` and the `StatusStyle` branch. `withExitPath` and `waveyMoodFor` need no change, so check the tests prove that.
- [ ] **Step 4: Run them and check they pass.** Run the same command, then `./gradlew assembleDebug`. Expected: PASS and BUILD SUCCESSFUL. If a `when` is now non-exhaustive, add the `UNREACHABLE` branch with the copy from Global Constraints.
- [ ] **Step 5: Commit.** `git commit -m "feat(model): add UNREACHABLE exit-path status and Verified-only rule"`

---

### Task 2: `ServerExitPathChecker` (retry, time limit, ASN comparison)

**Files:**
- Create: `app/src/main/java/com/example/wave/data/exitpath/ExitPathFetcher.kt`
- Create: `app/src/main/java/com/example/wave/data/exitpath/ServerExitPathChecker.kt`
- Test: `app/src/test/java/com/example/wave/data/exitpath/ServerExitPathCheckerTest.kt`

**Interfaces:**
- Consumes: `ExitPathStatus.UNREACHABLE` (Task 1), `RegistryState` / `registryOrNull` / `Registry.campusAsns: List<CampusAsn>` (existing).
- Produces:
  ```kotlin
  data class ExitPathAnswer(val publicIp: String?, val asn: Int?, val asName: String?, val note: String?)
  fun interface ExitPathFetcher { suspend fun fetch(): ExitPathAnswer } // throws IOException when there's no usable answer
  class ServerExitPathChecker(
      fetcher: ExitPathFetcher,
      registry: StateFlow<RegistryState>,
      retryDelaysMs: List<Long> = listOf(2_000, 4_000), // 3 attempts in total
      overallTimeoutMs: Long = 30_000,
  ) : ExitPathChecker
  ```

`check(network)` behaviour:
1. Emit PENDING.
2. Inside `withTimeoutOrNull(overallTimeoutMs)`, try up to `retryDelaysMs.size + 1` times. An `IOException` or `asn == null` counts as a failed attempt. Wait `retryDelaysMs[i]` before each retry.
3. With an ASN: emit PASSED if it's in `registry.value.registryOrNull?.campusAsns?.map { it.asn }`, otherwise FAILED.
4. With no ASN after all attempts, or on timeout: emit UNREACHABLE.
5. The flow then completes; it doesn't wait forever.

Only `IOException` is caught. `CancellationException` must pass through.

- [ ] **Step 1: Write the failing tests.** Use `runBlocking` (no coroutines-test dependency) and `retryDelaysMs = listOf(0, 0)`. The registry is a `MutableStateFlow(RegistryState.Ready(registryWithAsns(4775)))` built from the existing model classes. Each test collects `check(FakeFixtures.campus).toList()`.
  - `matchingAsnPasses`: fetcher returns asn 4775 → `[PENDING, PASSED]`.
  - `otherAsnFails`: asn 9299 → `[PENDING, FAILED]`.
  - `placeholderAsnZeroNeverMatches`: registry campus ASN `0`, answer 0 → `[PENDING, FAILED]`.
  - `emptyCampusAsnsFails`: registry `campusAsns = emptyList()` → `[PENDING, FAILED]`.
  - `twoFailuresThenSuccessPasses`: fetcher throws `IOException` twice, then returns 4775 → `[PENDING, PASSED]`, and the fetcher was called 3 times.
  - `threeFailuresIsUnreachable`: always throws → `[PENDING, UNREACHABLE]`, called 3 times.
  - `nullAsnCountsAsFailedAttempt`: always `asn = null, note = "private address"` → `[PENDING, UNREACHABLE]`, called 3 times.
  - `hangingServerTimesOut`: fetcher does `awaitCancellation()`, `overallTimeoutMs = 200` → `[PENDING, UNREACHABLE]`, finishing within 2 s (`withTimeout(2_000)` around the collect).
  - `cancellationStopsRetries`: fetcher always throws, `retryDelaysMs = listOf(10_000, 10_000)`. Launch the collect, cancel it after the first call, and check the fetcher call count stays 1.
- [ ] **Step 2: Run them and check they fail.** Run `./gradlew testDebugUnitTest --tests com.example.wave.data.exitpath.ServerExitPathCheckerTest`. Expected: compile failure.
- [ ] **Step 3: Implement** `ExitPathFetcher.kt` (answer + interface) and `ServerExitPathChecker.kt` as described above, with a KDoc that quotes the CONTRACT §4 rule.
- [ ] **Step 4: Run them and check they pass.** Expected: 9 tests PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(exitpath): ServerExitPathChecker with 3 tries in 30 s and campus ASN check"`

---

### Task 3: HTTP fetcher over the Wi-Fi network, and the INTERNET permission

**Files:**
- Create: `app/src/main/java/com/example/wave/data/exitpath/ExitPathJson.kt`
- Create: `app/src/main/java/com/example/wave/data/exitpath/HttpExitPathFetcher.kt`
- Modify: `app/src/main/java/com/example/wave/data/wifi/AndroidWifiScanner.kt` (expose the tracked network, ~line 60)
- Modify: `app/src/main/AndroidManifest.xml` (add `<uses-permission android:name="android.permission.INTERNET" />` with a comment: "exit-path check, CONTRACT §4")
- Test: `app/src/androidTest/java/com/example/wave/data/exitpath/ExitPathJsonTest.kt` (an instrumented test, because `org.json` is a stub in local unit tests, the same as `RegistryJsonTest`)

**Interfaces:**
- Consumes: `ExitPathAnswer`, `ExitPathFetcher` (Task 2).
- Produces:
  - `fun parseExitPathAnswer(json: String): ExitPathAnswer`. It throws `IOException` on bad JSON. A missing or `null` field gives `null`; `asn` is read with `optInt` only when the field is a number.
  - `class HttpExitPathFetcher(serverUrl: () -> String?, wifiNetwork: () -> android.net.Network?) : ExitPathFetcher`
  - In `AndroidWifiScanner`: `val currentNetwork: Network? get() = wifiNetwork`

`HttpExitPathFetcher.fetch()` steps:
1. Run on `Dispatchers.IO`.
2. `serverUrl()` null or blank → throw `IOException("No server address set")`.
3. `wifiNetwork()` null → throw `IOException("Not on Wi-Fi")`.
4. URL = `"$base/v1/exit-path"`. Open it with `network.openConnection(url) as HttpsURLConnection`, with `useCaches = false`, `connectTimeout = 5_000`, `readTimeout = 8_000` and header `Accept: application/json`.
5. Status other than 200 → `IOException("HTTP $code")`.
6. Read at most 16 KB of the body, then `parseExitPathAnswer`.
7. Always `disconnect()`.

- [ ] **Step 1: Write the failing instrumented tests** in `ExitPathJsonTest`:
  - `parsesFullAnswer`: the CONTRACT §4 example → `ExitPathAnswer("203.0.113.7", 4775, "Globe Telecoms", null)`.
  - `parsesUnknownAnswerWithNote`: `{"public_ip":null,"asn":null,"as_name":null,"checked_at":"…","note":"private"}` → `asn == null`, `note == "private"`.
  - `badJsonThrowsIOException`: `"<html>"` → `assertThrows(IOException::class.java)`.
- [ ] **Step 2: Run them and check they fail.** Start the Small_Phone emulator, then run `./gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.wave.data.exitpath.ExitPathJsonTest"`. Expected: compile failure.
- [ ] **Step 3: Implement** `ExitPathJson.kt`, `HttpExitPathFetcher.kt`, the `currentNetwork` getter and the manifest line.
- [ ] **Step 4: Run them and check they pass.** Run the same command, then `./gradlew assembleDebug`. Expected: 3 tests PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.** `git commit -m "feat(exitpath): fetch /v1/exit-path over the Wi-Fi network; add INTERNET permission"`

---

### Task 4: Server-address setting

**Files:**
- Create: `app/src/main/java/com/example/wave/data/exitpath/ServerSettings.kt`
- Modify: `app/build.gradle.kts` (`buildFeatures { buildConfig = true }`, plus in `defaultConfig`: `buildConfigField("String", "WAVE_SERVER_URL", "\"${providers.gradleProperty("wave.serverUrl").getOrElse("")}\"")`)
- Test: `app/src/test/java/com/example/wave/data/exitpath/ServerSettingsTest.kt`

**Interfaces:**
- Produces:
  - `fun normalizeServerUrl(input: String): String?`. It's pure and uses `java.net.URI`. It returns the base URL with no trailing `/` or `/v1`, or `null` when the input isn't a usable `https://` address.
  - `class ServerSettings(prefs: SharedPreferences, default: String = BuildConfig.WAVE_SERVER_URL)`, with:
    - `val url: StateFlow<String?>`: the saved value, else `normalizeServerUrl(default)`.
    - `fun current(): String? = url.value`
    - `fun save(input: String): Boolean`: normalizes; false = rejected, nothing saved; blank = clear the saved value and fall back to the default.
  - Preference key `"exit_path_server_url"` in the existing `wave_prefs` file.

- [ ] **Step 1: Write the failing tests** for `normalizeServerUrl`:
  - `"https://abc.trycloudflare.com"` → `"https://abc.trycloudflare.com"`
  - `"  https://abc.trycloudflare.com/  "` → `"https://abc.trycloudflare.com"`
  - `"https://wave-x.onrender.com/v1"` and `".../v1/"` → `"https://wave-x.onrender.com"`
  - `"http://192.168.1.5:8000"` → `null` (HTTPS only)
  - `""`, `"abc"`, `"https://"` → `null`
- [ ] **Step 2: Run them and check they fail.** Run `./gradlew testDebugUnitTest --tests com.example.wave.data.exitpath.ServerSettingsTest`. Expected: compile failure.
- [ ] **Step 3: Implement** `normalizeServerUrl`, `ServerSettings` and the Gradle change. Document in `backend/README.md` (Server section) how to set a permanent default: add `wave.serverUrl=https://…` to `%USERPROFILE%\.gradle\gradle.properties`, or to the project's `gradle.properties` once the Render address is permanent.
- [ ] **Step 4: Run them and check they pass.** Run the same command, then `./gradlew assembleDebug`. Expected: PASS and BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.** `git commit -m "feat(exitpath): server-address setting with HTTPS-only validation"`

---

### Task 5: Wire it into the app (container, view models, screens, debug menu)

**Files:**
- Modify: `app/src/main/java/com/example/wave/di/AppContainer.kt`
  - Keep the `AndroidWifiScanner` in a private val.
  - Add `val serverSettings = ServerSettings(prefs)`.
  - Set `exitPathChecker = if (realWifi) ServerExitPathChecker(HttpExitPathFetcher(serverSettings::current) { androidScanner?.currentNetwork }, registryRepository.state) else FakeExitPathChecker(store)`.
  - Expose `val exitPathFetcher: ExitPathFetcher?` (null in demo mode) for the Test button.
  - Update the class KDoc: the exit path is real now, and is Pending only while checking.
- Modify: `app/src/main/java/com/example/wave/ui/screens/monitor/MonitorScreen.kt`
  - Lines ~137 and ~146: replace `network.verdict == Verdict.WARNING` with `!network.needsExitPathCheck()`.
  - Subtitle: add `state.exitPath == ExitPathStatus.UNREACHABLE -> "WAVE's server can't be reached. Don't enter passwords yet."` before the `else` branch.
  - Add `MonitorUnreachablePreview`.
  - Make the `"Skipped"` label reflect the reason: `"Skipped"` stays for WARNING; for UNKNOWN use `"Not needed"`. Use `NotCheckedStyle` (gray) for both.
- Modify: `app/src/main/java/com/example/wave/ui/screens/home/HomeScreen.kt`
  - `onCheckWifi` (~line 142): replace `verdict == Verdict.WARNING` with `!network.needsExitPathCheck()`.
  - `bubbleText`: add `c.exitPath == ExitPathStatus.UNREACHABLE -> "Server unreachable" to "I couldn't finish the check. Don't enter passwords yet."`
  - `hintText`: add `c.exitPath == ExitPathStatus.UNREACHABLE -> "Check that the WAVE server is running, then tap Check again."`
  - Add an Unreachable preview.
- Modify: `app/src/main/java/com/example/wave/data/fake/Scenario.kt`: add a `SERVER_UNREACHABLE("Server unreachable", …campus…, ExitPathStatus.UNREACHABLE, QrResult.VALID)` scenario, copying `ALL_SAFE`'s lists, so demos can show it.
- Modify: `app/src/main/java/com/example/wave/ui/debug/DebugMenuSheet.kt` and the call site `WaveApp.kt:76`.
  - New params: `serverUrl: String?`, `onSaveServerUrl: (String) -> Boolean`, `onTestServer: (suspend () -> String)?` (null in demo mode → section hidden).
  - The section has an "Exit-path server" label, an `OutlinedTextField` with placeholder `https://….trycloudflare.com`, a Save button and a "Test server" button.
  - Save shows "Saved" or "Needs an https:// address".
  - Test shows one line: `"ASN 4775 · Globe Telecoms · 203.0.113.7"`, or the `note`, or the IOException message.
  - `WaveApp.kt` builds `onTestServer` from `container.exitPathFetcher`: `runCatching { fetch() }` formatted as above.

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: nothing new for later tasks.

- [ ] **Step 1: Write the failing test.** In `WaveyRulesTest`, `scenarioCoversUnreachable`: `Scenario.entries.any { it.exitPath == ExitPathStatus.UNREACHABLE }`.
- [ ] **Step 2: Run it and check it fails.** Run `./gradlew testDebugUnitTest --tests com.example.wave.WaveyRulesTest`. Expected: FAIL.
- [ ] **Step 3: Implement** the changes listed under Files.
- [ ] **Step 4: Run all unit tests and build.** Run `./gradlew testDebugUnitTest assembleDebug`. Expected: every test passes (51 existing + the new ones) and BUILD SUCCESSFUL. Open the Home and Monitor previews in Android Studio and check the Unreachable ones render, with amber chip, icon and text.
- [ ] **Step 5: Commit.** `git commit -m "feat: real exit-path check on Verified networks, server setting in debug menu"`

---

### Task 6: End-to-end check on a real phone, and docs

There are no new code files in this task.

**Docs to modify:**
- `docs/CONTRACT.md` and `backend/CONTRACT.md` §4:
  - Change the failure line to "Request fails 3 times in 30 s → **Server unreachable** – don't enter passwords."
  - Add "The check runs only on Verified networks."
  - Keep both copies identical.
- `CLAUDE.md`: no change.

- [ ] **Step 1: Start the server and tunnel.**
  1. From `backend/`, run `.venv\Scripts\python -m uvicorn server.main:app --port 8000`.
  2. Then run `cloudflared tunnel --url http://localhost:8000` and copy the `https://….trycloudflare.com` address.
- [ ] **Step 2: Emulator (Test button only).**
  1. Install with `./gradlew installDebug`.
  2. Open the debug menu (long-press the WAVE logo), paste the address, Save, then tap **Test server**.
  3. Expected: `ASN <your ISP's> · … · <your public IP>`.
  4. The emulator's "AndroidWifi" isn't in any registry (Unknown), so Monitor shows "Not needed". That's correct.
- [ ] **Step 3: Phone on home Wi-Fi, with mobile data ON** (this checks Review Focus #1).
  1. Install, set the same address, and open Monitor.
  2. Expected: home network Verified → exit path **Passed**, Wavey HAPPY (home-dev registry campus ASN is 4775).
  3. If your home ISP isn't ASN 4775, expect **Failed**. That's also correct: it means the comparison works.
- [ ] **Step 4: Unreachable.** Stop uvicorn, then tap Check on Home again. Expected: Pending for up to 30 s, then **Server unreachable** with the "Don't enter passwords" text.
- [ ] **Step 5: Edit the CONTRACT text** in both copies.
- [ ] **Step 6: Commit.** `git commit -m "docs: CONTRACT §4 server-unreachable wording and Verified-only rule"`
- [ ] **Step 7: Ask the user before pushing or opening the PR.** The PR description must list the shared files changed (`ExitPathStatus.kt`, `NetworkUi.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`) so the team can agree.
