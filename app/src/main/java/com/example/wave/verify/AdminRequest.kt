package com.example.wave.verify

import org.json.JSONObject
import java.util.Base64

/**
 * The text an admin phone shares to ask the WAVE owner for an ID card (CONTRACT §6):
 * `WAVEREQ:1:` + base64url of {"admin_name","place_id","admin_key_alg":"ES256","admin_public_key"}.
 * Read on the laptop by `wave_sign.py certify-admin`. Holds only the PUBLIC key.
 */
object AdminRequest {
    const val PREFIX = "WAVEREQ:1:"

    /** Null when fine; otherwise a short message for the screen. The name is trimmed first. */
    fun problem(adminName: String, placeId: String): String? = when {
        !AdminCert.nameOk(adminName.trim()) -> "Name: 1–64 characters"
        !AdminCert.PLACE_ID.matches(placeId) -> "Place ID: lowercase letters, digits and -, up to 32"
        else -> null
    }

    /** Throws IllegalArgumentException when [problem] is not null. */
    fun make(adminName: String, placeId: String, publicKeySpki: ByteArray): String {
        problem(adminName, placeId)?.let { throw IllegalArgumentException(it) }
        val body = JSONObject()
            .put("admin_name", adminName.trim())
            .put("place_id", placeId)
            .put("admin_key_alg", AdminCert.KEY_ALG)
            .put("admin_public_key", b64url(publicKeySpki))
        return PREFIX + b64url(body.toString().toByteArray(Charsets.UTF_8))
    }

    private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
