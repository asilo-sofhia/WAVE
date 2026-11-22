# Admin Wi-Fi Radar + Manage Registry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A campus admin, after the demo admin gate, scans nearby Wi-Fi, reviews each network's details and **approves or blocks** it. **Manage registry** shows the Approved and Blocked lists, starting from the current signed registry, and **exports a draft registry file**. The draft is then signed on the laptop with the campus key and published. Blocked addresses reach every phone and show as **Warning: "Blocked by campus admin"**.

**Architecture:**
- **Signed list format:** the registry (CONTRACT §2) gains an optional `blocked` list. The signing tool validates it, and the app's parser and classifier use it.
- **Draft on the admin phone:** `RegistryDraft`, plain Kotlin. It's seeded from the phone's current verified registry, and approve, block and remove are pure functions. It's saved as one JSON file in app storage, with signal ranges tracked across scans while the admin walks.
- **Screens:** the existing PR #9 screens are reused. The gate now opens **Manage registry** (Approved | Blocked tabs, campus provider, Export). Its "Scan to add" button opens the existing **Network survey**, where tapping a network opens a **Review** sheet with Approve and Block.
- **Signing stays on the laptop.** The phone never holds a private key, and the export is an unsigned draft.

**Tech Stack:** Kotlin, Compose, ViewModel + StateFlow, `org.json` (Android built-in), Storage Access Framework (`ActivityResultContracts.CreateDocument`) for export, Python signing tool. **No new libraries.**

**Spec:**
- `docs/CONTRACT.md` §2 (registry fields; the `blocked` addition is defined here).
- User decisions (2026-11-23):
  - Block is **published** in the signed registry.
  - Manage registry **starts from the current registry**.
  - Approving happens **only inside the admin area** after the gate; the public Wi-Fi radar stays read-only.

## Global Constraints

- **Branch:** `feature/admin-registry` from `main`. Ask before pushing.
- **Shared files** (team agreement per CLAUDE.md; list them in the PR):
  - `model/Registry.kt` (+`BlockedAp`, `Registry.blocked`)
  - `model/Verdict.kt` (+`WarningReason.BLOCKED_BY_ADMIN`)
  - `docs/CONTRACT.md` + `backend/CONTRACT.md` §2, kept identical
  - `backend/signing/wave_sign.py`
- **The `blocked` field (CONTRACT §2):**
  - It's optional: `"blocked": [{"bssid": "xx:xx:…", "ssid": "<name seen>", "reason": "<text>"}]`.
  - Each `bssid` is lowercase and colon-separated, appears once, and is **never also in any `networks[].aps`**.
  - Missing means an empty list, so old registries stay valid and old signatures still verify.
- **Classifier:** an observation whose BSSID is in `blocked` → **WARNING** with `BLOCKED_BY_ADMIN`, whatever its SSID. The title is `"Blocked by campus admin"` and the explanation is `"The campus admin marked this access point as not theirs."` This rule is checked before the name lookup.
- **Draft export:** registry JSON exactly as CONTRACT §2:
  - `schema "wave-registry/1"`;
  - `version` = current registry version + 1, or 1 if none;
  - `key_id "wave-2026-a"`;
  - `issued_at` = now (UTC, seconds), `expires_at` = now + **180 days**;
  - `campus_asns`, `networks`, `blocked`;
  - 2-space indent, LF line endings, UTF-8.

  The file is named `registry.json`. Then run `wave_sign.py sign-registry` on the laptop.
- **Signal range for an approved AP:**
  - `rssi_min = min(observedMin - 10, -85)`, `rssi_max = max(observedMax + 10, -30)`, both clamped to [-100, -10];
  - "observed" = the min and max RSSI seen for that BSSID across the admin's scans this session;
  - an AP that came from the current registry keeps its stored range unless re-approved.
- **Band mapping** for the contract: `2.4GHz`, `5GHz`, `6GHz`. Use the existing `SecurityMapping` for contract security names.
- **Approving rules:**
  - Approve applies to the access points the admin ticks in Review; by default, all radios of that network are ticked **except** those flagged with a made-up address.
  - Approving an AP removes it from Blocked, and blocking removes it from Approved.
  - `OPEN` networks can be approved, but Review shows a warning.
  - Hidden networks (no name) can only be blocked.
