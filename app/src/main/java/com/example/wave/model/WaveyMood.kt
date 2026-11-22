package com.example.wave.model

enum class WaveyMood { NORMAL, HAPPY, UNCOMFORTABLE, CURIOUS, BRUISED, QR_PEEK }

/**
 * The single rule for Wavey's mood about a network.
 *
 * - HAPPY only when connected, VERIFIED and the exit-path check PASSED.
 * - Connected to a WARNING network (or exit path FAILED) -> BRUISED.
 * - A WARNING network nearby but not connected -> UNCOMFORTABLE.
 * - UNKNOWN -> CURIOUS. Everything else -> NORMAL.
 */
fun waveyMoodFor(verdict: Verdict?, exitPath: ExitPathStatus?, connected: Boolean): WaveyMood = when {
    verdict == null -> WaveyMood.NORMAL
    connected && (verdict == Verdict.WARNING || exitPath == ExitPathStatus.FAILED) -> WaveyMood.BRUISED
    verdict == Verdict.WARNING -> WaveyMood.UNCOMFORTABLE
    verdict == Verdict.UNKNOWN -> WaveyMood.CURIOUS
    connected && verdict == Verdict.VERIFIED && exitPath == ExitPathStatus.PASSED -> WaveyMood.HAPPY
    else -> WaveyMood.NORMAL
}

/** Wavey's mood for a QR verification. VALID is NORMAL: no exit-path check has run yet. */
fun waveyMoodFor(qr: QrResult): WaveyMood = when (qr.verdict) {
    Verdict.VERIFIED -> WaveyMood.NORMAL
    Verdict.WARNING -> WaveyMood.UNCOMFORTABLE
    Verdict.UNKNOWN -> WaveyMood.CURIOUS
}
