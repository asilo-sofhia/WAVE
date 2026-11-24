# Phase 3: Admin Enrolment on the Phone Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A phone becomes a place admin phone:
1. It makes its own key in Android Keystore.
2. It shares a `WAVEREQ:1:` request.
3. It installs the owner's `WAVECERT:1:` ID card.
4. It shows "Admin · <place>".

**Architecture:**
- **Pure Kotlin checks** in `verify/` read certificates and build requests, matching `wave_sign.py` (CONTRACT §6). They are tested against `test_vectors.json`.
- **Data layer** in `data/admin/`:
  - an `AdminKeys` interface over Android Keystore, faked in tests;
  - an `AdminEnrollment` store that saves the request and certificate on the phone.
- **UI:** the existing "Manage registry" gate (`AdminAccessScreen`) becomes the enrolment screen. A `WAVECERT` scanned in the Scan QR tab is sent there.

**Tech Stack:**
- Kotlin and Compose.
- Android Keystore (P-256, ES256).
- Tink `Ed25519Verify` (already a dependency).
- `org.json` (built into Android). **New: test-only** `org.json:json`, so JVM unit tests can run code that uses `JSONObject`. Needs the user's OK.

**Spec:** `docs/superpowers/specs/2026-11-24-multi-place-admins.md` (Flows 1; phase 3 row), `docs/CONTRACT.md` §6

## Global Constraints

- **Formats match `wave_sign.py` exactly:**
  - prefixes `WAVEREQ:1:` and `WAVECERT:1:`;
  - domain `"WAVE-CERT-v1\n"`;
  - schema `wave-admin-cert/1`;
  - `admin_key_alg` = `ES256`;
  - `place_id` `^[a-z0-9][a-z0-9-]{0,31}$`;
  - `cert_id` `^[0-9a-f]{16}$`;
  - names 1–64 characters, not blank;
  - times `yyyy-MM-ddTHH:mm:ssZ` exactly;
  - `expires_at` after `issued_at`;
  - results checked in this order: MALFORMED → UNKNOWN_KEY → BAD_SIGNATURE → NOT_YET_VALID → EXPIRED.
- **Only root keys certify** (CONTRACT §6). `WaveKeys.roots` is a separate map from `WaveKeys.trusted` and never includes `wave-2026-a`, `wave-dev` or any other registry/QR key. Production root: `"wave-root-2026" to "GIEvVDzjd_HZEGv2cLikRTGqzKxhz5AiGXA2f-ZIQQw"`.
- **The private key never leaves the Keystore.** Only the public key (SPKI DER, base64url) goes into the request.
- **The key needs the screen lock:** `setUserAuthenticationRequired(true)`, valid 30 s after fingerprint, face or PIN. Signing comes in phase 4; phase 3 only creates the key and reads its public half, which needs no unlock.
- **CLAUDE.md applies:**
  - screens use the `data/` interfaces only;
  - real vs fake implementations are swapped in `AppContainer`;
  - a `@Preview` for every screen state;
  - touch targets ≥ 48dp;
  - `contentDescription` on icons;
  - run `./gradlew assembleDebug` after each change.
- **Device testing on the Xiaomi phone** (`be387ebf`), never the emulator. The user tests the admin UI by hand; I install the app and do the laptop side.
- Branch: `feature/admin-enrol`, from `main`.

## Review Focus

1. **Someone else's ID card** (a copied QR made for another phone's key) must be refused as `NOT_THIS_PHONE` and never show "Admin". → Task 2 `installCertForOtherKeyIsRefused`.
2. **Junk scanned as a certificate** must give `MALFORMED`, never a crash:
   - `"WAVECERT:1:"`, `"WAVECERT:1:a.b.c"`, invalid base64, JSON that isn't an object, missing fields;
   - a non-P-256 key, a name of 65 characters;
   - a time like `2026-11-24T00:00:00+00:00`.

   → Task 1 `junkIsMalformedNeverThrows`.
3. **An expired ID card,** at install or later, must show "ID card expired on <date>" and must not open Manage registry in release builds. → Task 2 `enrolledCertExpiresLater`, Task 3 preview `Expired`.
4. **A phone with no screen lock** can't create a key that needs unlocking. The app must say "Set a screen lock first" instead of crashing. → Task 3 (`KeystoreAdminKeys.create` throws `NoScreenLock`, and the screen shows the message). Checked on the device in Task 4.
5. **A saved enrolment file that is corrupt or cut short** (the app was killed while saving) must load as "not started", never crash. → Task 2 `corruptFileLoadsAsNotStarted`.

