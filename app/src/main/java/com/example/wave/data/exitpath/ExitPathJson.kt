package com.example.wave.data.exitpath

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Reads the `GET /v1/exit-path` body (CONTRACT §4) with Android's built-in org.json.
 * A missing or null field becomes null.
 *
 * @throws IOException when the body isn't a JSON object.
 */
fun parseExitPathAnswer(json: String): ExitPathAnswer {
    val o = try {
        JSONObject(json)
    } catch (e: JSONException) {
        throw IOException("The server's answer isn't valid JSON", e)
    }
    return ExitPathAnswer(
        publicIp = o.stringOrNull("public_ip"),
        asn = (o.opt("asn") as? Number)?.toInt(),
        asName = o.stringOrNull("as_name"),
        note = o.stringOrNull("note"),
    )
}

private fun JSONObject.stringOrNull(key: String): String? = (opt(key) as? String)
