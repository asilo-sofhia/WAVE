package com.example.wave.verify

import com.google.crypto.tink.subtle.Ed25519Verify
import java.util.Base64

/**
 * Checks a registry signature exactly as docs/CONTRACT.md §1–2 and backend wave_sign.py verify-registry do:
 * Ed25519 over "WAVE-REGISTRY-v1\n" + the RAW file bytes (never re-serialized JSON), with a key built into the app.
 * Pure (no Android), so it's tested against backend/test-vectors/test_vectors.json.
 */
object RegistrySignature {
    val DOMAIN: ByteArray = "WAVE-REGISTRY-v1\n".toByteArray(Charsets.UTF_8)

    enum class Problem { UNKNOWN_KEY, BAD_SIGNATURE }

    /** Null = the signature is genuine. [trustedKeys] maps key id -> base64url public key (32 bytes). */
    fun check(
        registryBytes: ByteArray,
        keyId: String,
        signatureB64Url: String,
        trustedKeys: Map<String, String> = WaveKeys.trusted,
    ): Problem? {
        val publicKey = trustedKeys[keyId]?.let(::decodeOrNull) ?: return Problem.UNKNOWN_KEY
        val signature = decodeOrNull(signatureB64Url) ?: return Problem.BAD_SIGNATURE
        return try {
            Ed25519Verify(publicKey).verify(signature, DOMAIN + registryBytes)
            null
        } catch (_: Exception) { // GeneralSecurityException (bad signature) or IllegalArgumentException (wrong length)
            Problem.BAD_SIGNATURE
        }
    }

    /** base64url without padding (the contract's encoding); Java's URL decoder accepts missing padding. */
    private fun decodeOrNull(text: String): ByteArray? =
        try { Base64.getUrlDecoder().decode(text.trim()) } catch (_: IllegalArgumentException) { null }
}
