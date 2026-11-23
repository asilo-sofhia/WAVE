package com.example.wave.model

/**
 * The three verdict moods, the same on every screen: VERIFIED -> HAPPY, UNKNOWN -> CONFUSED,
 * WARNING (possible evil twin) -> SCARED.
 * CURIOUS is for "still checking" (not a verdict). WAVING is a greeting (splash screen); it is never a verdict.
 * SAD is only for QR codes that can't be used (not Wi-Fi, altered, expired); networks never use it.
 * UNCOMFORTABLE and QR_PEEK are kept for art that isn't a verdict.
 */
enum class WaveyMood { NORMAL, HAPPY, UNCOMFORTABLE, CURIOUS, CONFUSED, SCARED, QR_PEEK, WAVING, SAD }

/**
 * The single rule for Wavey's mood about a network, on every screen.
 *
 * - WARNING (a possible evil twin), or connected and the exit-path check FAILED -> SCARED.
 * - UNKNOWN -> CONFUSED.
 * - VERIFIED -> HAPPY.
 * - Nothing checked yet (null) -> NORMAL.
 */
fun waveyMoodFor(verdict: Verdict?, exitPath: ExitPathStatus?, connected: Boolean): WaveyMood = when {
    verdict == null -> WaveyMood.NORMAL
    verdict == Verdict.WARNING -> WaveyMood.SCARED
    connected && exitPath == ExitPathStatus.FAILED -> WaveyMood.SCARED
    verdict == Verdict.UNKNOWN -> WaveyMood.CONFUSED
    else -> WaveyMood.HAPPY // VERIFIED
}

/** The verdict moods for a QR code's network (QR result screen), by verdict alone. */
fun waveyMoodFor(verdict: Verdict): WaveyMood = waveyMoodFor(verdict, null, connected = false)

/**
 * Wavey's mood for a QR code on its own, before its network is checked.
 * - A readable Wi-Fi code (signed or not) -> NORMAL: the network nearby decides the verdict mood.
 * - A code that can't be used (altered, expired, not a Wi-Fi code) -> SAD.
 */
fun waveyMoodFor(qr: QrResult): WaveyMood = when (qr) {
    QrResult.VALID, QrResult.UNSIGNED, QrResult.UNKNOWN_KEY -> WaveyMood.NORMAL
    QrResult.BAD_SIGNATURE, QrResult.EXPIRED, QrResult.MALFORMED -> WaveyMood.SAD
}
