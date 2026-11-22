package com.example.wave.data.exitpath

import android.content.SharedPreferences
import com.example.wave.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URISyntaxException

/**
 * The WAVE server's base address, e.g. `https://abc.trycloudflare.com` (no trailing slash, no `/v1`).
 * Null when the input isn't a usable https:// address: CONTRACT §4 is HTTPS only.
 */
fun normalizeServerUrl(input: String): String? {
    var text = input.trim().trimEnd('/')
    if (text.endsWith("/v1")) text = text.removeSuffix("/v1").trimEnd('/')
    val uri = try {
        URI(text)
    } catch (_: URISyntaxException) {
        return null
    }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
    if (uri.rawQuery != null || uri.rawFragment != null) return null
    return text
}

/**
 * Where the exit-path check sends its request. Saved on the phone (debug menu); falls back to [default],
 * which is the `wave.serverUrl` Gradle property baked in at build time (empty = none).
 */
class ServerSettings(
    private val prefs: SharedPreferences,
    private val default: String = BuildConfig.WAVE_SERVER_URL,
) {
    private val _url = MutableStateFlow(resolve())
    val url: StateFlow<String?> = _url.asStateFlow()

    fun current(): String? = url.value

    /** Blank = forget the saved address (back to the default). False = not an https:// address; nothing saved. */
    fun save(input: String): Boolean {
        if (input.isBlank()) {
            prefs.edit().remove(KEY).apply()
        } else {
            val normalized = normalizeServerUrl(input) ?: return false
            prefs.edit().putString(KEY, normalized).apply()
        }
        _url.value = resolve()
        return true
    }

    private fun resolve(): String? = prefs.getString(KEY, null) ?: normalizeServerUrl(default)

    private companion object {
        const val KEY = "exit_path_server_url"
    }
}
