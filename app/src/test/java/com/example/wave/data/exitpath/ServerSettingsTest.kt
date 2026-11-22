package com.example.wave.data.exitpath

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSettingsTest {

    @Test
    fun keepsAPlainHttpsAddress() {
        assertEquals("https://abc.trycloudflare.com", normalizeServerUrl("https://abc.trycloudflare.com"))
    }

    @Test
    fun trimsSpacesAndTrailingSlash() {
        assertEquals("https://abc.trycloudflare.com", normalizeServerUrl("  https://abc.trycloudflare.com/  "))
    }

    @Test
    fun dropsAV1Suffix() {
        assertEquals("https://wave-x.onrender.com", normalizeServerUrl("https://wave-x.onrender.com/v1"))
        assertEquals("https://wave-x.onrender.com", normalizeServerUrl("https://wave-x.onrender.com/v1/"))
    }

    @Test
    fun rejectsPlainHttp() {
        assertNull(normalizeServerUrl("http://192.168.1.5:8000"))
    }

    @Test
    fun rejectsJunk() {
        assertNull(normalizeServerUrl(""))
        assertNull(normalizeServerUrl("abc"))
        assertNull(normalizeServerUrl("https://"))
    }
}
