# Registry Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The app gets the newest signed registry from the WAVE server (`GET /v1/registry`), checks its signature, keeps a copy on the phone, and refuses older versions. The campus can then update the list once, and every phone picks it up without a reinstall.

**Architecture:**
- A new `SyncingRegistryRepository` replaces `BundledRegistryRepository` in `AppContainer`.
- At start it uses the newer of two verified registries: the copy bundled in the app and the copy saved on the phone (`RegistryCache`, three plain files in app storage).
- `sync()` downloads, verifies, applies the rollback rule, saves the new copy and updates `state`.
- It runs on app start, on the Networks **Refresh** button, and on a new debug-menu **Update registry** button.
- Signature checking moves out of `BundledRegistryRepository` into `SignatureRegistryVerifier`, unchanged.
- HTTPS GET code is shared with the exit-path fetcher through one helper, `httpsGet`.

**Tech Stack:** Kotlin, coroutines, `HttpsURLConnection`, Android's `org.json`, Tink (already used). **No new dependencies.**

**Spec:**
- `docs/CONTRACT.md` §2: the `version` field and the rollback rule; `expires_at` means stale but still usable.
- `docs/CONTRACT.md` §4 `GET /v1/registry`: `{"key_id", "registry_b64url", "signature_b64url"}`. The outer `key_id` is only a hint, and the app trusts only what's inside the verified bytes.
- `backend/server/main.py` `get_registry`: returns 503 when the server has no registry.

## Global Constraints