- **Admin area only:** Approve and Block never appear on the public radar or on Network details.
- Device checks run on the Xiaomi phone (`be387ebf`), never the emulator. Run `./gradlew testDebugUnitTest assembleDebug` after each task, and backend tests with `.venv/Scripts/python -m pytest tests -q`.

## Review Focus

1. **Export drops access points that are already registered** (e.g. adding the gym removes the library). The draft must start from the current registry. Cover: Task 3 `seedsFromCurrentRegistry`, `exportKeepsSeededAps`.
2. **The same BSSID is both approved and blocked.** That's impossible in the draft, and the signing tool refuses it. Cover: Task 1 `test_blocked_overlapping_aps_is_error`, Task 3 `approveRemovesFromBlocked` / `blockRemovesFromApproved`.
3. **A signal range that's too tight**, from one scan, causes false alarms for normal users. Ranges are widened as in Global Constraints. Cover: Task 3 `singleScanRangeIsWidened`.
4. **Blocked AP using a campus name**, e.g. a known rogue named `BATSTATEU-ICT`. It must show Warning with "Blocked by campus admin" as well as "Not on the official list". Cover: Task 2 `blockedBssidIsWarningWhateverTheName`.
5. **An exported file the signing tool rejects** (bad band, uppercase BSSID, empty networks). The export must pass `verify`/`sign` as-is. Cover: Task 5 Step 4 runs the real `sign-registry` on an exported file.

---

### Task 1: CONTRACT §2 `blocked` + signing tool

**Files:**
- Modify: `docs/CONTRACT.md` and `backend/CONTRACT.md` §2: example, field table row for `blocked`, and one sentence on its meaning.
- Modify: `backend/signing/wave_sign.py` `validate_registry` (~line 207). Validate `blocked` if present:
  - it's a list of objects;
  - each `bssid` matches the lowercase pattern and isn't duplicated;
  - `ssid` is a string, possibly empty;
  - `reason` is a non-empty string;
  - no BSSID is also in `networks[].aps`.
- Test: `backend/tests/test_wave_sign.py`

- [ ] **Step 1: Write the failing tests** (`validate_registry(json bytes)`), each checking the errors list:
  - `test_blocked_is_optional`: no `blocked` → no errors.
  - `test_valid_blocked_list`: one valid entry → no errors.
  - `test_blocked_bad_bssid_is_error`: `"AA:BB:…"` → error mentions `blocked[0]`.
  - `test_blocked_duplicate_is_error`
  - `test_blocked_overlapping_aps_is_error`: the same BSSID in `aps` → error mentions both.
  - `test_blocked_missing_reason_is_error`
- [ ] **Step 2: Run them and check they fail.** Run `.venv/Scripts/python -m pytest tests/test_wave_sign.py -q -k blocked`.
- [ ] **Step 3: Implement** the validation and the CONTRACT text.
- [ ] **Step 4: Run all backend tests.** Expected: all pass, and the existing test vectors are unchanged.
- [ ] **Step 5: Commit.** `git commit -m "feat(contract): optional blocked list in the signed registry"`

---

### Task 2: App reads `blocked` and warns on blocked access points

**Files:**
- Modify: `app/src/main/java/com/example/wave/model/Registry.kt`: `data class BlockedAp(val bssid: String, val ssid: String, val reason: String)` and `Registry.blocked: List<BlockedAp> = emptyList()`.
- Modify: `app/src/main/java/com/example/wave/model/Verdict.kt`: `WarningReason.BLOCKED_BY_ADMIN("Blocked by campus admin", "The campus admin marked this access point as not theirs.")`.
- Modify: `app/src/main/java/com/example/wave/data/registry/RegistryJson.kt`: parse optional `blocked`, with the BSSID pattern required.
- Modify: `app/src/main/java/com/example/wave/domain/RegistryClassifier.kt`: blocked check first; a new `CheckKind`/check line "Campus block list: blocked (<reason>)". Its kind's `reason` maps to `BLOCKED_BY_ADMIN`.
- Test: `app/src/test/java/com/example/wave/domain/RegistryClassifierTest.kt` and `app/src/androidTest/java/com/example/wave/data/registry/RegistryJsonTest.kt`.