---

### Task 1: Certificate check and request text (pure Kotlin)

**Files:**
- Modify: `gradle/libs.versions.toml` and `app/build.gradle.kts`: `testImplementation("org.json:json:20240303")` (needs the user's OK)
- Modify: `app/src/main/java/com/example/wave/verify/WaveKeys.kt`: add `val roots: Map<String, String>`
- Create: `app/src/main/java/com/example/wave/verify/AdminCert.kt`
- Create: `app/src/main/java/com/example/wave/verify/AdminRequest.kt`
- Test: `app/src/test/java/com/example/wave/verify/AdminCertTest.kt` and `AdminRequestTest.kt`

**Interfaces:**
- Produces, in `verify/AdminCert.kt`:
  ```kotlin
  enum class CertResult { VALID, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, NOT_YET_VALID, EXPIRED }
  data class AdminCertificate(
      val certId: String, val placeId: String, val placeName: String, val adminName: String,
      val adminPublicKey: String,  // base64url SPKI DER, as in the certificate
      val issuedAt: Instant, val expiresAt: Instant, val rootKeyId: String,
  )
  object AdminCert {
      const val PREFIX = "WAVECERT:1:"
      val DOMAIN: ByteArray  // "WAVE-CERT-v1\n"
      /** Never throws. The certificate is returned only when its root signature checked out (VALID, NOT_YET_VALID, EXPIRED). */
      fun verify(text: String, rootKeys: Map<String, String> = WaveKeys.roots, at: Instant): Pair<CertResult, AdminCertificate?>
  }
  ```
- Produces, in `verify/AdminRequest.kt`:
  ```kotlin
  object AdminRequest {
      const val PREFIX = "WAVEREQ:1:"
      /** Null if fine, else a short message for the screen ("Name: 1–64 characters", "Place ID: lowercase letters, digits and -, up to 32"). */
      fun problem(adminName: String, placeId: String): String?
      /** Throws IllegalArgumentException when problem() isn't null. Body: {"admin_name","place_id","admin_key_alg":"ES256","admin_public_key"} as base64url (no padding) compact JSON. */
      fun make(adminName: String, placeId: String, publicKeySpki: ByteArray): String
  }
  ```
  The name is trimmed before checking and saving. Length counts code points, as Python's `len` does.

- [ ] **Step 1: Ask the user for OK on the test-only `org.json:json` dependency** (CLAUDE.md: no libraries without asking). Then add it.
- [ ] **Step 2: Write the failing tests.**
  - **`AdminCertTest.vectorCasesMatchPython`:** for every entry in `cert_cases` of `app/src/test/resources/test_vectors.json`, `verify(cert, rootKeys = root_public_keys, at = Instant.parse(at)).first.name == expect`.
    - Read the file with `org.json`.
    - This includes `signed_by_registry_key`, which must give `UNKNOWN_KEY`.
  - **`AdminCertTest.validCertFields`:** the `valid` case gives `placeId == "testplace"`, `placeName == "Test Place"`, `adminName == "Test Admin"`.
  - **`AdminCertTest.junkIsMalformedNeverThrows`:** the Review Focus 2 inputs each give `(MALFORMED, null)`.
  - **`AdminCertTest.rootsAreSeparateFromRegistryKeys`:** `WaveKeys.roots.keys` and `WaveKeys.trusted.keys` share nothing, and `WaveKeys.roots` contains `wave-root-2026`.
  - **`AdminRequestTest.makeHasExactlyTheFourFields`:**
    - Make a P-256 SPKI with `KeyPairGenerator.getInstance("EC")` and `ECGenParameterSpec("secp256r1")`.
    - Decode `make("  Ana Cruz ", "batstateu", spki)`: it starts with `PREFIX`, and the JSON has exactly the keys `admin_name="Ana Cruz"`, `place_id`, `admin_key_alg="ES256"`, and `admin_public_key` = base64url of `spki` without padding.
  - **`AdminRequestTest.problems`:**
    - `""` and `"   "` are a name problem, and so is 65 × `"a"`.
    - `"BatStateU"`, `"-x"` and 33 × `"a"` are a place problem.
    - 64 × `"a"` with `"batstateu"` gives null.
- [ ] **Step 3: Run the tests and check they fail.** Run `./gradlew :app:testDebugUnitTest --tests "com.example.wave.verify.Admin*"`. Expected: compile errors (`AdminCert`, `AdminRequest`, `WaveKeys.roots` don't exist).
- [ ] **Step 4: Implement `AdminCert.verify`.**
  - Split off `PREFIX`, then split once on `.` into exactly 2 parts. Decode with base64url (`Base64.getUrlDecoder()`). Parse with `JSONObject`.
  - Check `schema`, `admin_key_alg`, the `cert_id` and `place_id` patterns, the names, and the times. Read the times with the strict regex `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$` and then `Instant.parse`.
  - Load the admin key with `KeyFactory.getInstance("EC")` and `X509EncodedKeySpec`, and require `(key as ECPublicKey).params.curve.field.fieldSize == 256` together with the P-256 order.
  - Then check the root key exists, then the signature with `Ed25519Verify` over `DOMAIN + certBytes` (the decoded bytes, never re-serialized), then the times.
  - Wrap all of it in `try/catch (e: Exception)`, which gives MALFORMED.
- [ ] **Step 5: Implement `AdminRequest`.** Build the body with `JSONObject` and encode with `Base64.getUrlEncoder().withoutPadding()`.
- [ ] **Step 6: Add `WaveKeys.roots`.** Use the production root above. Comment: "Only these keys certify place admins (CONTRACT §6). Never add registry/QR keys here."
- [ ] **Step 7: Run the tests and the build.** Run `./gradlew :app:testDebugUnitTest` and `./gradlew assembleDebug`. Expected: all unit tests pass (128 before, plus the new ones) and BUILD SUCCESSFUL.
- [ ] **Step 8: Commit.** Message: `feat(admin): read admin ID cards and make enrolment requests (CONTRACT §6)`

### Task 2: Admin key and enrolment store

**Files:**
- Create: `app/src/main/java/com/example/wave/data/admin/AdminKeys.kt`: the interface plus `KeystoreAdminKeys`
- Create: `app/src/main/java/com/example/wave/data/admin/AdminEnrollment.kt`
- Test: `app/src/test/java/com/example/wave/data/admin/AdminEnrollmentTest.kt`, with `FakeAdminKeys` and a `certify(...)` helper in the test folder

**Interfaces:**
- Consumes (Task 1):
  - `AdminCert.verify`, `AdminCertificate`, `CertResult`;
  - `AdminRequest.make` and `AdminRequest.problem`;
  - `WaveKeys.roots`.
- Produces:
  ```kotlin
  interface AdminKeys {
      fun publicKey(): ByteArray?          // SPKI DER, or null when there is no key
      fun create(): ByteArray              // makes the key if missing, returns its SPKI; throws NoScreenLock
      fun delete()
  }
  class NoScreenLock : Exception()
  class KeystoreAdminKeys(context: Context) : AdminKeys   // alias "wave_admin_p256"

  sealed interface AdminStatus {
      data object NotStarted : AdminStatus
      data class Requested(val adminName: String, val placeId: String, val request: String) : AdminStatus
      data class Enrolled(val cert: AdminCertificate, val validity: CertResult /* VALID, NOT_YET_VALID or EXPIRED */) : AdminStatus
  }
  enum class InstallResult { INSTALLED, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, NOT_YET_VALID, EXPIRED, NOT_THIS_PHONE, NO_KEY }

  class AdminEnrollment(
      file: File, keys: AdminKeys,
      rootKeys: Map<String, String> = WaveKeys.roots, clock: () -> Instant = Instant::now,
  ) {
      val status: StateFlow<AdminStatus>
      fun request(adminName: String, placeId: String): String   // throws IllegalArgumentException, NoScreenLock
      fun install(certText: String): InstallResult
      fun refresh()                                              // re-checks the certificate's dates (call when the screen opens)
      fun remove()                                               // deletes the key and the file
  }
  ```
  - **Saved file** `files/admin/enrolment.json`: `{"admin_name","place_id","request","cert"?}`. Write it atomically: write `enrolment.json.tmp`, then rename.
  - **Status on load:**
    - no file, or a corrupt file → `NotStarted`;
    - `cert` present and its signature checks → `Enrolled`;
    - otherwise → `Requested`.
  - **`install`** refuses a certificate whose `admin_public_key` bytes differ from `keys.publicKey()`, with `NOT_THIS_PHONE`. It refuses `NOT_YET_VALID` and `EXPIRED` at install. Only `INSTALLED` changes the file.
  - **`KeystoreAdminKeys.create()`:**
    - `KeyguardManager.isDeviceSecure` false → throw `NoScreenLock`.
    - Generate EC `secp256r1` with `PURPOSE_SIGN`, `DIGEST_SHA256` and `setUserAuthenticationRequired(true)`.
    - API ≥ 30: `setUserAuthenticationParameters(30, AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL)`. API 29: `setUserAuthenticationValidityDurationSeconds(30)`.
    - Try `setIsStrongBoxBacked(true)` first, and on `StrongBoxUnavailableException` retry without it.
    - Return `keyStore.getCertificate(alias).publicKey.encoded`.

- [ ] **Step 1: Write the failing tests** (`FakeAdminKeys` uses a JVM P-256 pair; the root is a Tink `Ed25519Sign.KeyPair`; `certify(spki, place, name, root, issued, days)` builds compact sorted JSON like Python's and signs `DOMAIN + bytes`):
  - **`requestCreatesKeyOnceAndSaves`:** `request` twice gives the same public key in both texts. Status `Requested`. A new `AdminEnrollment` on the same file sees `Requested`.
  - **`installValidCert`:** gives `INSTALLED`. Status `Enrolled` with `validity == VALID` and `cert.placeName`. Survives a reload.
  - **`installCertForOtherKeyIsRefused`:** gives `NOT_THIS_PHONE`, and the status stays `Requested`.
  - **`installExpiredOrUnknownRoot`:** an expired cert gives `EXPIRED`; another root gives `UNKNOWN_KEY`; `"nope"` gives `MALFORMED`. The status is unchanged each time.
  - **`installWithoutKey`:** a fresh store gives `NO_KEY`.
  - **`enrolledCertExpiresLater`:** install with clock = day 1, then move the clock past expiry and `refresh()`. Status `Enrolled` with `validity == EXPIRED`.
  - **`corruptFileLoadsAsNotStarted`:** for each of `"{"`, `""` and `"[]"` written to the file, the status is `NotStarted`.
  - **`removeClearsEverything`:** `FakeAdminKeys.publicKey()` is null, the file is gone, and the status is `NotStarted`.
- [ ] **Step 2: Run the tests and check they fail.** Run `./gradlew :app:testDebugUnitTest --tests "com.example.wave.data.admin.AdminEnrollmentTest"`. Expected: compile errors.
- [ ] **Step 3: Implement `AdminKeys.kt` and `AdminEnrollment.kt`.**
- [ ] **Step 4: Run all unit tests and the build.** Run `./gradlew :app:testDebugUnitTest` and `./gradlew assembleDebug`. Expected: all pass, BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.** Message: `feat(admin): Keystore admin key and saved enrolment`

### Task 3: Enrolment screen, scan routing and "Admin · Place"

**Files:**
- Modify: `di/AppContainer.kt`: `val adminEnrollment = AdminEnrollment(File(filesDir, "admin/enrolment.json"), KeystoreAdminKeys(context))`
- Modify: `ui/screens/tools/AdminAccessScreen.kt`: becomes `AdminAccessRoute(container, certText: String?, onBack, onContinue, onScanCard)` plus a stateless `AdminAccessScreen(state, actions)`
- Modify: `ui/navigation/Routes.kt` and `WaveNavHost.kt`:
  - `ADMIN_ACCESS = "adminaccess?cert={cert}"`, with `fun adminAccess(cert: String? = null)`;
  - Scan QR's `onScanned`: a payload starting with `AdminCert.PREFIX` goes to `adminAccess(payload)` instead of the QR result.
- Modify: `ui/screens/tools/SurveyScreen.kt`: the top bar subtitle shows "Admin · <placeName>" when enrolled
- Modify: `ui/screens/tools/NetworkToolsScreen.kt`: the Manage registry subtitle reads "Place admin ID card required"

**What each state shows:**

| State | Content |
|---|---|
| `NotStarted` | Title "Become a place admin"<br>Text "Ask the WAVE owner for an admin ID card. Your phone makes its own key; it never leaves this phone."<br>Fields "Your name", "Place ID (e.g. batstateu)"<br>Button "Create request". It is disabled while `AdminRequest.problem` isn't null, and the problem shows under the field. |
| `Requested` | The request text in a selectable box<br>"Share request": `Intent.ACTION_SEND`, `text/plain`, chooser<br>"Copy"<br>"Scan ID card": goes to the Scan QR tab, which has a camera and Gallery<br>"Paste ID card": reads the clipboard and runs `install`<br>"Start over": `remove()` after a confirm dialog |
| `Enrolled` and `VALID` | Green check icon with "Admin · <placeName>", then `adminName` and "ID card valid until <date>"<br>Primary button "Open Manage registry"<br>"Remove admin from this phone" (confirm dialog) |
| `Enrolled` and `EXPIRED` or `NOT_YET_VALID` | Amber clock icon with "ID card expired on <date>" (or "not valid until <date>")<br>"Start over" |
| Install results | Shown as a message on the card:<br>`NOT_THIS_PHONE` → "This ID card was made for another phone."<br>`UNKNOWN_KEY` / `BAD_SIGNATURE` → "This ID card wasn't made by the WAVE owner."<br>`EXPIRED` → "This ID card has expired."<br>`MALFORMED` → "This isn't a WAVE ID card."<br>`NO_KEY` → "Create a request first."<br>`NoScreenLock` → "Set a screen lock (PIN, pattern or fingerprint) first, then try again." |

- **Debug builds only:** the old "Continue without ID card (debug)" link stays under every state, so testing the admin radar doesn't need a certificate. In release builds, only `Enrolled`+`VALID` opens Manage registry.
- **Install from a scan:** when `certText` comes in from navigation, `install` runs once (`LaunchedEffect(certText)`) and shows the result.

- [ ] **Step 1: Wire `AppContainer`, the routes and the nav host.** Run `./gradlew assembleDebug`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 2: Build the stateless screen** with `@Preview`s for:
  - Not started;
  - Not started with a bad place ID;
  - Requested;
  - Requested with an "another phone" message;
  - Enrolled valid;
  - Expired;
  - No screen lock.

  Then build `AdminAccessRoute`, which collects `adminEnrollment.status`, calls `refresh()` on start, and runs the Share intent and clipboard.
- [ ] **Step 3: Add the Manage registry subtitle and the Network tools wording.**
- [ ] **Step 4: Run all unit tests and the build.** Run `./gradlew :app:testDebugUnitTest` and `./gradlew assembleDebug`. Expected: all pass, BUILD SUCCESSFUL.
- [ ] **Step 5: Commit.** Message: `feat(admin): enrolment screen, ID card scan and Admin · Place`

### Task 4: Phone rehearsal (Xiaomi, with the user)

- [ ] **Step 1: Install on the Xiaomi phone** (`adb -s be387ebf install -r app/build/outputs/apk/debug/app-debug.apk`) and check WAVE is in front before any taps.
- [ ] **Step 2: The user enrols.** Networks ⋮ → Network tools → Manage registry → name "Test Admin", place `wave-test` → Create request → Share. Send it to the laptop (Messenger or email), or I read it with `adb` from the shared text.
- [ ] **Step 3: The laptop side.**
  - Save the request to `backend/out/test/phone-req.txt` as UTF-8.
  - The user runs `certify-admin --request @out/test/phone-req.txt --place wave-test --place-name "WAVE Test" --days 30 --key keys/wave-root-2026.private.pem --png out/test/phone-cert.png`.
  - Then `verify-cert` gives VALID.
- [ ] **Step 4: The user scans** `phone-cert.png` from the laptop screen (Scan QR tab). Expected: "Admin · WAVE Test", and Manage registry shows the subtitle.
- [ ] **Step 5: Check refusals.**
  - Scan the old `out/test/cert.txt` QR (made for the laptop test key): "made for another phone".
  - Scan a Wi-Fi QR: it still opens the normal QR result.
- [ ] **Step 6: Ledger the results.** Any fix found goes through TDD and gets its own commit.