- **Branch:** `feature/registry-download`, created from `feature/exit-path-client` (stacked). Don't push; ask first.
- **No new libraries.** Use `HttpsURLConnection`, `org.json` and the existing Tink code only.
- **Verify, then trust:** raw bytes are signature-checked before parsing (CONTRACT Rule #1), with the same checks as today's `BundledRegistryRepository.load()`:
  - `alg == Ed25519`, a known key, a valid signature, the contract's schema;
  - the `key_id` inside the file equals the signing key;
  - expired → `Stale`.
- **Rollback rule:** a downloaded registry is accepted only if its `version` is **higher** than the current one.
  - Equal → "up to date".
  - Lower → rejected, with the message `"older version vN; this phone has vM"`.
  - The current version is the highest verified one among the bundled copy, the saved copy and earlier downloads.
- **Never replace a good registry with a bad or missing one.** A failed download, bad signature or bad JSON leaves `state` and the saved copy untouched.
- **Downloads use the phone's default network**, Wi-Fi or mobile data. That's safe because the signature is checked. HTTPS only (`ServerSettings` already rejects `http://`).
  - Limits: maximum body **1 MB**, connect timeout 5 s, read timeout 8 s, cancellable (shares `httpsGet` with the exit path).
- **Automatic syncs** (app start, Networks Refresh) run at most **once per 60 s**. The debug button always syncs.
- **No server address:** no download; result `Failed("No server address set")`. Bundled or saved copies still work.
- **Shared files:**
  - `data/Repositories.kt`: only the KDoc of `refresh()` changes; no signature change.
  - `model/` is not changed.
- **Deletes `data/registry/BundledRegistryRepository.kt`** (replaced). The user approves this file deletion at plan review.
- Run `./gradlew testDebugUnitTest assembleDebug` after each task. Instrumented tests run on the emulator only: `ANDROID_SERIAL=emulator-5554`.

## Review Focus

1. **Server serves an old signed registry (rollback attack).** The app keeps the newer one, and the debug button says why. Cover: Task 3 `olderDownloadIsRejected`.
2. **App data is cleared, or the app is reinstalled.** The saved copy is gone; the app falls back to the bundled copy, and the next sync brings the newest back. Cover: Task 3 `startsWithHigherOfBundledAndSaved` (no saved copy) and `firstRunDownloadsWhenNothingLocal`.
3. **The saved copy on the phone is damaged** (half-written file, edited by hand). It fails verification, and the app falls back to the bundled copy instead of breaking. Cover: Task 2 atomic write; Task 3 `damagedSavedCopyFallsBackToBundled`.
4. **Expired registry downloaded.** If it's newer, it's accepted as `Stale` ("out-of-date registry vN"), per CONTRACT §2. Cover: Task 3 `newerButExpiredIsAcceptedAsStale`.
5. **Server returns 503 or HTML, is unreachable, or a firewall throws a non-IO exception.** `state` stays the same and the app never crashes. Cover: Task 3 `failedDownloadKeepsCurrent` and `nonIoExceptionKeepsCurrent`; Task 4 `nonJsonResponseThrowsIOException`.

---

### Task 1: Shared HTTPS GET helper, and the verifier moved out of the bundled repository

**Files:**
- Create: `app/src/main/java/com/example/wave/data/net/HttpsGet.kt`
- Modify: `app/src/main/java/com/example/wave/data/exitpath/HttpExitPathFetcher.kt`. Its body becomes `httpsGet(open = { network.openConnection(url) }, maxBytes = 16 * 1024)` followed by `parseExitPathAnswer`; the private `request` and `readNBytesCompat` code moves into `HttpsGet.kt`.
- Create: `app/src/main/java/com/example/wave/data/registry/SignedRegistry.kt`
- Test: `app/src/test/java/com/example/wave/data/registry/SignatureRegistryVerifierTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  /** GET over HTTPS; aborts on cancel. @throws IOException not https, HTTP != 200, body > maxBytes, network errors. */
  suspend fun httpsGet(open: () -> java.net.URLConnection, maxBytes: Int,
                       connectTimeoutMs: Int = 5_000, readTimeoutMs: Int = 8_000): String
  /** The signed material, exactly as signed. equals/hashCode compare the bytes' content. */
  class SignedRegistry(val bytes: ByteArray, val keyId: String, val signatureB64Url: String)
  fun interface RegistryVerifier { fun verify(file: SignedRegistry): RegistryState } // Ready/Stale or Missing(problem)
  class SignatureRegistryVerifier(
      private val now: () -> Instant = Instant::now,
      private val trustedKeys: Map<String, String> = WaveKeys.trusted,
      private val parse: (ByteArray) -> Registry = RegistryJson::parse,
  ) : RegistryVerifier
  ```
  - `SignatureRegistryVerifier` holds the checks from `BundledRegistryRepository.load()`, from the `RegistrySignature.check` call onward.
  - `parse` is injectable so JVM tests can avoid `org.json`.

- [ ] **Step 1: Write the failing tests.** Use the existing vectors in `app/src/test/resources/test_vectors.json` (read them the way `RegistrySignatureTest` does: the registry vector's bytes, key and signature), with a fake `parse` returning a fixed `Registry`.
  - `genuineIsReady`: valid vector, `expiresAt` in the future → `RegistryState.Ready`.
  - `expiredIsStale`: same, `now` after `expiresAt` → `RegistryState.Stale`.
  - `tamperedIsMissing`: flip one byte → `Missing` whose problem contains `"signature"`.
  - `unknownKeyIsMissing`: empty `trustedKeys` → `Missing` with `"unknown key"`.
  - `keyIdMismatchIsMissing`: fake `parse` returns `keyId = "other"` → `Missing` with `"key_id"`.
  - `parseErrorIsMissing`: fake `parse` throws `IllegalArgumentException` → `Missing` with `"contract"`.
- [ ] **Step 2: Run them and check they fail.** Run `./gradlew testDebugUnitTest --tests com.example.wave.data.registry.SignatureRegistryVerifierTest`. Expected: compile failure.
- [ ] **Step 3: Implement** `HttpsGet.kt`, `SignedRegistry.kt` (`SignedRegistry`, `RegistryVerifier`, `SignatureRegistryVerifier`), and refactor `HttpExitPathFetcher` to use `httpsGet`.
- [ ] **Step 4: Run all tests and build.** Run `./gradlew testDebugUnitTest assembleDebug`, then the exit-path instrumented test on the emulator:
  ```
  ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.wave.data.exitpath.ExitPathJsonTest"
  ```
  Expected: all pass.
- [ ] **Step 5: Commit.** `git commit -m "refactor: shared httpsGet helper; registry signature check as SignatureRegistryVerifier"`

---

### Task 2: `RegistryCache`, the copy saved on the phone

**Files:**
- Create: `app/src/main/java/com/example/wave/data/registry/RegistryCache.kt`
- Test: `app/src/test/java/com/example/wave/data/registry/RegistryCacheTest.kt` (JUnit `TemporaryFolder`)

**Interfaces:**
- Consumes: `SignedRegistry` (Task 1).
- Produces: `class RegistryCache(private val dir: java.io.File)` with `fun read(): SignedRegistry?` and `fun write(file: SignedRegistry)`.
  - The files in `dir` are `registry.json` (raw bytes), `registry.keyid` (UTF-8) and `registry.sig` (base64url text). No JSON is involved, so it's JVM-testable.
  - `write` writes each file as `*.tmp`, then renames, `registry.json` last.
  - `read` returns null if any file is missing or empty. It never throws on I/O; it logs nothing.

- [ ] **Step 1: Write the failing tests:**
  - `emptyDirReadsNull`
  - `writeThenReadRoundTrips`: same bytes, key id and signature.
  - `overwriteReplaces`: write v1, then v2; read gives v2.
  - `missingSignatureFileReadsNull`
  - `noTmpFilesLeftAfterWrite`
- [ ] **Step 2: Run them and check they fail.** Run `./gradlew testDebugUnitTest --tests com.example.wave.data.registry.RegistryCacheTest`. Expected: compile failure.
- [ ] **Step 3: Implement** `RegistryCache`.
- [ ] **Step 4: Run them and check they pass.** Expected: 5 PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(registry): RegistryCache keeps the signed registry on the phone"`

---

### Task 3: `SyncingRegistryRepository` (choose newest, download, rollback rule)

**Files:**
- Create: `app/src/main/java/com/example/wave/data/registry/SyncingRegistryRepository.kt`
- Test: `app/src/test/java/com/example/wave/data/registry/SyncingRegistryRepositoryTest.kt`

**Interfaces:**
- Consumes: `SignedRegistry`, `RegistryVerifier` (Task 1); `RegistryCache` (Task 2).
- Produces:
  ```kotlin
  fun interface RegistryDownloader { suspend fun download(): SignedRegistry } // throws IOException
  sealed interface RegistrySync {
      data class Updated(val version: Int) : RegistrySync
      data class UpToDate(val version: Int) : RegistrySync
      data class Rejected(val reason: String) : RegistrySync
      data class Failed(val message: String) : RegistrySync
      data object Skipped : RegistrySync // automatic sync within 60 s of the last one
  }
  class SyncingRegistryRepository(
      private val bundled: () -> SignedRegistry?,
      private val cache: RegistryCache,
      private val downloader: RegistryDownloader,
      private val verifier: RegistryVerifier,
      private val minIntervalMs: Long = 60_000,
      private val clock: () -> Long = System::currentTimeMillis,
  ) : RegistryRepository {
      suspend fun start()                              // loadLocal() then sync()
      suspend fun loadLocal()                          // verified newer of bundled/saved -> state
      suspend fun sync(force: Boolean = false): RegistrySync
      override suspend fun refresh(): RegistryState    // sync(force = false); returns state.value
  }
  ```

`sync` steps:
1. Unless `force`, return `Skipped` if the last attempt was less than `minIntervalMs` ago.
2. Download. Any exception except `CancellationException` → `Failed(message)`.
3. Verify. If the result is `Missing` → `Rejected(problem)`.
4. Compare the downloaded `version` with the current one:
   - **lower** → `Rejected("older version vN; this phone has vM")`;
   - **equal** → `UpToDate(v)`;
   - **higher**, or no current registry → `cache.write`, set `state`, return `Updated(v)`.
5. A mutex serializes syncs.
6. Cache and verifier work runs on `Dispatchers.IO`.

- [ ] **Step 1: Write the failing tests.**
  - Setup:
    - A fake verifier that reads the version from the bytes (for example the text `"v3"` → `Ready(registry(version = 3))`, `"v3-expired"` → `Stale(...)`, `"bad"` → `Missing("signature doesn't match the file")`).
    - A real `RegistryCache` in a `TemporaryFolder`, a counting fake downloader, and `runBlocking`.
  - Tests:
    - `startsWithHigherOfBundledAndSaved`: bundled v1, saved v2 → state version 2; bundled v3, saved v2 → 3; no saved copy → 1.
    - `newerDownloadReplacesAndIsSaved`: current v1, download v2 → `Updated(2)`, state v2, `cache.read()` gives v2's bytes.
    - `olderDownloadIsRejected`: current v2, download v1 → `Rejected` containing `"older version v1; this phone has v2"`, state still v2, saved copy unchanged.
    - `sameVersionIsUpToDate`: current v2, download v2 → `UpToDate(2)`.
    - `badSignatureIsRejectedAndKeepsCurrent`: download `"bad"` → `Rejected`, state unchanged.
    - `failedDownloadKeepsCurrent`: downloader throws `IOException("HTTP 503")` → `Failed("HTTP 503")`, state unchanged.
    - `nonIoExceptionKeepsCurrent`: downloader throws `SecurityException` → `Failed`, no crash.
    - `firstRunDownloadsWhenNothingLocal`: no bundled, no saved copy → state `Missing`; download v1 → `Updated(1)`, state `Ready`.
    - `newerButExpiredIsAcceptedAsStale`: current v1, download `"v2-expired"` → `Updated(2)`, state `Stale`.
    - `damagedSavedCopyFallsBackToBundled`: saved copy verifies as `Missing`, bundled v1 → state v1.
    - `automaticSyncIsThrottled`: two `sync()` calls at the same clock → second is `Skipped`, downloader called once; `sync(force = true)` downloads again.
- [ ] **Step 2: Run them and check they fail.** Run `./gradlew testDebugUnitTest --tests com.example.wave.data.registry.SyncingRegistryRepositoryTest`. Expected: compile failure.
- [ ] **Step 3: Implement** as described above.
- [ ] **Step 4: Run them and check they pass.** Expected: 11 PASS.
- [ ] **Step 5: Commit.** `git commit -m "feat(registry): SyncingRegistryRepository with rollback check"`

---

### Task 4: HTTP downloader, and wiring into the app

**Files:**
- Create: `app/src/main/java/com/example/wave/data/registry/HttpRegistryDownloader.kt`
- Test: `app/src/androidTest/java/com/example/wave/data/registry/RegistryResponseTest.kt` (an instrumented test, because it uses `org.json`)
- Modify: `app/src/main/java/com/example/wave/di/AppContainer.kt`
  - `registryRepository = SyncingRegistryRepository(bundled = { readBundledRegistry(context) }, cache = RegistryCache(File(context.filesDir, "registry")), downloader = HttpRegistryDownloader(serverSettings::current), verifier = SignatureRegistryVerifier())`
  - Then `appScope.launch { registryRepository.start() }`.
  - Move `serverSettings` above it.
  - Expose `val registrySync: suspend () -> RegistrySync = { registryRepository.sync(force = true) }`. Keep the concrete type in a private val.
- Delete: `app/src/main/java/com/example/wave/data/registry/BundledRegistryRepository.kt`. Its asset-reading part becomes `fun readBundledRegistry(context: Context): SignedRegistry?` in `HttpRegistryDownloader.kt`'s sibling file `BundledRegistry.kt`, which reads `registry/registry.json` and `.sig` from assets, with the `.sig` parsed by `RegistryJson.parseSignature`.
- Modify: `app/src/main/java/com/example/wave/data/Repositories.kt:111`. KDoc only: "Downloads the newest signed registry from the server (at most once a minute) and keeps it if it's newer. Never replaces a good registry with a bad one."
- Modify: `app/src/main/java/com/example/wave/ui/screens/nearby/NearbyScreen.kt`. `NearbyViewModel` gets a `registry: RegistryRepository` parameter, and `refresh()` also launches `registry.refresh()` in its own job; the route passes `container.registryRepository`.
- Modify: `app/src/main/java/com/example/wave/ui/debug/DebugMenuSheet.kt` and `WaveApp.kt`.
  - New parameter `onUpdateRegistry: (suspend () -> String)?`. An **"Update registry"** `SecondaryButton` sits under the server buttons, with its own status line.
  - `WaveApp` maps `RegistrySync` to one line:
    - `Updated` → "Registry v2 downloaded"
    - `UpToDate` → "Already up to date (v2)"
    - `Rejected` → "Rejected: <reason>"
    - `Failed` → "No answer: <message>"
- Modify: `backend/README.md` "Point the app at the server" paragraph: add one sentence saying the app downloads the registry from the same address on start and on Refresh, and the debug button forces it.

**Interfaces:**
- Consumes: Tasks 1–3, plus `ServerSettings.current()` from the exit-path branch.
- Produces:
  - `fun parseRegistryResponse(json: String): SignedRegistry`. It decodes `registry_b64url` with `Base64.getUrlDecoder()` and throws `IOException` on bad JSON, missing fields or bad base64.
  - `class HttpRegistryDownloader(serverUrl: () -> String?) : RegistryDownloader`. It throws `IOException("No server address set")` when the address is null, and uses `httpsGet(open = { URL("$base/v1/registry").openConnection() }, maxBytes = 1_048_576)`.

- [ ] **Step 1: Write the failing instrumented tests:**
  - `parsesServerResponse`: `{"key_id":"wave-dev","registry_b64url":"eyJ2IjoxfQ","signature_b64url":"AAAA"}` → bytes = `{"v":1}`, keyId `wave-dev`, signature `"AAAA"`.
  - `missingFieldThrowsIOException`
  - `nonJsonResponseThrowsIOException`: `"<html>"`.
- [ ] **Step 2: Run them and check they fail.** Run:
  ```
  ANDROID_SERIAL=emulator-5554 ./gradlew connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=com.example.wave.data.registry.RegistryResponseTest"
  ```
  Expected: compile failure.
- [ ] **Step 3: Implement** everything listed under Files.
- [ ] **Step 4: Run all tests and build.** Run `./gradlew testDebugUnitTest assembleDebug`, plus the instrumented `RegistryResponseTest`, `RegistryJsonTest` and `ExitPathJsonTest` on the emulator. Expected: all pass.
- [ ] **Step 5: Commit.** `git commit -m "feat(registry): download the signed registry from /v1/registry"`

---

### Task 5: End-to-end check with real signed versions

There are no new code files in this task. Run everything from `backend/`, and put all test registries in the scratchpad, **never** in `backend/registry/`.

- [ ] **Step 1: Make a v2.** Copy `registry/registry.home-dev.json` to `<scratch>/reg-v2/registry.json`, set `"version": 2`, and sign it with the dev key:
  ```
  python signing/wave_sign.py sign-registry <scratch>/reg-v2/registry.json --key keys/wave-dev.private.pem
  ```
- [ ] **Step 2: Serve v1.** Start the server with the home registry (`WAVE_TRUSTED_PROXY=cloudflare`, `WAVE_REGISTRY_FILE=registry/registry.home-dev.json`), start a tunnel, install on the emulator with `-Pwave.serverUrl=<tunnel>`, and tap **Update registry**.
  - Expected: "Already up to date (v1)".
- [ ] **Step 3: Serve v2.** Restart the server with `WAVE_REGISTRY_FILE=<scratch>/reg-v2/registry.json` and tap Update registry.
  - Expected: "Registry v2 downloaded", and Networks shows "signed registry v2".
- [ ] **Step 4: Rollback.** Restart the server with v1 again and tap Update registry.
  - Expected: "Rejected: older version v1; this phone has v2", and Networks still shows v2.
- [ ] **Step 5: Offline.** Stop the server, force-stop the app and reopen it.
  - Expected: Networks still says v2 (the saved copy).
- [ ] **Step 6: Clean up.** Stop the server and tunnel. Leave the emulator in Real Wi-Fi mode.
- [ ] **Step 7: Ask the user before pushing or opening the PR.** The PR description must list the shared-file change (`Repositories.kt`, KDoc only) and the deleted `BundledRegistryRepository.kt`.
