package com.example.wave.data.admin

import com.example.wave.data.net.httpsGet
import com.example.wave.data.net.httpsPostJson
import com.example.wave.verify.AdminCert
import org.json.JSONObject
import java.io.IOException
import java.net.URL

/** The server's word on an upload (CONTRACT §4): STORED, STALE_VERSION, EXPIRED, ... and the stored version. */
data class UploadAnswer(val result: String, val version: Int?)

/** The WAVE server's place endpoints (CONTRACT §4). */
interface PlaceServer {
    /** The version the server holds for [placeId], or 0 when the place isn't listed. Throws IOException. */
    suspend fun latestVersion(placeId: String): Int

    /** Uploads one package. Any answer with a result comes back; IOException only when there is no usable answer. */
    suspend fun upload(placeId: String, packageJson: String): UploadAnswer
}

class HttpPlaceServer(private val baseUrl: () -> String?) : PlaceServer {

    private fun url(path: String): URL {
        val base = baseUrl()?.trimEnd('/') ?: throw IOException("No WAVE server address set")
        return URL(base + path)
    }

    override suspend fun latestVersion(placeId: String): Int {
        val body = httpsGet({ url("/v1/places").openConnection() }, maxBytes = MAX_LIST_BYTES, readTimeoutMs = 60_000)
        return parse { PlaceServerJson.latestVersion(body, placeId) }
    }

    override suspend fun upload(placeId: String, packageJson: String): UploadAnswer {
        val (_, body) = httpsPostJson({ url("/v1/places/$placeId/registry").openConnection() }, packageJson, maxBytes = 16_384)
        return parse { PlaceServerJson.answer(body) }
    }

    private inline fun <T> parse(block: () -> T): T =
        try { block() } catch (e: Exception) { throw IOException("The server's answer isn't readable", e) }

    private companion object {
        const val MAX_LIST_BYTES = 1_048_576
    }
}

/** One entry of `GET /v1/places` (CONTRACT §4). */
data class PlaceSummary(val placeId: String, val placeName: String, val version: Int)

/** Pure readers for the server's answers (tested on the laptop). Throw on anything unexpected. */
object PlaceServerJson {
    /** Every place and its latest version. A place id that isn't valid (§6) makes the whole answer junk. */
    fun index(body: String): List<PlaceSummary> {
        val places = JSONObject(body).getJSONArray("places")
        return (0 until places.length()).map { i ->
            val p = places.getJSONObject(i)
            val id = p.get("place_id") as? String ?: throw IllegalArgumentException("place_id must be text")
            require(AdminCert.PLACE_ID.matches(id)) { "bad place_id" }
            PlaceSummary(id, p.optString("place_name", id), wholeNumber(p.get("version")))
        }
    }

    fun latestVersion(body: String, placeId: String): Int {
        val places = JSONObject(body).getJSONArray("places")
        for (i in 0 until places.length()) {
            val p = places.getJSONObject(i)
            if (p.get("place_id") == placeId) return wholeNumber(p.get("version"))
        }
        return 0
    }

    fun answer(body: String): UploadAnswer {
        val o = JSONObject(body)
        val result = o.get("result") as? String ?: throw IllegalArgumentException("result must be text")
        return UploadAnswer(result, o.opt("version")?.let(::wholeNumber))
    }

    private fun wholeNumber(v: Any): Int = when (v) {
        is Int -> v
        is Long -> Math.toIntExact(v)
        else -> throw IllegalArgumentException("version must be a whole number")
    }
}
