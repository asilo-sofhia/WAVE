# Phase 5: Users Get Every Place Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every phone downloads each place's signed list from the WAVE server, checks it from the root key down, saves it, and judges nearby Wi-Fi against all places together, alongside the existing built-in or campus list.

**Architecture:**
- **Pure check:** `verify/PlacePackage.kt` mirrors `wave_sign.verify_package` (CONTRACT §7) and is tested against `package_cases` in `test_vectors.json`.
- **`data/registry/PlaceLists.kt`:**
  - keeps one verified package per place in `files/places/`;
  - syncs from `GET /v1/places` (index) and `GET /v1/places/{id}/registry`, downloading **only places whose version is higher** than the saved copy;
  - refuses rollbacks per place.
- **Merge:** a pure `mergeRegistries` combines the campus registry (today's `SyncingRegistryRepository`) and all place registries into the one `Registry` the classifier, QR checker and exit-path checker already use. This happens behind a `CombinedRegistryRepository` swapped in **only in `AppContainer`**, so screens don't change.
- **Server:** gzip for large answers.

**Tech Stack:**
- Kotlin, `org.json`, `java.security` (P-256 verify), Tink `Ed25519Verify` (via `AdminCert`).
- FastAPI's built-in `GZipMiddleware` (starlette, no new dependency).

**Spec:** `docs/superpowers/specs/2026-11-24-multi-place-admins.md` (Flows 3; phase 5 row; rules 1–5 and 7), `docs/CONTRACT.md` §4, §6, §7

## Global Constraints

- **Package check order** (CONTRACT §7, same as Python):
  1. shape and registry format, with `place_id` → MALFORMED;
  2. the certificate is VALID at the registry's `issued_at` (`AdminCert.verify` with `WaveKeys.roots`), and the registry's `expires_at` is ≤ the certificate's `expires_at` → EXPIRED;
  3. the certificate, package and registry name the same `place_id` → WRONG_PLACE;
  4. `key_id` == `cert_id` → MALFORMED;
  5. ECDSA P-256/SHA-256 DER over `"WAVE-REGISTRY-v1\n"` + the exact bytes, with the certificate's `admin_public_key` → BAD_SIGNATURE.

  Phones skip the server's upload-time `now` checks.
- **Per place:**
  - keep the download only if VALID, its `place_id` equals the index entry's, and its `version` is higher than the saved one;
  - otherwise keep the saved copy;
  - a place missing from the index is **kept** (removal needs signed revocation, phase 6).
- **Place lists past `expires_at` are not used** (an expired admin list stops vouching). The campus registry keeps today's Stale behaviour.
- **Merge rules:**
  - networks with the same (ssid, security) from different sources get their access points joined (first copy of a BSSID wins);
  - `blocked` is the union, **except** a BSSID that any source approves is not blocked (one place can't turn another place's access point into a Warning);
  - `campus_asns` is the union.
  - The combined `version` = campus version + the sum of place versions. It only goes up, so Networks re-judges when any list updates.
- Downloads go over any network (`url.openConnection()`) at app start and on Refresh (no background work, CLAUDE.md), at most once a minute unless forced. The index is capped at 1 MiB and each package at 512 KiB.
- **No shared-file changes:** `model/` and `data/Repositories.kt` stay as they are. The place name is kept beside the registry, not inside `Registry`.
- **CLAUDE.md applies:** run `./gradlew assembleDebug` after each change. Device: Xiaomi `be387ebf`; the user tests the UI by hand.
- Branch: `feature/place-lists`, on top of `feature/admin-publish` (phase 4, not merged yet).

## Review Focus

1. **Rollback:** the server (or an attacker in the middle) serves an **older but genuine** list for a place. The phone keeps its newer copy. → Task 3 `olderVersionIsRefused`.
2. **The index and the package disagree:** the index says `sm-mall` but the package is a valid `batstateu` list, so it's refused for that entry. → Task 3 `packageForAnotherPlaceIsRefused`.
3. **One bad place doesn't stop the others:** a broken package, a 404 or a bad signature for one place still lets the other places update. → Task 3 `oneBadPlaceDoesNotStopOthers`.
4. **A saved copy edited or cut short on disk** is ignored on load (it's re-checked every load) and replaced on the next sync. → Task 3 `damagedSavedCopyIsIgnored`.
5. **Cross-place blocking:** place A's admin blocks an access point that place B approves. B's access point stays Verified. → Task 4 `approvalBeatsAnotherPlacesBlock`.

---

### Task 1: Server sends large answers compressed

**Files:**
- Modify: `backend/server/main.py`: `app.add_middleware(GZipMiddleware, minimum_size=1024)` (from `starlette.middleware.gzip`)
- Modify: `docs/CONTRACT.md` and `backend/CONTRACT.md` §4: one line, "Answers over 1 KiB are gzip-compressed when the client sends `Accept-Encoding: gzip` (Android does this by itself)." Keep both copies identical.
- Test: `backend/tests/test_places_api.py`

- [ ] **Step 1: Write the failing test** `test_large_answers_are_gzipped`.
  - Upload a valid package (about 1.5 KB) with `make_package(root=root)`.
  - `c.get("/v1/places/testplace/registry", headers={"Accept-Encoding": "gzip"})` has `content-encoding: gzip`, and `r.json()` still equals the package.
  - `c.get("/v1/health", headers={"Accept-Encoding": "gzip"})` has no `content-encoding` (too small).
- [ ] **Step 2: Run the test and check it fails.** Run `python -m pytest tests/test_places_api.py -q -k gzip` from `backend/`. Expected: FAIL (no content-encoding).
- [ ] **Step 3: Add the middleware and the CONTRACT line.**
- [ ] **Step 4: Run all backend tests.** Run `python -m pytest -q`. Expected: all pass.
- [ ] **Step 5: Commit.** Message: `feat(server): gzip large answers`

### Task 2: Check a place package on the phone

**Files:**
- Create: `app/src/main/java/com/example/wave/verify/PlacePackage.kt`
- Test: `app/src/test/java/com/example/wave/verify/PlacePackageTest.kt`

**Interfaces:**
- Consumes: `AdminCert.verify`, `CertResult`, `AdminCertificate` (phase 3), `RegistryJson.parse`, `RegistrySignature.DOMAIN`.
- Produces:
  ```kotlin
  enum class PackageResult { VALID, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, NOT_YET_VALID, EXPIRED, WRONG_PLACE }
  data class PlaceList(val placeId: String, val placeName: String, val registry: Registry, val packageJson: String)
  object PlacePackage {
      /** Never throws. The list is returned only when VALID. */
      fun verify(packageJson: String, rootKeys: Map<String, String> = WaveKeys.roots): Pair<PackageResult, PlaceList?>
  }
  ```
  `place_id` is read from the registry JSON beside `RegistryJson.parse`, because `Registry` has no place field.

- [ ] **Step 1: Write the failing tests:**
  - **`vectorCasesMatchPython`:** for each of the 7 `package_cases` (`package_json`, `expect`), with roots from `root_public_keys`, `verify(...).first.name == expect`.
  - **`validCaseFields`:** the `valid` case gives `placeId == "testplace"`, `placeName == "Test Place"`, `registry.version == 2` and one network `TEST-PLACE-WIFI`.
  - **`junkIsMalformed`:** `""`, `"{}"`, `"[]"`, a package with a number for `cert`, and invalid base64 each give `(MALFORMED, null)` and never throw.
  - **`registryKeysCannotCertify`:** the `valid` package checked with `rootKeys = WaveKeys.trusted` gives `UNKNOWN_KEY`.
- [ ] **Step 2: Run the tests and check they fail.** Run `./gradlew :app:testDebugUnitTest --tests "*PlacePackageTest"`. Expected: compile error.
- [ ] **Step 3: Implement it** in the Global Constraints order. Map certificate results to the same names. Wrap everything in `try/catch (e: Exception)`, which gives MALFORMED.
- [ ] **Step 4: Run the tests and the build.** Run `./gradlew :app:testDebugUnitTest assembleDebug`. Expected: pass.
- [ ] **Step 5: Commit.** Message: `feat(registry): check place packages on the phone (CONTRACT §7)`

### Task 3: Download and keep every place's list

**Files:**
- Modify: `data/admin/PlaceServer.kt`: `PlaceServerJson.index(body): List<PlaceSummary>`
- Create: `data/registry/PlaceLists.kt`
- Test: `app/src/test/java/com/example/wave/data/registry/PlaceListsTest.kt`, using the `make`/`certify` helpers in the test folder to build real packages signed by a test root and a JVM admin key

**Interfaces:**
- Consumes: Task 2 `PlacePackage.verify` and `PlaceList`; `httpsGet`.
- Produces:
  ```kotlin
  data class PlaceSummary(val placeId: String, val placeName: String, val version: Int)
  interface PlaceDirectory {
      suspend fun index(): List<PlaceSummary>               // GET /v1/places; IOException when no usable answer
      suspend fun download(placeId: String): String       // GET /v1/places/{id}/registry body; IOException
  }
  class HttpPlaceDirectory(baseUrl: () -> String?) : PlaceDirectory
  class PlaceListStore(dir: File) {                        // one <place_id>.json per place, written via .tmp + rename
      fun readAll(): List<String>; fun write(placeId: String, packageJson: String)
  }
  data class PlacesSync(val updated: List<String>, val refused: Map<String, String>, val failed: String?)
  class PlaceListsRepository(
      directory: PlaceDirectory, store: PlaceListStore,
      rootKeys: Map<String, String> = WaveKeys.roots, now: () -> Instant = Instant::now,
      minIntervalMs: Long = 60_000, clockMs: () -> Long = System::currentTimeMillis,
  ) {
      val lists: StateFlow<Map<String, PlaceList>>          // verified, not expired, by place_id
      suspend fun loadLocal()
      suspend fun sync(force: Boolean = false): PlacesSync  // never throws except on cancel
  }
  ```
  - **`sync`:** fetch the index. For each entry whose `version` > saved, download, then verify, then require `placeId == entry.placeId` and `version > saved`, then save and update `lists`.
  - **`refused`** maps a place_id to its reason. **`failed`** is set only when the index itself failed.
  - **Expired lists** (`registry.expiresAt < now()`) are kept on disk but left out of `lists`.

- [ ] **Step 1: Write the failing tests:**
  - **`downloadsOnlyNewerPlaces`:**
    - Setup: the index has `a` v2 and `b` v1; `a` v1 is saved.
    - It downloads `a` (v2) and `b`; `lists` has both.
    - A second sync with the same index downloads nothing.
  - **`olderVersionIsRefused`:** `a` v3 is saved, and the index wrongly says v5 but serves a genuine v2. `a` is refused and stays at v3.
  - **`packageForAnotherPlaceIsRefused`**
  - **`oneBadPlaceDoesNotStopOthers`:** one place answers with an IOException, one with a bad signature, and one is good. Only the good one is updated, and `refused` names the other two.
  - **`damagedSavedCopyIsIgnored`:** a saved file that is junk or has one changed byte is not in `lists` after `loadLocal`, and the next sync replaces it.
  - **`expiredListsAreNotUsed`**
  - **`indexDownIsFailedAndKeepsLists`**
  - **`placeMissingFromIndexIsKept`**
  - **`automaticSyncAtMostOncePerMinute`**
  - **`indexParsing`:** `PlaceServerJson.index` reads the CONTRACT §4 example, and throws on junk.
- [ ] **Step 2: Run the tests and check they fail.** Expected: compile error.
- [ ] **Step 3: Implement.** `HttpPlaceDirectory` uses `httpsGet` with `url.openConnection()`, a 60 s read timeout (Render wakes slowly) and the size caps.
- [ ] **Step 4: Run the tests and the build.** Run `./gradlew :app:testDebugUnitTest assembleDebug`. Expected: pass.
- [ ] **Step 5: Commit.** Message: `feat(registry): download and keep every place's list`

### Task 4: Judge Wi-Fi against every place

**Files:**
- Create: `data/registry/CombinedRegistry.kt`: `mergeRegistries` and `CombinedRegistryRepository`
- Modify: `di/AppContainer.kt`:
  - `placeLists = PlaceListsRepository(HttpPlaceDirectory(serverSettings::current), PlaceListStore(File(filesDir, "places")))`, started on `appScope` (`loadLocal()` then `sync()`);
  - `registryRepository = CombinedRegistryRepository(syncingRegistry, placeLists, appScope)`.
  - Everything that uses `registryRepository.state` (scan repository, QR verifier, exit-path checker, Manage registry) then sees the combined list.
- Modify: `data/wifi/WifiScanRepository.kt`, `registryInfo`: the source label reads "signed lists: campus v3 + 2 places" when places exist, else today's text. It reads the place count from a new optional constructor parameter `placeCount: () -> Int = { 0 }`.
- Test: `app/src/test/java/com/example/wave/data/registry/CombinedRegistryTest.kt`

**Interfaces:**
- Consumes: Task 3 `PlaceListsRepository.lists`, `PlacesSync`; existing `SyncingRegistryRepository`, `RegistryState`.
- Produces:
  ```kotlin
  /** Pure. campus may be null; places may be empty; null when both are empty. */
  fun mergeRegistries(campus: Registry?, places: List<Registry>): Registry?
  class CombinedRegistryRepository(campus: SyncingRegistryRepository, places: PlaceListsRepository, scope: CoroutineScope) : RegistryRepository {
      override val state: StateFlow<RegistryState>   // Stale if the campus one is Stale, else Ready; Missing (with the campus problem) only when nothing at all
      override suspend fun refresh(): RegistryState   // refreshes both
  }
  ```
  - With **no campus registry**, the merged registry uses schema `wave-registry/1`, `keyId "places"`, the earliest `issuedAt` and the latest `expiresAt` of the places.

- [ ] **Step 1: Write the failing tests:**
  - **`joinsSameNetworkAcrossPlaces`:** `BSU-WiFi` WPA2 in campus with AP1, and in place X with AP2, gives one network with AP1 and AP2. A different security makes two networks.
  - **`approvalBeatsAnotherPlacesBlock`:** place A blocks AP2, and place B approves AP2. AP2 is not in the merged `blocked`. A block that nobody approves stays.
  - **`asnsAreJoined`**
  - **`versionIsTheSum`:** campus v3 plus places v2 and v5 gives 10. Places only gives the sum of the places.
  - **`nothingIsNull`**
  - **`combinedStateFollowsBoth`:**
    - Use fake flows: the campus is Missing and one place exists → Ready (merged);
    - the campus is Stale → Stale;
    - both empty → Missing with the campus problem.
  - **`classifierVerifiesAPlaceNetwork`:** using the real `RegistryClassifier` on the merged registry, an observation matching place X's AP2 (ssid, bssid, security, rssi in range) gives `Verdict.VERIFIED`, and an unknown BSSID with the same SSID gives `Verdict.WARNING`.
- [ ] **Step 2: Run the tests and check they fail.** Expected: compile error.
- [ ] **Step 3: Implement** `mergeRegistries`, `CombinedRegistryRepository` (`combine` on `scope`, `stateIn(Eagerly)`) and the wiring.
- [ ] **Step 4: Run the tests and the build.** Run `./gradlew :app:testDebugUnitTest assembleDebug`. Expected: pass.
- [ ] **Step 5: Commit.** Message: `feat(registry): judge Wi-Fi against every place's list`

### Task 5: Phone rehearsal (Xiaomi, with the user)

- [ ] **Step 1: Deploy Task 1 to Render** (with the user's OK: the branch must reach `main`, or Render is pointed at the branch). Otherwise test against the live server without gzip: the phone works either way.
- [ ] **Step 2: Install on the Xiaomi** and check WAVE is in front before any taps.
- [ ] **Step 3: Make a list that only the place server knows.**
  - The user approves a network in the Wi-Fi radar that is **not** in the built-in list (for example the phone hotspot of a second phone, or a neighbour's network seen from home).
  - Then Publish to WAVE Test.
- [ ] **Step 4: Check it's Verified from the place list.**
  - Clear the saved place lists: `adb shell run-as com.example.wave rm -r files/places`.
  - Networks → Refresh.
  - That network shows **Verified**, the details show the source label "+ 1 place", and `files/places/wave-test.json` exists.
- [ ] **Step 5: Check a copy that isn't the real access point gets Warning.** A second device with the same Wi-Fi name but another BSSID shows **Warning**. Skip this if no second device is available.
- [ ] **Step 6: Ledger the results.** Then remind the user to delete the `wave-test` row in Supabase (home Wi-Fi details).