- [ ] **Step 1: Write the failing tests:**
  - classifier `blockedBssidIsWarningWhateverTheName`:
    - registry blocks `6a:…:9c`;
    - an observation with SSID `"BSU Free WiFi"` (not registered) → WARNING, reasons contain `BLOCKED_BY_ADMIN`;
    - the same BSSID named like a registered SSID → WARNING with `BLOCKED_BY_ADMIN` **and** `UNREGISTERED_BSSID`.
  - classifier `notBlockedBehavesAsBefore`: an empty block list → existing verdicts unchanged (spot-check one Verified, one Unknown).
  - instrumented `parsesBlockedList` and `missingBlockedIsEmpty`.
- [ ] **Step 2: Run them and check they fail.**
- [ ] **Step 3: Implement.** Check every `when` over `WarningReason` / `CheckKind` still compiles; add the UI label wherever a `when` needs it.
- [ ] **Step 4: Run all tests and build.** Run `./gradlew testDebugUnitTest assembleDebug`, then the instrumented `RegistryJsonTest` on the phone with `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.
- [ ] **Step 5: Commit.** `git commit -m "feat: warn on access points the campus admin blocked"`

---

### Task 3: `RegistryDraft`, the admin's working list (pure logic)

**Files:**
- Create: `app/src/main/java/com/example/wave/data/admin/RegistryDraft.kt`
- Test: `app/src/test/java/com/example/wave/data/admin/RegistryDraftTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class DraftSource { FROM_REGISTRY, SURVEYED }
  data class DraftAp(val bssid: String, val ssid: String, val security: SecurityType, val band: WifiBand,
                     val channel: Int, val building: String, val rssiMin: Int, val rssiMax: Int, val source: DraftSource)
  data class RegistryDraft(
      val baseVersion: Int,             // version of the registry it started from (0 = none)
      val campusAsns: List<CampusAsn>,
      val approved: List<DraftAp>,
      val blocked: List<BlockedAp>,
  ) {
      companion object { fun from(registry: Registry?): RegistryDraft }   // seeds approved + blocked + ASNs
      fun approve(aps: List<DraftAp>): RegistryDraft                       // replaces same BSSID; removes from blocked
      fun block(bssid: String, ssid: String, reason: String): RegistryDraft // removes from approved
      fun remove(bssid: String): RegistryDraft                             // from either list
      fun withCampusAsn(asn: CampusAsn): RegistryDraft                     // replaces ASN 0 placeholders
      fun toRegistry(now: Instant): Registry                               // version = baseVersion + 1, 180-day expiry
  }
  /** Signal range rule from Global Constraints. */
  fun surveyedRange(observedMin: Int, observedMax: Int): IntRange
  /** Min/max RSSI per BSSID across this session's scans. */
  class SignalTracker { fun record(bssid: String, rssi: Int); fun range(bssid: String): IntRange? }
  ```
  `toRegistry` groups approved APs into `networks` by (ssid, security), sorted by ssid then bssid.

- [ ] **Step 1: Write the failing tests:**
  - `seedsFromCurrentRegistry`
  - `exportKeepsSeededAps` (v1 with the library AP + approve the gym AP → `toRegistry` has both, version 2)
  - `approveRemovesFromBlocked`
  - `blockRemovesFromApproved`
  - `approveSameBssidReplaces` (new building/range wins)
  - `removeDeletesFromEitherList`
  - `groupsNetworksBySsidAndSecurity`
  - `noRegistryStartsAtVersion1`
  - `expiryIs180Days`
  - `singleScanRangeIsWidened`: `surveyedRange(-60, -60)` → `-85..-30`
  - `wideScanRangeIsKept`: `surveyedRange(-95, -25)` → `-100..-15`
  - `campusAsnReplacesPlaceholder`: ASN 0 removed when a real one is set
  - `signalTrackerKeepsMinMax`
- [ ] **Step 2: Run them and check they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run them and check they pass.**
- [ ] **Step 5: Commit.** `git commit -m "feat(admin): RegistryDraft working list with approve/block and export"`

---

### Task 4: Saving and exporting the draft (JSON)

**Files:**
- Create: `app/src/main/java/com/example/wave/data/admin/RegistryDraftStore.kt`:
  - the draft is saved as `files/admin/draft.json`;
  - the format is the registry JSON from `RegistryJsonWriter` plus `"base_version"` and per-AP `"source"`;
  - load uses `RegistryJson` helpers;
  - it exposes `StateFlow<RegistryDraft>` and `update { draft -> … }`.
- Create: `app/src/main/java/com/example/wave/data/admin/RegistryJsonWriter.kt`: `fun write(registry: Registry): String`, a hand-built JSON in CONTRACT order with 2-space indent and LF, so it's JVM-testable.
- Modify: `di/AppContainer.kt`: `val registryDraft: RegistryDraftStore` (seeded from `registryRepository.state` the first time), and a session `SignalTracker`.
- Test:
  - `app/src/test/java/com/example/wave/data/admin/RegistryJsonWriterTest.kt` (exact output for a small registry, LF only, escaping of quotes/backslashes in names);
  - `app/src/androidTest/java/com/example/wave/data/admin/RegistryDraftStoreTest.kt` (save → load round trip; written JSON parses with `RegistryJson.parse`).

- [ ] **Step 1: Write the failing tests.**
- [ ] **Step 2: Run them and check they fail.**
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run unit + instrumented tests (phone) and build.**
- [ ] **Step 5: Commit.** `git commit -m "feat(admin): save the draft on the phone and write contract JSON"`

---

### Task 5: Admin screens (Review sheet + Manage registry)

**Files:**
- Modify: `ui/screens/tools/SurveyScreen.kt`.
  - Tapping a network opens a **Review** bottom sheet instead of the "not available yet" dialog. The sheet shows:
    - name, security and band/channel;
    - **each access point**: BSSID, current signal, observed range, made-up-address flag, checkbox;
    - **Building** text field and **Reason** text field (for Block).
  - Buttons: **Approve selected** and **Block selected**; disabled with an explanation when nothing is ticked, Approve hidden for hidden networks.
  - Each row shows a chip: Approved / Blocked / In registry v1 / New.
  - Scans feed the `SignalTracker`.
- Create: `ui/screens/tools/ManageRegistryScreen.kt`, with:
  - tabs **Approved (n)** | **Blocked (n)**; rows show SSID, BSSID, building/reason and source chip, with a remove action;
  - a **Campus provider** card showing the ASN, with a **Detect** button that uses `container.exitPathFetcher` and must be on campus Wi-Fi;
  - **Scan to add** (opens the survey) and **Export draft** (`CreateDocument("application/json")`, suggested name `registry.json`, writes `RegistryJsonWriter.write(draft.toRegistry(now))`);
  - an info line: "Sign it on the laptop: `wave_sign.py sign-registry registry.json --key keys/wave-2026-a.private.pem`".
- Modify: `ui/navigation/Routes.kt`, `WaveNavHost.kt`: the admin gate → `MANAGE_REGISTRY`, and "Scan to add" → `SURVEY`.
- Previews for each state: empty, with items, blocked tab, Review sheet (normal, flagged, hidden network).

- [ ] **Step 1: Write the failing test** `ManageRegistryStateTest` for the pure UI-state mapping: counts, chips and an empty state.
- [ ] **Step 2: Run it and check it fails.**
- [ ] **Step 3: Implement** the screens and navigation.
- [ ] **Step 4: Run tests and build, then do a real end-to-end check on the phone.**
  1. **At home:** approve `GFiber_8C809` (if visible); block the PLDT network, reason "test"; set the campus provider with Detect; Export draft.
  2. **Copy the file to the laptop** (`adb pull` from where it was saved, or via Drive).
  3. **Sign the copy with the dev key, in the scratchpad only:** run `wave_sign.py sign-registry <copy> --key keys/wave-dev.private.pem`. Expected: "Signed." with no errors. Then run `verify-registry`; expected: VALID.
  4. **Serve it from a local server + tunnel** (`WAVE_REGISTRY_FILE=<copy>`, `WAVE_TRUSTED_PROXY=cloudflare`), point the phone at the tunnel, and tap **Update registry**. Expected: "Registry v2 downloaded".
  5. **Check Networks:** the PLDT network shows **Warning: "Blocked by campus admin"**.
  6. **Clean up:** put the phone back to the Render address, delete the saved test registry on the phone, and stop the local server and tunnel. **Never upload this test registry to Render** (it contains home addresses).
- [ ] **Step 5: Commit.** `git commit -m "feat(admin): review, approve/block and manage registry screens"`
- [ ] **Step 6: Ask the user before pushing** and opening the PR. The PR lists the shared files.
