package com.example.wave.verify

import com.example.wave.data.registry.RegistryJson
import com.example.wave.model.Registry
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Result of checking one place's package, in the order the checks run (CONTRACT §7). */
enum class PackageResult { VALID, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, NOT_YET_VALID, EXPIRED, WRONG_PLACE }

/** One place's verified Wi-Fi list, with the exact package it came from (saved on the phone as is). */
data class PlaceList(val placeId: String, val placeName: String, val registry: Registry, val packageJson: String)

/**
 * Checks a place package from the root down, exactly as backend wave_sign.py verify_package does
 * (without the server's upload-time checks: phones have no trusted clock):
 * format → admin ID card valid when the list was issued, and the list doesn't outlive it → places agree →
 * key_id names the card → the admin's ECDSA P-256 signature over "WAVE-REGISTRY-v1\n" + the exact bytes.
 * Pure (no Android), so it runs against the shared test vectors.
 */
object PlacePackage {
    private val FIELDS = listOf("place_id", "registry_b64url", "signature_b64url", "cert")

    /** Never throws. The list comes back only when VALID. */
    fun verify(packageJson: String, rootKeys: Map<String, String> = WaveKeys.roots): Pair<PackageResult, PlaceList?> = try {
        check(packageJson, rootKeys)
    } catch (_: Exception) {
        PackageResult.MALFORMED to null
    }

    private fun check(packageJson: String, rootKeys: Map<String, String>): Pair<PackageResult, PlaceList?> {
        // 1. Shape and registry format.
        val pkg = JSONObject(packageJson)
        val fields = FIELDS.associateWith { requireNotNull(pkg.opt(it) as? String) { "$it must be text" } }
        val bytes = Base64.getUrlDecoder().decode(fields.getValue("registry_b64url"))
        val signature = Base64.getUrlDecoder().decode(fields.getValue("signature_b64url"))
        val registry = RegistryJson.parse(bytes)
        val regPlace = JSONObject(String(bytes, Charsets.UTF_8)).opt("place_id") as? String
        require(regPlace != null && AdminCert.PLACE_ID.matches(regPlace)) { "a place registry needs place_id" }
        require(registry.networks.isNotEmpty()) { "networks must not be empty" }

        // 2. The ID card, at the time the list was issued; the list can't outlive the card.
        val (certResult, cert) = AdminCert.verify(fields.getValue("cert"), rootKeys, at = registry.issuedAt)
        if (certResult != CertResult.VALID || cert == null) return PackageResult.valueOf(certResult.name) to null
        if (registry.expiresAt > cert.expiresAt) return PackageResult.EXPIRED to null

        // 3. Card, package and list name the same place.
        if (!(cert.placeId == fields.getValue("place_id") && cert.placeId == regPlace)) return PackageResult.WRONG_PLACE to null

        // 4. The list names its card.
        if (registry.keyId != cert.certId) return PackageResult.MALFORMED to null

        // 5. The admin's signature.
        val adminKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(cert.adminPublicKey)))
        val genuine = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(adminKey)
                update(RegistrySignature.DOMAIN + bytes)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
        if (!genuine) return PackageResult.BAD_SIGNATURE to null
        return PackageResult.VALID to PlaceList(cert.placeId, cert.placeName, registry, packageJson)
    }
}
