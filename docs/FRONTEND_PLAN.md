# WAVE: Frontend Build Plan

> Phase: **frontend only**. Fake data, no Wi-Fi scanning, no camera/ML Kit, no crypto, no network calls.
> Goal: a polished, clickable app on a real phone that shows every screen and every verdict state. All data goes through interfaces, so the backend can replace the fakes without UI changes.
> Today: Wed Nov 11, 2026. Screens due **Sat Nov 21**. Integration days **Fri Nov 13** and **Fri Nov 20**.

## 1. Screens and navigation

### Navigation map

```
Splash ──Get Started──► Location explainer ──Allow──► Home
   └── (permission already granted) ─────────────────► Home

Bottom nav (Home · Networks · Scan QR · Monitor)

Home ──Check Wi-Fi──► (inline result) ──tap Health card──► Monitor
  │                         └─ connected net is WARNING ──► Disconnect-now
  ├──Nearby Networks row──► Nearby
  └──Scan Wi-Fi QR row────► Scan QR

Nearby ──tap row──► Network detail / Warning ──back──► Nearby
  └── permission missing ──► Location explainer

Scan QR ──(fake scan)──► QR result ──VALID: Connect──► Monitor
                                    └─other: Scan again──► Scan QR

Monitor ──verdict WARNING or exit path FAILED──► Disconnect-now
Disconnect-now ──"I've disconnected"──► Home

Hidden: long-press the WAVE logo (debug builds only) ──► Debug menu sheet
```

Routes use plain string routes (`"detail/{bssid}"`) so we don't need the kotlinx-serialization plugin.

### Screens, states, Wavey mood

