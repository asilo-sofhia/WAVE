package com.example.wave.model

/** Outcome of verifying a Wi-Fi QR code. Names are a contract with the backend: keep stable. */
enum class QrResult { VALID, UNSIGNED, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, EXPIRED }

data class QrVerification(
    val result: QrResult,
    /** Network the QR encodes; null when the QR couldn't be read. */
    val network: NetworkUi?,
    val signer: String?,
    val expiresAt: String?,
    val rawPayload: String,
)

/** How a QR result is shown: VALID = verified, changed/expired = warning, the rest = unknown. */
val QrResult.verdict: Verdict
    get() = when (this) {
        QrResult.VALID -> Verdict.VERIFIED
        QrResult.BAD_SIGNATURE, QrResult.EXPIRED -> Verdict.WARNING
        QrResult.UNSIGNED, QrResult.UNKNOWN_KEY, QrResult.MALFORMED -> Verdict.UNKNOWN
    }
