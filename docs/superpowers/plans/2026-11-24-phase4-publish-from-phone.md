# Phase 4: Publish from the Phone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A place admin taps **Publish** in Manage registry and confirms with fingerprint or screen lock. The phone signs the place's list with its Keystore key, uploads the package to the WAVE server, and shows the server's answer.

**Architecture:**
- **`PlacePublisher`** (in `data/admin/`, pure, tested with fakes) turns the registry draft plus the installed ID card into a place registry (CONTRACT §7) and asks the server for the next version. It signs through an `AdminSigner` and uploads through a `PlaceServer`.
- **Real implementations:**
  - `KeystoreAdminKeys` signs (SHA256withECDSA, DER);
  - `HttpPlaceServer` calls `GET /v1/places` and `POST /v1/places/{id}/registry` (CONTRACT §4).
- **UI:** Manage registry's publish card shows a confirm dialog, then the system unlock prompt (framework `android.hardware.biometrics.BiometricPrompt`, no new library), then the result.

**Tech Stack:** Kotlin and Compose, Android Keystore, `java.security.Signature`, `HttpsURLConnection`, framework `BiometricPrompt`, `org.json`.

**Spec:** `docs/superpowers/specs/2026-11-24-multi-place-admins.md` (Flows 2; phase 4 row), `docs/CONTRACT.md` §2, §4, §7

## Global Constraints

- **A place registry** (CONTRACT §7) is a §2 registry plus `"place_id"`:
  - `key_id` = the ID card's `cert_id`;
  - `issued_at` = now, whole seconds;
  - `expires_at` = the earlier of now + 180 days and the card's `expires_at`;
  - `version` = the server's version for that place + 1, or 1 when the place isn't listed;
  - networks non-empty (§2).
- **Signature:** ECDSA P-256 / SHA-256, DER, over `"WAVE-REGISTRY-v1\n"` + the exact registry bytes that are sent. Never re-serialize after signing.
- **Package:** `{"place_id","registry_b64url","signature_b64url","cert"}`, with base64url and no padding.
- **Only a valid installed ID card publishes.** The private key never leaves the Keystore. The key is unlocked for 30 s by the system prompt (fingerprint, face or screen lock).
- **New permission:** `android.permission.USE_BIOMETRIC` (normal, no runtime prompt) in `AndroidManifest.xml`, which is a shared file. Needs the user's OK.
- **CLAUDE.md rules:** a `@Preview` for each new UI state, touch targets ≥ 48dp, run `./gradlew assembleDebug` after each change. Device: Xiaomi `be387ebf`, and the user tests the UI by hand.
- Without a valid card (debug bypass), the old "Save list for publishing" (file export) stays as it is.
- Branch: `feature/admin-publish`, from `main`.

## Review Focus

