package com.example.wave.data.registry

import com.example.wave.data.net.httpsGet
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.Base64

/**
 * `GET <server>/v1/registry` (CONTRACT §4) over the phone's default network. Wi-Fi or mobile data are both fine:
 * the answer is only trusted after its signature checks out, so a network can block it but not fake it.
 */
class HttpRegistryDownloader(private val serverUrl: () -> String?) : RegistryDownloader {

    override suspend fun download(): SignedRegistry {
        val base = serverUrl()?.takeIf { it.isNotBlank() } ?: throw IOException("No server address set")
        val url = URL("$base/v1/registry")
        return parseRegistryResponse(httpsGet(open = { url.openConnection() }, maxBytes = MAX_BODY_BYTES))
    }

    private companion object {
        const val MAX_BODY_BYTES = 1024 * 1024
    }
}

/**
 * `{"key_id", "registry_b64url", "signature_b64url"}`. The outer key_id is only a hint for the verifier,
 * which also requires the key_id inside the signed bytes to match.
 *
 * @throws IOException on bad JSON, a missing field or bad base64.
 */
fun parseRegistryResponse(json: String): SignedRegistry = try {
    val o = JSONObject(json)
    SignedRegistry(
        bytes = Base64.getUrlDecoder().decode(o.getString("registry_b64url")),
        keyId = o.getString("key_id"),
        signatureB64Url = o.getString("signature_b64url"),
    )
} catch (e: JSONException) {
    throw IOException("The server's registry answer isn't valid: ${e.message}", e)
} catch (e: IllegalArgumentException) {
    throw IOException("The server's registry isn't valid base64url", e)
}
