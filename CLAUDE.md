# WAVE – Android app (frontend phase)

WAVE detects evil twin Wi-Fi networks and fake Wi-Fi QR codes. BatStateU CS 312 Mobile Computing, Group 9.

## Current phase: INTEGRATION (shared foundation first)
- Screens keep using the interfaces in data/ and never call Android Wi-Fi, Room, crypto or network code directly.
- Real implementations replace Fake* only in di/AppContainer.kt. Fakes stay available for the debug menu and demos.
- docs/CONTRACT.md is the source of truth for the registry, QR and exit-path formats.
- Lanes: (1) UI polish in ui/, (2) Android Wi-Fi API + classifier in data/wifi/ and domain/, (3) registry verification, Room cache, sync and server in data/registry/, data/local/, verify/ and backend/.
- Shared files (model/, data/Repositories.kt, libs.versions.toml, AndroidManifest.xml) change only with team agreement.

## Stack
Kotlin, Jetpack Compose, Material 3, Navigation Compose, ViewModel + StateFlow. minSdk 29. No other libraries without asking first.

## Design
- Mockups in docs/mockups/ are the visual inspiration, not pixel-exact specs.
- Mascot: Wavey the dolphin. Moods: NORMAL, HAPPY, UNCOMFORTABLE, CURIOUS, SCARED, QR_PEEK, WAVING (greeting only, never a verdict), SAD (invalid or unverified QR codes only). All art goes through one `Wavey(mood)` composable; moods without real art yet use placeholders there.
- HAPPY only when Verified AND exit-path check passed. One exception: the QR result screen shows HAPPY for a valid signed code that matches the registry (the access point is still checked before Connect).
- Verdict colors (as in the mockups): Verified = green + check icon, Warning = red + triangle, Unknown/Pending = yellow (amber) + "?" / clock. Neutral states (not checked, skipped, not connected) = gray. Never color alone.
- Out of scope: WPA2/3-Enterprise, iOS, accounts/login, background monitoring.

## Rules
- Every screen has a @Preview for each state (verified, warning, unknown, loading).
- Accessibility: touch targets ≥ 48dp, contentDescription on icons and Wavey, text contrast ≥ 4.5:1.
- Small commits. Run `./gradlew assembleDebug` after each change and fix errors before moving on.
- Ask before adding dependencies or changing the architecture.