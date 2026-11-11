# WAVE – Android app (frontend phase)

WAVE detects evil twin Wi-Fi networks and fake Wi-Fi QR codes. BatStateU CS 312 Mobile Computing, Group 9.

## Current phase: FRONTEND ONLY
- Build all screens with FAKE data. No real Wi-Fi scanning, no camera/ML Kit, no crypto, no network calls yet.
- All data comes through interfaces (e.g. `ScanRepository`, `QrVerifier`, `ExitPathChecker`) with `Fake*` implementations, so the real backend plugs in later without UI changes.
- Keep the state names in docs/FRONTEND_PLAN.md stable; the backend will plug into them later.
- Mascot source images are in assets/mascot/. Android only bundles them after they're imported into app/src/main/res/.

## Stack
Kotlin, Jetpack Compose, Material 3, Navigation Compose, ViewModel + StateFlow. minSdk 29. No other libraries without asking first.

## Design
- Mockups in docs/mockups/ are the visual inspiration, not pixel-exact specs.
- Mascot: Wavey the dolphin. Moods: NORMAL, HAPPY, UNCOMFORTABLE, CURIOUS, BRUISED, QR_PEEK. Until the animated WebPs exist, use placeholder images through one `Wavey(mood)` composable.
- HAPPY only when Verified AND exit-path check passed.
- Verdict colors (as in the mockups): Verified = green + check icon, Warning = red + triangle, Unknown/Pending = yellow (amber) + "?" / clock. Neutral states (not checked, skipped, not connected) = gray. Never color alone.
- Out of scope: WPA2/3-Enterprise, iOS, accounts/login, background monitoring.

## Rules
- Every screen has a @Preview for each state (verified, warning, unknown, loading).
- Accessibility: touch targets ≥ 48dp, contentDescription on icons and Wavey, text contrast ≥ 4.5:1.
- Small commits. Run `./gradlew assembleDebug` after each change and fix errors before moving on.
- Ask before adding dependencies or changing the architecture.