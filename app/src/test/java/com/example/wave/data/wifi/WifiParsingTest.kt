package com.example.wave.data.wifi

import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiParsingTest {

    @Test
    fun capabilitiesFollowTheContractTable() {
        val cases = mapOf(
            "[RSN-PSK-CCMP][ESS]" to SecurityType.WPA2_PERSONAL,
            "[WPA2-PSK-CCMP][ESS]" to SecurityType.WPA2_PERSONAL,
            "[WPA-PSK-TKIP][WPA2-PSK-CCMP][ESS]" to SecurityType.WPA2_PERSONAL,
            "[RSN-FT/PSK-CCMP][ESS]" to SecurityType.WPA2_PERSONAL,
            "[RSN-PSK-SHA256-CCMP][ESS]" to SecurityType.WPA2_PERSONAL,
            "[RSN-SAE-CCMP][ESS]" to SecurityType.WPA3_PERSONAL,
            "[RSN-PSK+SAE-CCMP][ESS]" to SecurityType.WPA2_WPA3_PERSONAL,
            "[WPA2-EAP-CCMP][ESS]" to SecurityType.UNSUPPORTED,
            "[RSN-OWE-CCMP][ESS]" to SecurityType.UNSUPPORTED,
            "[WEP][ESS]" to SecurityType.WEP,
            "[ESS]" to SecurityType.OPEN,
            "" to SecurityType.OPEN,
        )
        cases.forEach { (caps, expected) -> assertEquals(caps, expected, securityFromCapabilities(caps)) }
    }

    @Test
    fun bandsAndChannels() {
        assertEquals(WifiBand.GHZ_2_4, bandForFrequency(2412))
        assertEquals(1, channelForFrequency(2412))
        assertEquals(6, channelForFrequency(2437))
        assertEquals(14, channelForFrequency(2484))
        assertEquals(WifiBand.GHZ_5, bandForFrequency(5180))
        assertEquals(36, channelForFrequency(5180))
        assertEquals(149, channelForFrequency(5745))
        assertEquals(WifiBand.GHZ_6, bandForFrequency(5955))
        assertEquals(1, channelForFrequency(5955))
        assertEquals(2, channelForFrequency(5935))
        assertNull(channelForFrequency(2000))
        assertNull(channelForFrequency(2413))
    }

    @Test
    fun bssidAndSsidCleanup() {
        assertEquals("8c:3b:ad:72:10:4f", normalizeBssid("8C-3B-AD-72-10-4F"))
        assertEquals("8c:3b:ad:72:10:4f", bssidOrNull(" 8C:3B:AD:72:10:4F "))
        assertNull(bssidOrNull("02:00:00:00:00:00"))
        assertNull(bssidOrNull(null))
        assertEquals("BATSTATEU-ICT", cleanSsid("\"BATSTATEU-ICT\""))
        assertEquals("plain", cleanSsid("plain"))
        assertNull(cleanSsid("<unknown ssid>"))
        assertNull(cleanSsid("\"\""))
    }
}
