package com.example.wave.data.wifi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.MacAddress
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import com.example.wave.model.WifiAuth
import com.example.wave.model.WifiCredentials

/**
 * Joins a network from a QR code the way Android allows apps to (Android 10+ apps can't connect silently):
 * - Android 11+: the system "Save this network?" sheet (Settings.ACTION_WIFI_ADD_NETWORKS). The user taps Save
 *   and the phone connects.
 * - Android 10: a network suggestion; Android shows a notification asking to allow WAVE's suggested network.
 * [bssid] pins the connection to one access point (used when a fake copy shares the name).
 */
class WifiJoiner(context: Context) {
    private val wifi: WifiManager = context.applicationContext.getSystemService(WifiManager::class.java)

    sealed interface Prepared {
        /** Launch this for a result, then pass the result to [resultOf]. */
        data class SaveSheet(val intent: Intent) : Prepared

        /** Android 10: suggestion added (or not); nothing to launch. */
        data class Suggested(val ok: Boolean) : Prepared

        /** This kind of network (e.g. WEP) or this password can't be added by an app. */
        data class Unsupported(val reason: String) : Prepared
    }

    enum class Outcome { SAVED, ALREADY_SAVED, CANCELLED, FAILED }

    fun prepare(c: WifiCredentials, bssid: String? = null): Prepared {
        val suggestion = try {
            suggestion(c, bssid) ?: return Prepared.Unsupported("WAVE can't add this type of network. Use Wi-Fi settings.")
        } catch (_: IllegalArgumentException) { // e.g. a WPA password shorter than 8 characters
            return Prepared.Unsupported("The password in this code isn't valid for ${c.ssid}.")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Prepared.SaveSheet(
                Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
                    .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion)),
            )
        } else {
            val status = try { wifi.addNetworkSuggestions(listOf(suggestion)) } catch (_: SecurityException) { -1 }
            Prepared.Suggested(
                status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS ||
                    status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE,
            )
        }
    }

    /** Reads the Save sheet's answer (Android 11+). */
    fun resultOf(resultCode: Int, data: Intent?): Outcome {
        if (resultCode != Activity.RESULT_OK) return Outcome.CANCELLED
        val codes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST).orEmpty()
        } else {
            emptyList()
        }
        return when (codes.firstOrNull()) {
            Settings.ADD_WIFI_RESULT_SUCCESS -> Outcome.SAVED
            Settings.ADD_WIFI_RESULT_ALREADY_EXISTS -> Outcome.ALREADY_SAVED
            null -> Outcome.SAVED // some phones return OK without the list
            else -> Outcome.FAILED
        }
    }

    private fun suggestion(c: WifiCredentials, bssid: String?): WifiNetworkSuggestion? {
        val b = WifiNetworkSuggestion.Builder().setSsid(c.ssid)
        when (c.auth) {
            WifiAuth.OPEN -> Unit
            WifiAuth.WPA -> b.setWpa2Passphrase(c.password ?: return null)
            WifiAuth.SAE -> b.setWpa3Passphrase(c.password ?: return null)
            WifiAuth.OTHER -> return null
        }
        if (c.hidden) b.setIsHiddenSsid(true)
        if (bssid != null) b.setBssid(MacAddress.fromString(bssid))
        return b.build()
    }
}