1. **Server unreachable or asleep** (Render's free plan wakes in about 1 min): the admin gets "Can't reach the WAVE server. Try again in a minute." and nothing is signed. → Task 2 `serverDownBeforeSigning`.
2. **Another admin published first** (`STALE_VERSION`): the admin is told "A newer list is already published. Tap Publish again." Publishing again fetches the new version. → Task 2 `staleVersionPassesThrough`.
3. **The key can't be used:** the unlock timed out (`UserNotAuthenticatedException`), or the key was removed or invalidated (screen lock changed: `KeyPermanentlyInvalidatedException`, or the key is missing). The admin sees "Unlock again" or "This phone's admin key is gone. Remove admin and enrol again.", never a crash. → Task 2 `signerFailures`.
4. **An ID card that expires before 180 days:** the list's `expires_at` is capped at the card's, or the server refuses it with `EXPIRED`. → Task 2 `expiryCappedAtCard`.
5. **An empty list** (nothing approved): Publish is refused on the phone with "Approve at least one network first", because §2 requires networks. → Task 2 `emptyListIsRefused`.

---

### Task 1: Signing key and server client

**Files:**
- Modify: `app/src/main/java/com/example/wave/data/admin/AdminKeys.kt`: add `AdminSigner` and `KeystoreAdminKeys.sign`
- Create: `app/src/main/java/com/example/wave/data/admin/PlaceServer.kt`: the interface, `HttpPlaceServer`, and the pure parsers
- Modify: `app/src/main/java/com/example/wave/data/net/HttpsGet.kt`: add `httpsPostJson`
- Test: `app/src/test/java/com/example/wave/data/admin/PlaceServerJsonTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  interface AdminSigner { fun sign(data: ByteArray): ByteArray }   // DER ECDSA; throws KeyNeedsUnlock, KeyGone
  class KeyNeedsUnlock : Exception()      // UserNotAuthenticatedException
  class KeyGone : Exception()             // no key, or KeyPermanentlyInvalidatedException
  // KeystoreAdminKeys : AdminKeys, AdminSigner

  data class UploadAnswer(val result: String, val version: Int?)
  interface PlaceServer {
      suspend fun latestVersion(placeId: String): Int        // 0 when not listed; throws IOException
      suspend fun upload(placeId: String, packageJson: String): UploadAnswer   // throws IOException (no answer)
  }
  class HttpPlaceServer(baseUrl: () -> String?) : PlaceServer
  object PlaceServerJson {
      fun latestVersion(body: String, placeId: String): Int  // {"places":[{place_id,version,..}]} -> version or 0; throws on junk
      fun answer(body: String): UploadAnswer                  // {"result":..,"version"?:..}; throws on junk
  }
  suspend fun httpsPostJson(open: () -> URLConnection, body: String, maxBytes: Int): Pair<Int, String>  // any HTTP status, its body
  ```
  - Every server answer with a JSON `result` (200, 400, 403, 409, 413, 503) is an `UploadAnswer`. Only no answer, or an answer that isn't JSON, throws `IOException`.
  - The base URL comes from `ServerSettings.current()`. A missing URL throws `IOException("No WAVE server address set")`.

- [ ] **Step 1: Write the failing tests** in `PlaceServerJsonTest`:
  - `latestVersion("""{"places":[{"place_id":"a","version":3},{"place_id":"wave-test","version":7}]}""", "wave-test") == 7`
  - an absent place gives `0`, and `{"places":[]}` gives `0`;
  - `"nope"` and `{"places":5}` throw;
  - `answer("""{"result":"STORED","place_id":"x","version":2}""") == UploadAnswer("STORED", 2)`;
  - `answer("""{"result":"STALE_VERSION"}""") == UploadAnswer("STALE_VERSION", null)`;
  - `answer("<html>")` throws.
- [ ] **Step 2: Run the tests and check they fail.** Run `./gradlew :app:testDebugUnitTest --tests "*PlaceServerJsonTest"`. Expected: compile error.
- [ ] **Step 3: Implement.**
  - `PlaceServerJson` with `org.json`, `HttpPlaceServer`, and `httpsPostJson`, which reads `errorStream` for codes other than 2xx and shares `readAtMost` with `httpsGet`.
  - `KeystoreAdminKeys.sign`: `Signature.getInstance("SHA256withECDSA")` with the Keystore private key. `UserNotAuthenticatedException` becomes `KeyNeedsUnlock`; `KeyPermanentlyInvalidatedException` or a missing key becomes `KeyGone`.
- [ ] **Step 4: Run the tests and the build.** Run `./gradlew :app:testDebugUnitTest assembleDebug`. Expected: pass, BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.** Message: `feat(admin): sign with the Keystore key; place server client`

### Task 2: PlacePublisher

**Files:**
- Modify: `data/admin/RegistryJsonWriter.kt`: `write(r: Registry, placeId: String? = null)` puts `"place_id"` right after `"key_id"` when it is given
- Modify: `data/admin/AdminEnrollment.kt`: `fun validCard(): Pair<AdminCertificate, String>?`, returning the card and its exact text when it is VALID now
- Create: `data/admin/PlacePublisher.kt`
- Test: `app/src/test/java/com/example/wave/data/admin/PlacePublisherTest.kt` (reuses `FakeAdminKeys`, `certify` and `newP256` from `AdminEnrollmentTest.kt`; `FakeAdminKeys` gains `AdminSigner` with a JVM P-256 private key)

**Interfaces:**
- Consumes:
  - Task 1: `AdminSigner`, `KeyNeedsUnlock`, `KeyGone`, `PlaceServer`, `UploadAnswer`;
  - phase 3: `AdminEnrollment`, `AdminCertificate`;
  - existing: `RegistryDraft.toRegistry`.
- Produces:
  ```kotlin
  sealed interface PublishOutcome {
      data class Answered(val answer: UploadAnswer, val version: Int) : PublishOutcome   // the server's word
      data object NotAdmin : PublishOutcome            // no valid ID card
      data object NothingToPublish : PublishOutcome    // no approved networks
      data object ServerUnreachable : PublishOutcome
      data object NeedsUnlock : PublishOutcome
      data object KeyGone : PublishOutcome
  }
  class PlacePublisher(enrollment: AdminEnrollment, signer: AdminSigner, server: PlaceServer, clock: () -> Instant = Instant::now) {
      suspend fun publish(draft: RegistryDraft): PublishOutcome   // never throws except CancellationException
  }
  fun RegistryDraft.toPlaceRegistry(now: Instant, version: Int, cert: AdminCertificate): Registry
  ```
  **`publish` order:**
  1. A valid card, or `NotAdmin`.
  2. A non-empty `approved` list, or `NothingToPublish`.
  3. `latestVersion`; an `IOException` gives `ServerUnreachable`, and nothing is signed.
  4. Build the bytes with `RegistryJsonWriter.write(reg, placeId)`.
  5. `sign(DOMAIN + bytes)`.
  6. `upload`; an `IOException` gives `ServerUnreachable`.
  7. Return `Answered`.

- [ ] **Step 1: Write the failing tests:**
  - **`publishesASignedPlacePackage`:**
    - Setup: enrolled card with place `wave-test` and cert_id `0123456789abcdef`, the server says version 4, and the draft has 1 approved AP.
    - The fake server got exactly one upload, for `wave-test`.
    - The package JSON has exactly the 4 fields, and `cert` equals the installed card text.
    - The decoded registry has `"place_id":"wave-test"`, `"key_id":"0123456789abcdef"` and `"version":5`.
    - The signature verifies with the phone's public key (`Signature("SHA256withECDSA")`) over `"WAVE-REGISTRY-v1\n".toByteArray() + registryBytes`.
    - The outcome is `Answered(UploadAnswer("STORED", 5), 5)`.
  - **`notListedStartsAtVersion1`:** the server returns 0, so the version is 1.
  - **`expiryCappedAtCard`:** a card that expires in 30 days gives a registry `expires_at` equal to the card's `expires_at`. A card valid for 400 days gives now + 180 days.
  - **`serverDownBeforeSigning`:** `latestVersion` throws `IOException`, so the outcome is `ServerUnreachable` and the signer was never called.
  - **`staleVersionPassesThrough`:** the server answers `STALE_VERSION`, so the outcome is `Answered(UploadAnswer("STALE_VERSION", null), 5)`.
  - **`signerFailures`:** a signer that throws `KeyNeedsUnlock` gives `NeedsUnlock`; one that throws `KeyGone` gives `KeyGone`. Nothing is uploaded.
  - **`emptyListIsRefused`:** `NothingToPublish`, with no server call.
  - **`notEnrolled`:** `NotAdmin`, with no server call.
  - **`writerPutsPlaceIdAfterKeyId`:** `write(reg, "wave-test")` contains `"key_id": "…",\n  "place_id": "wave-test",`, and `write(reg)` has no `place_id`.
- [ ] **Step 2: Run the tests and check they fail.** Run `./gradlew :app:testDebugUnitTest --tests "*PlacePublisherTest"`. Expected: compile error.
- [ ] **Step 3: Implement** `toPlaceRegistry` (reusing `toRegistry` and then `copy(version, keyId, expiresAt)`), `validCard`, the writer parameter, and `PlacePublisher`.
- [ ] **Step 4: Run the tests and the build.** Run `./gradlew :app:testDebugUnitTest assembleDebug`. Expected: pass.
- [ ] **Step 5: Commit.** Message: `feat(admin): sign and upload the place list (CONTRACT §7)`

### Task 3: Publish in Manage registry

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`: `<uses-permission android:name="android.permission.USE_BIOMETRIC" />` (needs the user's OK)
- Modify: `di/AppContainer.kt`:
  - hold one `KeystoreAdminKeys`, shared by `adminEnrollment` and the publisher;
  - `val placePublisher = PlacePublisher(adminEnrollment, keys, HttpPlaceServer(serverSettings::current))`.
- Create: `ui/screens/tools/UnlockPrompt.kt`:
  ```kotlin
  suspend fun Context.confirmWithScreenLock(title: String, subtitle: String): Boolean
  ```
  It uses the framework `BiometricPrompt`:
  - API 30+: `setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)`;
  - API 29: `setDeviceCredentialAllowed(true)`;
  - success gives true; error or cancel gives false.
- Modify: `ui/screens/tools/SurveyScreen.kt` (`SurveyViewModel.publishToServer()`, `SurveyRoute`) and `AdminParts.kt` (`AdminActionsCard`):
  - **With a valid card:** the button reads **"Publish to <placeName>"** and the hint reads "Signed on this phone with your admin key and sent to the WAVE server."
    1. Tap opens a confirm dialog: "Publish N networks and M blocked to <placeName>? Phones will use this list." with Publish / Cancel.
    2. Then `confirmWithScreenLock("Publish Wi-Fi list", placeName)`.
    3. Then `publish()`, with a loading state.
    4. If the result is `NeedsUnlock`, prompt once more and retry once.
    5. On `STORED`, the draft's `baseVersion` becomes the published version.
  - **Without a valid card** (debug bypass): today's "Save list for publishing" (file) stays.

**Messages:**

| Outcome | Message |
|---|---|
| `STORED` | "Published version N to <placeName>. Phones get it on their next update." |
| `STALE_VERSION` | "A newer list is already published. Tap Publish again." |
| `EXPIRED` | "Your ID card has expired. Ask the WAVE owner for a new one." |
| `NOT_YET_VALID` | "The server's clock and this phone's disagree. Check the phone's date." |
| `UNKNOWN_KEY` / `BAD_SIGNATURE` / `WRONG_PLACE` | "The server refused this list (<RESULT>)." |
| `MALFORMED` / `TOO_LARGE` | "The server couldn't read this list (<RESULT>)." |
| `UPLOADS_OFF` / `STORAGE_UNAVAILABLE` | "The server can't save lists right now. Try again later." |
| `ServerUnreachable` | "Can't reach the WAVE server. Try again in a minute." |
| `NeedsUnlock` (after the retry) or cancelled prompt | "Not published: unlock was cancelled." |
| `KeyGone` | "This phone's admin key is gone. Remove admin and enrol again." |
| `NotAdmin` | "Install your admin ID card first." |
| `NothingToPublish` | "Approve at least one network first." |

These go in a pure `fun publishMessage(outcome: PublishOutcome, placeName: String): String`, with a unit test covering each row.

- [ ] **Step 1: Ask the user for OK on `USE_BIOMETRIC`.** Then add it, and wire `AppContainer`.
- [ ] **Step 2: Write the failing test `PublishMessageTest`**, one assertion per table row. Run it: compile error.
- [ ] **Step 3: Implement** `publishMessage`, `UnlockPrompt`, the ViewModel and the card. Add previews: card enrolled, publishing (loading), published message, and the confirm dialog.
- [ ] **Step 4: Run the tests and the build.** Run `./gradlew :app:testDebugUnitTest assembleDebug`. Expected: pass.
- [ ] **Step 5: Commit.** Message: `feat(admin): Publish to <place> from Manage registry`

### Task 4: Phone rehearsal (Xiaomi, with the user)

- [ ] **Step 1:** Install on `be387ebf` and check WAVE is in front before any taps.
- [ ] **Step 2: The user publishes.** Approve 1–2 networks in the Wi-Fi radar → Manage registry → **Publish to WAVE Test** → fingerprint. Expected: "Published version 2 to WAVE Test" (the laptop test uploaded version 1).
- [ ] **Step 3: Check on the laptop.**
  - `curl https://wave-fkn3.onrender.com/v1/places` shows `wave-test` at version 2.
  - Download `/v1/places/wave-test/registry` to `out/test/from-phone.json` and run `verify-package --pub keys/wave-root-2026.public.json`. Expected: `Result: VALID`.
- [ ] **Step 4: Publish again with no change.** Expected: version 3, because each publish is a new version. Then the user cancels the fingerprint prompt. Expected: "Not published: unlock was cancelled."
- [ ] **Step 5:** Ledger the results.
