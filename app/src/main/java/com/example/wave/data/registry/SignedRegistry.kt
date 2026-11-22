package com.example.wave.data.registry

import com.example.wave.data.RegistryState
import com.example.wave.model.Registry
import com.example.wave.verify.RegistrySignature
import com.example.wave.verify.WaveKeys
import java.time.Instant

/**
 * A registry exactly as it was signed: the raw bytes of registry.json, the signing key's id and the Ed25519
 * signature (base64url). Comes from the app's assets, the copy saved on the phone, or the server.
 */
class SignedRegistry(val bytes: ByteArray, val keyId: String, val signatureB64Url: String) {
    override fun equals(other: Any?): Boolean =
        other is SignedRegistry && bytes.contentEquals(other.bytes) && keyId == other.keyId && signatureB64Url == other.signatureB64Url

    override fun hashCode(): Int = (bytes.contentHashCode() * 31 + keyId.hashCode()) * 31 + signatureB64Url.hashCode()

    override fun toString(): String = "SignedRegistry(${bytes.size} bytes, key=$keyId)"
}

/** Turns signed material into a trusted registry, or says why it can't be trusted. */
fun interface RegistryVerifier {
    /** Ready / Stale when genuine; Missing(problem) otherwise. */
    fun verify(file: SignedRegistry): RegistryState
}

/**
 * CONTRACT Rule #1: check the signature over the raw bytes first, only then parse. Also requires the key_id
 * inside the file to match the signing key, and treats a registry past expires_at as Stale (still usable).
 */
class SignatureRegistryVerifier(
    private val now: () -> Instant = Instant::now,
    private val trustedKeys: Map<String, String> = WaveKeys.trusted,
    private val parse: (ByteArray) -> Registry = RegistryJson::parse,
) : RegistryVerifier {

    override fun verify(file: SignedRegistry): RegistryState {
        when (RegistrySignature.check(file.bytes, file.keyId, file.signatureB64Url, trustedKeys)) {
            RegistrySignature.Problem.UNKNOWN_KEY -> return RegistryState.Missing("signed by unknown key '${file.keyId}'")
            RegistrySignature.Problem.BAD_SIGNATURE -> return RegistryState.Missing("signature doesn't match the file")
            null -> Unit
        }
        val registry = try {
            parse(file.bytes)
        } catch (e: Exception) {
            return RegistryState.Missing("content breaks the contract: ${e.message}")
        }
        if (registry.keyId != file.keyId) return RegistryState.Missing("key_id inside the file doesn't match its signature")
        return if (registry.expiresAt.isBefore(now())) RegistryState.Stale(registry) else RegistryState.Ready(registry)
    }
}
