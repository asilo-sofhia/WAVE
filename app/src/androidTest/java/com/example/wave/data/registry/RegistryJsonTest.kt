package com.example.wave.data.registry

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wave.model.BlockedAp
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Runs on a device/emulator because org.json is part of Android (not available in plain JUnit). */
@RunWith(AndroidJUnit4::class)
class RegistryJsonTest {

    /** docs/CONTRACT.md §2 example. */
    private val contractExample = """
        {
          "schema": "wave-registry/1",
          "version": 1,
          "key_id": "wave-2026-a",
          "issued_at": "2026-11-10T00:00:00Z",
          "expires_at": "2026-12-10T00:00:00Z",
          "campus_asns": [ { "asn": 0, "name": "TBD - measure on campus" } ],
          "networks": [
            {
              "ssid": "BSU-ICT-TESTLAB",
              "security": "WPA2-PSK",
              "aps": [
                { "bssid": "00:00:5e:00:53:01", "band": "2.4GHz", "channel": 6,
                  "building": "CICS Lab", "rssi_min": -85, "rssi_max": -25 }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesTheContractExample() {
        val r = RegistryJson.parse(contractExample.toByteArray())
        assertEquals(1, r.version)
        assertEquals("wave-2026-a", r.keyId)
        assertEquals(Instant.parse("2026-12-10T00:00:00Z"), r.expiresAt)
        val net = r.networks.single()
        assertEquals(SecurityType.WPA2_PERSONAL, net.security)
        val ap = net.aps.single()
        assertEquals("00:00:5e:00:53:01", ap.bssid)
        assertEquals(WifiBand.GHZ_2_4, ap.band)
        assertEquals(-85, ap.rssiMin)
    }

    @Test
    fun rejectsContractViolations() {
        listOf(
            contractExample.replace("wave-registry/1", "wave-registry/2"),
            contractExample.replace("00:00:5e:00:53:01", "00:00:5E:00:53:01"), // uppercase BSSID
            contractExample.replace("\"WPA2-PSK\"", "\"WPA2-Enterprise\""),
            contractExample.replace("\"2.4GHz\"", "\"2.4 GHz\""),
        ).forEach { bad -> assertThrows(Exception::class.java) { RegistryJson.parse(bad.toByteArray()) } }
    }

    @Test
    fun readsTheSignatureFile() {
        val sig = RegistryJson.parseSignature("""{ "key_id": "wave-dev", "alg": "Ed25519", "signature": "abc" }""".toByteArray())
        assertEquals("wave-dev", sig.keyId)
        assertEquals("Ed25519", sig.alg)
    }

    @Test
    fun parsesBlockedList() {
        val withBlocked = contractExample.trimEnd().removeSuffix("}") +
            """, "blocked": [ { "bssid": "6a:3f:00:11:22:9c", "ssid": "BSU Free WiFi", "reason": "Rogue hotspot" } ] }"""
        val r = RegistryJson.parse(withBlocked.toByteArray())
        assertEquals(listOf(BlockedAp("6a:3f:00:11:22:9c", "BSU Free WiFi", "Rogue hotspot")), r.blocked)
    }

    @Test
    fun missingBlockedIsEmpty() {
        assertEquals(emptyList<BlockedAp>(), RegistryJson.parse(contractExample.toByteArray()).blocked)
    }

    @Test
    fun badBlockedBssidIsRejected() {
        val bad = contractExample.trimEnd().removeSuffix("}") +
            """, "blocked": [ { "bssid": "6A:3F:00:11:22:9C", "ssid": "x", "reason": "r" } ] }"""
        assertThrows(Exception::class.java) { RegistryJson.parse(bad.toByteArray()) }
    }
}
