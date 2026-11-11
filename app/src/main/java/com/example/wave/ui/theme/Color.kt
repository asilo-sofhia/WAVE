package com.example.wave.ui.theme

import androidx.compose.ui.graphics.Color

// Brand / surfaces (see docs/FRONTEND_PLAN.md §5). Every text pair is >= 4.5:1.
val SkyTop = Color(0xFFE8F5FF)
val SkyBottom = Color(0xFFC4E5FF)
// Screen background from mockups 2-5: deeper sky at the top, fading lighter downwards.
val SkyHigh = Color(0xFFA9DEFC)
val SkyMidBg = Color(0xFFC9EBFD)
val SkyLow = Color(0xFFE3F4FD)
// Brand icon blue on light tiles (>= 3:1 for graphics).
val BrandIcon = Color(0xFF1683F5)
val CardSurface = Color(0xFFFBFDFF) // opaque: translucent cards show their own shadow through
val CardBorder = Color(0x99FFFFFF)
val Navy900 = Color(0xFF0E1E3F)
val Slate600 = Color(0xFF4A5873)
val Blue700 = Color(0xFF0A4FB0)
val Blue400 = Color(0xFF3BA3F5) // decorative only, never text
val BlueTint = Color(0xFFD3E8FF)
val Divider = Color(0xFFE2EAF4)
val ScannerNavy = Color(0xFF0B3A6E)

// Verdicts: always shown with an icon + a word, never color alone.
val VerifiedFg = Color(0xFF087050)
val VerifiedBg = Color(0xFFDDF5EA)
// Warning = red (mockups 3 and 5). 4.8:1 on its tint.
val WarningFg = Color(0xFFC42330)
val WarningBg = Color(0xFFFEE4E8)
// Unknown / Pending = yellow (amber). 4.9:1 on its tint.
val UnknownFg = Color(0xFF8F5F00)
val UnknownBg = Color(0xFFFFF0CF)
// Neutral gray for non-verdict states: "Not checked", "Skipped", "Not connected", disabled controls.
val NeutralFg = Color(0xFF556070)
val NeutralBg = Color(0xFFE9EDF2)
val Danger = Color(0xFFB3261E)
val DangerBg = Color(0xFFFCE4E2)
