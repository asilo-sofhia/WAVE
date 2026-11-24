package com.example.wave.verify

import com.google.crypto.tink.subtle.Ed25519Verify
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/** Result of checking an admin ID card, in the order they are checked (CONTRACT §6). */
enum class CertResult { VALID, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, NOT_YET_VALID, EXPIRED }

/** A place admin's certificate: "this phone key may sign for [placeId] until [expiresAt]". */
data class AdminCertificate(
    val certId: String,
    val placeId: String,
    val placeName: String,
    val adminName: String,
    /** base64url of the admin phone key's SubjectPublicKeyInfo DER (P-256), exactly as in the certificate. */
    val adminPublicKey: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val rootKeyId: String,
)

/**
 * Reads admin ID cards (`WAVECERT:1:<cert>.<signature>`) exactly as backend wave_sign.py verify_cert does:
 * format → root key known → root signature (Ed25519 over "WAVE-CERT-v1\n" + the exact certificate bytes) → dates.
 * Only root keys ([WaveKeys.roots]) certify admins, never registry or QR keys. Pure (no Android).
 */
object AdminCert {
    const val PREFIX = "WAVECERT:1:"
    val DOMAIN: ByteArray = "WAVE-CERT-v1\n".toByteArray(Charsets.UTF_8)

    private const val SCHEMA = "wave-admin-cert/1"
    const val KEY_ALG = "ES256"
    private val CERT_ID = Regex("^[0-9a-f]{16}$")
    val PLACE_ID = Regex("^[a-z0-9][a-z0-9-]{0,31}$")
    private val TIME = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")
    private const val MAX_TEXT = 8_000

    /** P-256 (secp256r1) group order: identifies the curve together with the 256-bit field. */
    private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)

    /**
     * Never throws. The certificate comes back only when the root signature checked out
     * (VALID, NOT_YET_VALID or EXPIRED), so unverified names are never shown.
     */
    fun verify(
        text: String,
        rootKeys: Map<String, String> = WaveKeys.roots,
        at: Instant,
    ): Pair<CertResult, AdminCertificate?> {
        val parsed = try {
            parse(text.trim())
        } catch (_: Exception) {
            return CertResult.MALFORMED to null
        }
        val (cert, certBytes, signature) = parsed
        val root = rootKeys[cert.rootKeyId]?.let(::decodeOrNull) ?: return CertResult.UNKNOWN_KEY to null
        val genuine = try {
            Ed25519Verify(root).verify(signature, DOMAIN + certBytes)
            true
        } catch (_: Exception) {
            false
        }
        if (!genuine) return CertResult.BAD_SIGNATURE to null
        return when {
            at < cert.issuedAt -> CertResult.NOT_YET_VALID to cert
            at > cert.expiresAt -> CertResult.EXPIRED to cert
            else -> CertResult.VALID to cert
        }
    }

    private data class Parsed(val cert: AdminCertificate, val certBytes: ByteArray, val signature: ByteArray)

    private fun parse(text: String): Parsed {
        require(text.length <= MAX_TEXT && text.startsWith(PREFIX)) { "not a WAVE ID card" }
        val parts = text.removePrefix(PREFIX).split('.')
        require(parts.size == 2) { "needs <cert>.<signature>" }
        val certBytes = Base64.getUrlDecoder().decode(parts[0])
        val signature = Base64.getUrlDecoder().decode(parts[1])
        val o = JSONObject(String(certBytes, Charsets.UTF_8))
        require(str(o, "schema") == SCHEMA && str(o, "admin_key_alg") == KEY_ALG) { "wrong schema or algorithm" }
        val certId = str(o, "cert_id").also { require(CERT_ID.matches(it)) { "bad cert_id" } }
        val placeId = str(o, "place_id").also { require(PLACE_ID.matches(it)) { "bad place_id" } }
        val placeName = str(o, "place_name").also { require(nameOk(it)) { "bad place name" } }
        val adminName = str(o, "admin_name").also { require(nameOk(it)) { "bad admin name" } }
        val adminKey = str(o, "admin_public_key").also { requireP256(it) }
        val issued = time(str(o, "issued_at"))
        val expires = time(str(o, "expires_at"))
        require(expires > issued) { "expires_at must be after issued_at" }
        val rootKeyId = str(o, "root_key_id")
        return Parsed(
            AdminCertificate(certId, placeId, placeName, adminName, adminKey, issued, expires, rootKeyId),
            certBytes,
            signature,
        )
    }

    /** A JSON string field (numbers or other types are not quietly turned into text). */
    private fun str(o: JSONObject, key: String): String =
        requireNotNull(o.opt(key) as? String) { "$key must be a string" }

    /** 1–64 characters (code points, like Python's len) and not only spaces. Same rule as wave_sign.py. */
    fun nameOk(value: String): Boolean = value.isNotBlank() && value.codePointCount(0, value.length) <= 64

    private fun time(value: String): Instant {
        require(TIME.matches(value)) { "time must look like 2026-11-24T00:00:00Z" }
        return Instant.parse(value)
    }

    private fun requireP256(b64: String) {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(b64)))
        val params = (key as ECPublicKey).params
        require(params.curve.field.fieldSize == 256 && params.order == P256_ORDER) { "admin key must be P-256" }
    }

    private fun decodeOrNull(text: String): ByteArray? =
        try { Base64.getUrlDecoder().decode(text.trim()) } catch (_: IllegalArgumentException) { null }
}
