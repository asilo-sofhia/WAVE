package com.example.wave.data.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLConnection
import javax.net.ssl.HttpsURLConnection

/**
 * GET over HTTPS and return the body as UTF-8 text. Used for the WAVE server (CONTRACT §4).
 *
 * [open] creates the connection, e.g. `network.openConnection(url)` to force Wi-Fi, or `url.openConnection()`.
 * Cancelling the call closes the connection, so a blocked connect or read ends right away.
 *
 * @throws IOException when the address isn't https, the status isn't 200, the body is over [maxBytes],
 *   or the network fails.
 */
suspend fun httpsGet(
    open: () -> URLConnection,
    maxBytes: Int,
    connectTimeoutMs: Int = 5_000,
    readTimeoutMs: Int = 8_000,
): String = coroutineScope {
    val conn = withContext(Dispatchers.IO) { open() } as? HttpsURLConnection
        ?: throw IOException("The server address must start with https://")
    // Blocking socket calls ignore coroutine cancellation; disconnect() from another thread unblocks them.
    val abortOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            conn.disconnect()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            try {
                conn.useCaches = false
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.setRequestProperty("Accept", "application/json")
                val code = conn.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                conn.inputStream.use { String(it.readAtMost(maxBytes), Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        }
    } finally {
        abortOnCancel.cancel()
    }
}

/** Reads the whole stream; more than [max] bytes is an error, never a silently cut answer. */
private fun InputStream.readAtMost(max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = read(buf)
        if (n < 0) return out.toByteArray()
        if (out.size() + n > max) throw IOException("The server's answer is larger than $max bytes")
        out.write(buf, 0, n)
    }
}

/**
 * POST a JSON [body] over HTTPS and return (status code, answer body) for ANY status: the WAVE server answers
 * refusals (400/403/409/413/503) with JSON too (CONTRACT §4). Cancelling closes the connection.
 *
 * @throws IOException when the address isn't https, the answer is over [maxBytes], or the network fails.
 */
suspend fun httpsPostJson(
    open: () -> URLConnection,
    body: String,
    maxBytes: Int,
    connectTimeoutMs: Int = 10_000,
    readTimeoutMs: Int = 60_000,
): Pair<Int, String> = coroutineScope {
    val conn = withContext(Dispatchers.IO) { open() } as? HttpsURLConnection
        ?: throw IOException("The server address must start with https://")
    val abortOnCancel = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            conn.disconnect()
        }
    }
    try {
        withContext(Dispatchers.IO) {
            try {
                val bytes = body.toByteArray(Charsets.UTF_8)
                conn.useCaches = false
                conn.connectTimeout = connectTimeoutMs
                conn.readTimeout = readTimeoutMs
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                conn.outputStream.use { it.write(bytes) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                code to (stream?.use { String(it.readAtMost(maxBytes), Charsets.UTF_8) } ?: "")
            } finally {
                conn.disconnect()
            }
        }
    } finally {
        abortOnCancel.cancel()
    }
}
