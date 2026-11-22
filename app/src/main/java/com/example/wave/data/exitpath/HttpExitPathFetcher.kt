package com.example.wave.data.exitpath

import android.net.Network
import com.example.wave.data.net.httpsGet
import java.io.IOException
import java.net.URL

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

    override suspend fun fetch(): ExitPathAnswer {
        val base = serverUrl()?.takeIf { it.isNotBlank() } ?: throw IOException("No server address set")
        val network = wifiNetwork() ?: throw IOException("Not on Wi-Fi")
        val url = URL("$base/v1/exit-path")
        return parseExitPathAnswer(httpsGet(open = { network.openConnection(url) }, maxBytes = MAX_BODY_BYTES))
    }

    private companion object {
        const val MAX_BODY_BYTES = 16 * 1024
    }
}
