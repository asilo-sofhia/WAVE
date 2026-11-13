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
    /** What's needed to join the network. Null when the code must not be used (changed, expired, unreadable). */
    val credentials: WifiCredentials? = null,
    /** Is the code's network (name + security) on the signed registry? Null = no registry loaded. */
    val inRegistry: Boolean? = null,
)

/** How a Wi-Fi QR code says to join (its T field). */
enum class WifiAuth { OPEN, WPA, SAE, OTHER }

/** The network a QR code describes, for joining it. [toString] never includes the password. */
data class WifiCredentials(
    val ssid: String,
    val password: String?,
    val auth: WifiAuth,
    val hidden: Boolean,
) {
    override fun toString() = "WifiCredentials(ssid=$ssid, auth=$auth, hidden=$hidden, password=${if (password == null) "none" else "••••"})"
}

/** How a QR result is shown: VALID = verified, changed/expired = warning, the rest = unknown. */
val QrResult.verdict: Verdict
    get() = when (this) {
        QrResult.VALID -> Verdict.VERIFIED
        QrResult.BAD_SIGNATURE, QrResult.EXPIRED -> Verdict.WARNING
        QrResult.UNSIGNED, QrResult.UNKNOWN_KEY, QrResult.MALFORMED -> Verdict.UNKNOWN
    }
