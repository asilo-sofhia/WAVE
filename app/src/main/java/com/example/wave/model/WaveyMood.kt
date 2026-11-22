package com.example.wave.model

/**
 * WAVING is a greeting (splash screen); it is never a verdict about a network.
 * SAD is only for QR codes that could not be verified; networks never use it.
 */
enum class WaveyMood { NORMAL, HAPPY, UNCOMFORTABLE, CURIOUS, SCARED, QR_PEEK, WAVING, SAD }

/**
 * The single rule for Wavey's mood about a network.
 *
 * - HAPPY only when connected, VERIFIED and the exit-path check PASSED.
 * - Connected to a WARNING network (or exit path FAILED) -> SCARED.
 * - A WARNING network nearby but not connected -> UNCOMFORTABLE.
 * - UNKNOWN -> CURIOUS. Everything else -> NORMAL.
 */
fun waveyMoodFor(verdict: Verdict?, exitPath: ExitPathStatus?, connected: Boolean): WaveyMood = when {
    verdict == null -> WaveyMood.NORMAL
    connected && (verdict == Verdict.WARNING || exitPath == ExitPathStatus.FAILED) -> WaveyMood.SCARED
    verdict == Verdict.WARNING -> WaveyMood.UNCOMFORTABLE
    verdict == Verdict.UNKNOWN -> WaveyMood.CURIOUS
    connected && verdict == Verdict.VERIFIED && exitPath == ExitPathStatus.PASSED -> WaveyMood.HAPPY
    else -> WaveyMood.NORMAL
}

/**
 * Wavey's mood for a QR code on its own.
 * - VALID -> NORMAL: no exit-path check has run yet.
 * - Every invalid or unverified result (changed, expired, unsigned, unknown signer, unreadable) -> SAD.
 * The QR result screen adds two cases on top: not in the registry -> SAD, fake network nearby -> UNCOMFORTABLE.
 */
fun waveyMoodFor(qr: QrResult): WaveyMood = when (qr) {
    QrResult.VALID -> WaveyMood.NORMAL
    QrResult.BAD_SIGNATURE, QrResult.EXPIRED, QrResult.UNSIGNED, QrResult.UNKNOWN_KEY, QrResult.MALFORMED -> WaveyMood.SAD
}
