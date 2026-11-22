package com.example.wave.data.exitpath

import java.io.IOException

/** The server's answer to `GET /v1/exit-path` (CONTRACT §4). Any field can be null when the server can't tell. */
data class ExitPathAnswer(
    val publicIp: String?,
    val asn: Int?,
    val asName: String?,
    /** The server's explanation when it can't tell the ASN (e.g. it sees a private address). */
    val note: String?,
)

/** One request to the exit-path endpoint. */
fun interface ExitPathFetcher {
    /** @throws IOException when there's no usable answer (no server address, not on Wi-Fi, timeout, HTTP error, bad JSON). */
    suspend fun fetch(): ExitPathAnswer
}