| Screen | Purpose | State → Wavey mood |
|---|---|---|
| **Splash** (mockup 1) | First-launch welcome, "Your Wi-Fi safety buddy", Get Started | Default → NORMAL |
| **Location explainer** (new) | Explains why Android needs location to list Wi-Fi, and that WAVE never stores or sends it. "Allow location" / "Not now" | First ask → CURIOUS · Denied ("Open settings") → CURIOUS |
| **Home** (mockup 2) | Wavey + speech bubble, SSID card, Health card, Check Wi-Fi, tool rows | Not checked → NORMAL · Checking (loading) → CURIOUS · Verified + exit PENDING → NORMAL · Verified + exit PASSED → **HAPPY** · Unknown → CURIOUS · Warning → BRUISED (goes to Disconnect-now) · Not on Wi-Fi → NORMAL |
| **Nearby Networks** (mockup 3) | Scan summary, registry info, list of NetworkRows, quick actions | Loading (skeleton rows) → CURIOUS · All verified → NORMAL · Twin nearby (banner) → UNCOMFORTABLE · Unknown only → CURIOUS · Empty → NORMAL · No permission → CURIOUS |
| **Network detail / Warning** (new) | One network's verdict. WARNING shows the title "Don't connect", plain-language reasons, one action ("Back to safe networks"), and a "Technical details" toggle (BSSID, security, band, dBm, reason codes) | VERIFIED → NORMAL · UNKNOWN ("Can't verify, avoid logins") → CURIOUS · WARNING → UNCOMFORTABLE · Loading → CURIOUS |
| **Scan QR** (mockup 4) | Fake viewfinder (static frame + scan-line animation). Tap frame / "Scan sample QR" runs FakeQrVerifier. Flashlight toggles its icon only; Gallery runs the same fake verify. "?" opens a help sheet | Idle → QR_PEEK · Verifying → CURIOUS |
| **QR result** (new) | Verdict for the scanned QR, the network it encodes, one action, and a tech-details toggle (signer, expiry, raw payload) | VALID → NORMAL, "Connect" · BAD_SIGNATURE ("This QR was changed") → UNCOMFORTABLE · EXPIRED → UNCOMFORTABLE · UNSIGNED / UNKNOWN_KEY → CURIOUS · MALFORMED ("Not a Wi-Fi QR") → CURIOUS |
| **Connection Monitor** (mockup 5) | Current connection: Registry tile + Exit-path tile, details list, "Live" pill | Not connected → NORMAL · Loading → CURIOUS · Verified + PENDING → NORMAL · Verified + PASSED → **HAPPY** · Unknown → CURIOUS · Warning / exit FAILED → goes to Disconnect-now |
| **Disconnect-now** (new) | Connected to a fake network. Big "Disconnect now" heading, reasons, one action "Open Wi-Fi settings" (`Settings.Panel.ACTION_WIFI`; Android 10+ apps can't disconnect for you), secondary "I've disconnected" | Always → BRUISED |
| **Debug menu** (hidden sheet) | Switch scenarios during demos | No Wavey |

**One rule in one place:** `waveyMoodFor(verdict, exitPath, connected)` in `model/WaveyRules.kt` decides the mood. HAPPY only when `verdict == VERIFIED && exitPath == PASSED`. Covered by a JUnit test.

## 2. UI state models

```kotlin
enum class Verdict { VERIFIED, UNKNOWN, WARNING }

enum class WarningReason {
    UNREGISTERED_BSSID,    // "This access point isn't on the official list."
    LOCALLY_ADMINISTERED,  // "Its hardware address looks made-up (often a phone hotspot)."
    SECURITY_MISMATCH,     // "Its security is different from the real network."
    RSSI_OUT_OF_RANGE,     // "Its signal strength is unusual for this place."
    EXIT_ASN_MISMATCH      // "Your traffic leaves through an unexpected provider."
}

enum class SecurityType { OPEN, WEP, WPA2_PERSONAL, WPA3_PERSONAL, WPA2_WPA3_PERSONAL } // no Enterprise
enum class WifiBand { GHZ_2_4, GHZ_5, GHZ_6 }

data class NetworkUi(
    val ssid: String,
    val bssid: String,
    val security: SecurityType,
    val band: WifiBand,
    val signal: Int,                       // dBm; label (Excellent/Good/Fair/Weak) derived in UI
    val verdict: Verdict,
    val reasons: List<WarningReason> = emptyList()  // non-empty only when WARNING
)

enum class QrResult { VALID, UNSIGNED, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, EXPIRED }
data class QrVerification(val result: QrResult, val network: NetworkUi?, val signer: String?, val expiresAt: String?, val rawPayload: String)

enum class ExitPathStatus { PENDING, PASSED, FAILED }

enum class WaveyMood { NORMAL, HAPPY, UNCOMFORTABLE, CURIOUS, BRUISED, QR_PEEK }

data class RegistryInfo(val source: String, val updatedAt: String)   // "Offline registry", "today"
data class ScanResult(val networks: List<NetworkUi>, val registry: RegistryInfo, val scannedAt: Long)
```

Each screen has its own `sealed interface XUiState` (for example `NearbyUiState.Loading / Loaded(networks, registry) / Empty / NeedsPermission`), exposed by its ViewModel as `StateFlow`. When the exit path comes back FAILED, the Monitor ViewModel adds `EXIT_ASN_MISMATCH` and switches the verdict to WARNING.

## 3. Fake data layer

As built (`app/src/main/java/com/example/wave/data/Repositories.kt`). Names to lock with the backend team on Nov 13:

```kotlin
interface ScanRepository {
    val connection: StateFlow<NetworkUi?>          // current Wi-Fi + verdict, null = not on Wi-Fi
    suspend fun scanNearby(): ScanResult
    suspend fun checkConnection(): NetworkUi?      // re-read + re-verify current connection (Home "Check Wi-Fi")
    fun findNetwork(bssid: String): NetworkUi?     // from latest scan or connection (Network detail)
}
interface QrVerifier { suspend fun verify(payload: String): QrVerification }
interface ExitPathChecker { fun check(network: NetworkUi): Flow<ExitPathStatus> } // PENDING, then PASSED/FAILED
interface LocationPermissionGate { val granted: StateFlow<Boolean>; suspend fun request(): Boolean }
```

- `Fake*` implementations read the active `Scenario` from `ScenarioStore` (a `MutableStateFlow`) and add a configurable delay (default 1.2 s) so the loading states show up.
- `AppContainer` (manual DI, held by `WaveApplication` so demo state survives rotation) holds the four interfaces. Swapping to the real backend = changing 4 lines there. No Hilt.
- `AppContainer.demo` exposes the `ScenarioStore` to the debug menu and to one demo hook (Disconnect-now's "I've disconnected"). It becomes `null` when real implementations are in.
- Location permission is faked through `FakeLocationPermissionGate` (Q9).

| Scenario | Nearby list | Current connection | Exit path | QR scan |
|---|---|---|---|---|
| `ALL_SAFE` | BATSTATEU-ICT ✓, CICS-LAB-5G ✓ | BATSTATEU-ICT ✓ | PENDING → PASSED | VALID |
| `TWIN_NEARBY` | real ✓ + twin ⚠ (`02:1A:7F:…`, OPEN, −38 dBm) + Library Guest ? | BATSTATEU-ICT ✓ | PASSED | VALID |
| `UNKNOWN_ONLY` | Library Guest ?, Cafe-Free ? | Library Guest ? | PASSED | UNSIGNED |
| `QR_VALID` | as ALL_SAFE | none | — | VALID |
| `QR_CHANGED` | as ALL_SAFE | none | — | BAD_SIGNATURE |
| `EXIT_PATH_FAILED` | as ALL_SAFE | BATSTATEU-ICT ✓ | PENDING → FAILED | VALID |
| `CONNECTED_TO_TWIN` *(added)* | as TWIN_NEARBY | twin ⚠ | — | VALID |

`CONNECTED_TO_TWIN` is one more than your list, so Disconnect-now can be shown directly.

**Debug menu** (ModalBottomSheet; long-press the logo; only shown when the app is debuggable via `ApplicationInfo.FLAG_DEBUGGABLE`, no build change): scenario radio list · QR result override (all 6 values) · fake delay 0 / 1.2 / 3 s · location permission granted/denied · "Reset onboarding".

## 4. Mascot assets

| File (in `assets/mascot/`) | Looks like | Maps to |
|---|---|---|
| `Base_Mascot.png` | Open eyes, wavy smile, standing | **NORMAL** |
| `Calm_Mascot.png` | Closed happy eyes, tongue out | **HAPPY** (decided, Q2) |

**Missing:** UNCOMFORTABLE, CURIOUS, BRUISED, QR_PEEK.

**Import plan:**
1. Clean the stray edge pixels, downscale to 512 px on the long side, and convert to lossless WebP with alpha (Android Studio → Convert to WebP). That cuts ~1 MB per image to ~100–200 KB.
2. Save to `app/src/main/res/drawable-nodpi/` as `wavey_normal.webp` and `wavey_happy.webp`. Missing moods get `wavey_<mood>` later with the same names.
3. `Wavey(mood, modifier, size)` is the **only** place that touches mascot images. It maps mood → drawable plus a contentDescription ("Wavey looks worried", …).
4. Placeholders for missing moods: the base image plus a small mood badge (CURIOUS "?", UNCOMFORTABLE "!", BRUISED bandage, tinted) so demos still show different moods. QR_PEEK = the base image clipped to its top half, peeking over the scan frame like mockup 4.
5. Later, animated WebP: change only the inside of `Wavey` to decode with `ImageDecoder` → `AnimatedImageDrawable` (API 28+, no new library). Show a still frame when system animations are turned off.

## 5. Design system

**Colors** (light theme only; `dynamicColor` turned off):

| Token | Hex | Use |
|---|---|---|
| SkyTop / SkyBottom | `#E8F5FF` / `#C4E5FF` | Screen background gradient |
| CardSurface | `#FFFFFF` @ 90% | Cards (translucent, no real blur: `blur()` is API 31+, minSdk is 29) |
| Navy900 | `#0E1E3F` | Primary text |
| Slate600 | `#4A5873` | Secondary text |
| Blue700 | `#0A4FB0` | Primary button, selected nav |
| Blue400 | `#3BA3F5` | Decorative accents only (not text) |
| Verified fg / bg | `#0A7A55` / `#DDF5EA` | ✓ check icon + "Verified" |
| Warning fg / bg | `#C42330` / `#FEE4E8` | ⚠ triangle + "Warning" (red, as in mockups 3 and 5) |
| Unknown/Pending fg / bg | `#8F5F00` / `#FFF0CF` | "?" or clock + "Unknown"/"Pending" (yellow/amber, as in the mockups) |
| Neutral fg / bg | `#556070` / `#E9EDF2` | "Not checked", "Skipped", "Not connected", disabled |
| Danger | `#B3261E` | Disconnect-now heading only, always with icon + text |

All text/background pairs must reach at least 4.5:1, re-checked in M6.

**Type** (system sans unless Q5 says otherwise): Display 32/38 Bold · Headline 24/30 Bold · Title 18/24 SemiBold · Body 16/22 · Label 14/20 Medium · Caption 12/16. Buttons 16 SemiBold.
**Spacing:** 4 · 8 · 12 · 16 · 24 · 32; screen gutter 20.
**Shapes:** hero cards 28 dp · cards/rows 20 dp · icon tiles 16 dp · chips and buttons pill. Buttons 56 dp tall.

**Components** (`ui/components/`):

| Component | Notes |
|---|---|
| `Wavey(mood)` | See §4 |
| `VerdictChip(verdict)` / `ExitPathChip(status)` | Icon + text always; never color alone |
| `NetworkRow(network, onClick)` | Verdict icon tile, SSID, chip, "security · band", first reason line, `SignalBars` + text label; one merged TalkBack label; ≥ 48 dp |
| `StatusCard(icon, label, value, status)` | Two-up tiles (SSID/Health, Registry/Exit path) |
| `PrimaryButton` / `SecondaryButton` | Pill, loading state |
| `SpeechBubble`, `ReasonList`, `TechDetailsToggle`, `SectionHeader`, `SignalBars` | Small helpers |
| `WaveScaffold` + `WaveBottomBar` | Gradient background, top bar with logo (debug long-press), 4-tab bottom nav |

**Mockup fixes:**

| Mockup | Fix |
|---|---|
| 1 Splash | "Your Smart Ai Asssistant Hare." → **"Your Wi-Fi safety buddy"** |
| 2 Home | "BAYSTADU-ICT" → **BATSTATEU-ICT**; remove "Good morning / Stay secure, Alex", the bell, and the gear |
| 3 Nearby, 5 Monitor | "WPA2 Enterprise" → **WPA2 Personal** (Enterprise is out of scope) |
| 3 Nearby | Remove "Run Full Security Check"; Warning stays red and Unknown yellow, as in the mockup (changed on Nov 11 at the team's request); every chip keeps its icon + word |
| 5 Monitor | Pending stays yellow + clock + word (Nov 11 decision); remove the gear |
| All | No red-vs-green-only signals: every status has an icon and a word |

## 6. Package structure

```
com.example.wave/
  MainActivity.kt                 // builds AppContainer, setContent { WaveApp() }
  WaveApp.kt                      // WaveScaffold + NavHost + bottom bar
  di/AppContainer.kt
  model/                          // Verdict, WarningReason, NetworkUi, QrResult, QrVerification,
                                  // ExitPathStatus, WaveyMood, WaveyRules.kt
  data/                           // ScanRepository, QrVerifier, ExitPathChecker
  data/fake/                      // Fake*, Scenario, ScenarioStore, FakeFixtures
  ui/theme/                       // Color, Type, Shape, Spacing, Theme
  ui/components/                  // Wavey, VerdictChip, NetworkRow, StatusCard, PrimaryButton, …
  ui/navigation/                  // Routes, WaveNavHost
  ui/screens/<name>/              // <Name>Screen.kt (stateless + previews), <Name>ViewModel.kt, <Name>UiState.kt
     splash, permission, home, nearby, detail, scanqr, qrresult, monitor, disconnect
  ui/debug/DebugMenuSheet.kt
res/drawable-nodpi/wavey_*.webp, res/drawable/ic_*.xml (vector icons)
```

Every screen is split into a stateful `XRoute(viewModel)` and a stateless `XScreen(state, onAction)`. Previews use the stateless one for each state (verified / warning / unknown / loading; for Splash and Permission, the states they actually have).

**Dependencies to add** (already in the CLAUDE.md stack, but I'll confirm before touching Gradle): `navigation-compose`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, and bump `lifecycle` 2.6.1 to the current stable version. Nothing else.

## 7. Milestones

| # | Dates | Deliverable | Done when |
|---|---|---|---|
| M1 | Wed Nov 11 – Thu Nov 12 | Theme (colors, type, shapes, dynamic color off), mascot import, all components with previews | `assembleDebug` passes; component previews look like the mockups |
| M2 | Thu Nov 12 – Fri Nov 13 | Models, interfaces, fakes + scenarios, AppContainer, NavHost + bottom bar with empty screens, debug menu | Can click through all tabs on a phone and switch scenarios |
| **Integration** | **Fri Nov 13** | Share `model/` + `data/` interfaces with the backend team and lock the names | Backend agrees on signatures |
| M3 | Sat Nov 14 – Sun Nov 15 | Splash, Location explainer, Home, Nearby | All their states reachable via the debug menu |
| M4 | Mon Nov 16 – Tue Nov 17 | Network detail/Warning, Monitor, Disconnect-now | Twin / exit-fail / connected-to-twin flows work end to end |
| M5 | Wed Nov 18 – Thu Nov 19 | Scan QR, QR result (all 6 results) | Every QR result reachable |
| **Integration** | **Fri Nov 20** | Full clickable build on a phone; backend tries swapping one Fake | Swapping doesn't break the UI |
| M6 | Fri Nov 20 – **Sat Nov 21 (due)** | Polish: TalkBack pass, 48 dp targets, contrast check, 200% font scale, reduced motion, all previews present, `waveyMoodFor` tests | Checklist below is all green |

**Verify after every change:** `./gradlew assembleDebug`. At each milestone also run `./gradlew installDebug` on the phone and walk through every scenario in the debug menu. M6 checklist: every screen × state preview renders · TalkBack reads each NetworkRow as one sentence · no clipped text at the largest font · HAPPY appears only in Verified + Passed · `./gradlew testDebugUnitTest` passes.

## 8. Risks and open questions

**Risks**
- **10-day window.** Mitigation: components first; each screen is mostly assembled from them.
- **4 of 6 moods missing.** Placeholders with badges keep the demo readable; real art can drop in at any time thanks to `Wavey(mood)`.
- **Mascot PNGs are ~1 MB with edge artifacts.** Clean, resize and convert before import.
- **Interface drift with the backend.** Lock names on Nov 13 and keep `docs/FRONTEND_PLAN.md` as the source of truth.
- **Glass look:** real blur isn't available below API 31, so we fake it with translucent cards and soft shadows.
- **Disconnect:** Android 10+ apps can't disconnect Wi-Fi themselves, so the button opens the system Wi-Fi panel.

**Decisions (Nov 11; recommended option taken for every question, change any of them by saying so)**

| # | Decision |
|---|---|
| 1 | Mockups and mascot moved to root `docs/mockups/` and `assets/mascot/` (matches CLAUDE.md) |
| 2 | `Calm_Mascot` = HAPPY. Idle Home uses NORMAL (`Base_Mascot`). QR_PEEK placeholder uses the calm face, as in mockup 4 |
| 3 | Still open: who draws the 4 missing moods + animated WebP, and when |
| 4 | Icons: 22 hand-copied Material vector XMLs in `res/drawable/ic_*.xml`, no icons dependency |
| 5 | Font: system sans |
| 6 | Gear and bell removed; the logo's long-press opens the debug menu |
| 7 | EXPIRED/BAD_SIGNATURE = Warning; UNSIGNED/UNKNOWN_KEY/MALFORMED = Unknown |
| 8 | Splash on first launch only (SharedPreferences), resettable from the debug menu |
| 9 | Location permission fully faked this phase |
| 10 | Verified detail has one action: "Connect in Wi-Fi settings" (opens the system Wi-Fi panel). QR VALID does the same |
| 11 | `git init` done, small commits on `master`, no remote |
| 12 | Light theme only |
| 13 | Package stays `com.example.wave` |

**Original open questions**
1. **Folder paths:** mockups/mascot live under `app/docs` and `app/assets`, but CLAUDE.md says root `docs/` and `assets/`. Move them to root (I'll save this plan at root `docs/`), or update CLAUDE.md?
2. **Calm_Mascot:** HAPPY, or NORMAL? Mockup 2 uses the calm face for the idle "ready to scan" state, which by our rule is NORMAL.
3. **Missing moods:** who draws UNCOMFORTABLE, CURIOUS, BRUISED, QR_PEEK (and the animated WebPs), and by when?
4. **Icons:** add `material-icons-extended` (one more dependency), or hand-copy ~15 Material Symbols as vector XML into `res/drawable` (no dependency, my recommendation)?
5. **Font:** system sans, or bundle a rounded font like Nunito (OFL) as TTF in `res/font` (no dependency)? The mockups look like a rounded geometric font.
6. **Gear icon** on Home/Monitor: no settings screen is defined. OK to remove it?
7. **QR mapping:** EXPIRED → Warning (orange), and MALFORMED/UNKNOWN_KEY → Unknown (gray)? Agree?
8. **Splash:** first launch only (remembered via SharedPreferences, resettable in debug menu), or every launch?
9. **Location permission:** keep it fully faked this phase, or request the real permission now (still no scanning)?
10. **Verified network detail:** info only, or also a "Connect" button that opens the Wi-Fi panel?
11. **Git:** the project isn't a git repo yet, but CLAUDE.md wants small commits. OK to `git init`?
12. **Dark mode:** light theme only for this phase? (Mockups are light only.)
13. **Package name:** keep `com.example.wave`, or rename (e.g. `ph.edu.batstateu.wave`) now, before code grows?
