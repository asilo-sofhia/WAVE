package com.example.wave.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecurityMappingTest {

    @Test
    fun contractValuesRoundTrip() {
        for (value in listOf("OPEN", "WPA2-PSK", "WPA3-SAE", "WPA2-WPA3")) {
            val type = SecurityMapping.fromContract(value)!!
            assertEquals(value, SecurityMapping.toContract(type))
        }
        assertEquals(SecurityType.WPA2_PERSONAL, SecurityMapping.fromContract("WPA2-PSK"))
    }

    @Test
    fun unknownValuesAreRejected() {
        assertNull(SecurityMapping.fromContract("wpa2-psk")) // case-sensitive, like the signing tool
        assertNull(SecurityMapping.fromContract("WPA2-Enterprise"))
        assertNull(SecurityMapping.toContract(SecurityType.WEP))
        assertNull(SecurityMapping.toContract(SecurityType.UNSUPPORTED))
    }

    @Test
    fun bands() {
        assertEquals(WifiBand.GHZ_2_4, SecurityMapping.bandFromContract("2.4GHz"))
        assertEquals(WifiBand.GHZ_5, SecurityMapping.bandFromContract("5GHz"))
        assertEquals(WifiBand.GHZ_6, SecurityMapping.bandFromContract("6GHz"))
        assertNull(SecurityMapping.bandFromContract("5 GHz"))
    }
}
