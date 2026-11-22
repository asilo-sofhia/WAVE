package com.example.wave.data.registry

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Runs on a device because org.json is part of Android (not available in plain JUnit). */
@RunWith(AndroidJUnit4::class)
class RegistryResponseTest {

    @Test
    fun parsesServerResponse() {
        // registry_b64url "eyJ2IjoxfQ" = base64url of {"v":1}
        val file = parseRegistryResponse("""{"key_id":"wave-dev","registry_b64url":"eyJ2IjoxfQ","signature_b64url":"AAAA"}""")
        assertEquals(SignedRegistry("""{"v":1}""".toByteArray(), "wave-dev", "AAAA"), file)
    }

    @Test
    fun missingFieldThrowsIOException() {
        assertThrows(IOException::class.java) { parseRegistryResponse("""{"key_id":"wave-dev","registry_b64url":"eyJ2IjoxfQ"}""") }
    }

    @Test
    fun nonJsonResponseThrowsIOException() {
        assertThrows(IOException::class.java) { parseRegistryResponse("<html>") }
    }
}
