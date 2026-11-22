package com.example.wave.data.exitpath

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * `GET <server>/v1/exit-path`, sent over the Wi-Fi [Network] (CONTRACT §4). Opening the connection from the
 * Wi-Fi network object means Android can't route it over mobile data, which would report the carrier's ASN.
 *
 * @param serverUrl the base address (https, no trailing slash), or null when none is set.
 * @param wifiNetwork the connected Wi-Fi network, or null when not on Wi-Fi.
 */
class HttpExitPathFetcher(
    private val serverUrl: () -> String?,
    private val wifiNetwork: () -> Network?,
) : ExitPathFetcher {

    override suspend fun fetch(): ExitPathAnswer = withContext(Dispatchers.IO) {
        val base = serverUrl()?.takeIf { it.isNotBlank() } ?: throw IOException("No server address set")
        val network = wifiNetwork() ?: throw IOException("Not on Wi-Fi")
        val conn = network.openConnection(URL("$base/v1/exit-path")) as? HttpsURLConnection
            ?: throw IOException("The server address must start with https://")
        try {
            conn.useCaches = false
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val body = conn.inputStream.use { input ->
                val bytes = input.readNBytesCompat(MAX_BODY_BYTES)
                String(bytes, Charsets.UTF_8)
            }
            parseExitPathAnswer(body)
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
        const val MAX_BODY_BYTES = 16 * 1024
    }
}

/** InputStream.readNBytes is API 33+; minSdk is 29. */
private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
