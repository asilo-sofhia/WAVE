package com.example.wave.data.wifi

import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand

/*
 * Pure parsing of Android Wi-Fi values. No Android imports, so all of it is covered by plain JUnit tests.
 */

/**
 * Android ScanResult.capabilities -> SecurityType, following docs/CONTRACT.md §2 (checked in this order):
 * PSK + SAE -> WPA2/WPA3 · SAE -> WPA3 · PSK -> WPA2 · EAP or OWE -> UNSUPPORTED · WEP -> WEP · else OPEN.
 * WEP isn't in the contract table; without its own row it would fall through to OPEN, which is wrong.
 */
fun securityFromCapabilities(capabilities: String): SecurityType {
    val caps = capabilities.uppercase()
    val psk = "PSK" in caps
    val sae = "SAE" in caps
    return when {
        psk && sae -> SecurityType.WPA2_WPA3_PERSONAL
        sae -> SecurityType.WPA3_PERSONAL
        psk -> SecurityType.WPA2_PERSONAL
        "EAP" in caps || "OWE" in caps -> SecurityType.UNSUPPORTED
        "WEP" in caps -> SecurityType.WEP
        else -> SecurityType.OPEN
    }
}

/** 2.4 GHz below 3 GHz, 6 GHz from 5925 MHz, 5 GHz in between. */
fun bandForFrequency(mhz: Int): WifiBand = when {
    mhz < 3000 -> WifiBand.GHZ_2_4
    mhz < 5925 -> WifiBand.GHZ_5
    else -> WifiBand.GHZ_6
}

/** IEEE channel number for a centre frequency, or null if it isn't a standard Wi-Fi channel. */
fun channelForFrequency(mhz: Int): Int? = when (mhz) {
    2484 -> 14
    in 2412..2472 -> ((mhz - 2407) / 5).takeIf { (mhz - 2407) % 5 == 0 }
    5935 -> 2
    in 5955..7115 -> ((mhz - 5950) / 5).takeIf { (mhz - 5950) % 5 == 0 }
    in 5000..5895 -> ((mhz - 5000) / 5).takeIf { (mhz - 5000) % 5 == 0 }
    else -> null
}

/** "8C-3B-AD-72-10-4F" / "8C:3B:AD:72:10:4F" -> "8c:3b:ad:72:10:4f". */
fun normalizeBssid(raw: String): String = raw.trim().lowercase().replace('-', ':')

/** Android reports "02:00:00:00:00:00" when it hides the real BSSID. */
fun bssidOrNull(raw: String?): String? =
    raw?.let(::normalizeBssid)?.takeUnless { it.isBlank() || it == HIDDEN_BSSID }

/** WifiInfo.getSSID() is quoted ("\"Name\"") or "<unknown ssid>" when hidden. */
fun cleanSsid(raw: String?): String? {
    if (raw == null || raw == UNKNOWN_SSID) return null
    val unquoted = if (raw.length >= 2 && raw.startsWith('"') && raw.endsWith('"')) raw.substring(1, raw.length - 1) else raw
    return unquoted.ifBlank { null }
}

const val HIDDEN_BSSID = "02:00:00:00:00:00"
const val UNKNOWN_SSID = "<unknown ssid>"
