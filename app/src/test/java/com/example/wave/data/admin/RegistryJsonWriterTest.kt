package com.example.wave.data.admin

import com.example.wave.model.BlockedAp
import com.example.wave.model.CampusAsn
import com.example.wave.model.Registry
import com.example.wave.model.RegistryAp
import com.example.wave.model.RegistryNetwork
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** The exported draft must be exactly what backend/signing/wave_sign.py accepts (CONTRACT §2). */
class RegistryJsonWriterTest {

    private val registry = Registry(
        schema = "wave-registry/1", version = 2, keyId = "wave-2026-a",
        issuedAt = Instant.parse("2026-11-23T08:00:00Z"), expiresAt = Instant.parse("2027-05-22T08:00:00Z"),
        campusAsns = listOf(CampusAsn(9299, "PLDT")),
        networks = listOf(
            RegistryNetwork(
                "BATSTATEU-ICT", SecurityType.WPA2_PERSONAL,
                listOf(
                    RegistryAp("00:1a:2b:00:00:01", WifiBand.GHZ_2_4, 6, "Library", -85, -30),
                    RegistryAp("00:1a:2b:00:00:02", WifiBand.GHZ_5, 36, "Library", -85, -30),
                ),
            ),
        ),
        blocked = listOf(BlockedAp("6a:3f:00:11:22:9c", "BSU Free WiFi", "Rogue hotspot")),
    )

    @Test
    fun writesTheContractFormatExactly() {
        val expected = """
            {
              "schema": "wave-registry/1",
              "version": 2,
              "key_id": "wave-2026-a",
              "issued_at": "2026-11-23T08:00:00Z",
              "expires_at": "2027-05-22T08:00:00Z",
              "campus_asns": [
                { "asn": 9299, "name": "PLDT" }
              ],
              "networks": [
                {
                  "ssid": "BATSTATEU-ICT",
                  "security": "WPA2-PSK",
                  "aps": [
                    { "bssid": "00:1a:2b:00:00:01", "band": "2.4GHz", "channel": 6, "building": "Library", "rssi_min": -85, "rssi_max": -30 },
                    { "bssid": "00:1a:2b:00:00:02", "band": "5GHz", "channel": 36, "building": "Library", "rssi_min": -85, "rssi_max": -30 }
                  ]
                }
              ],
              "blocked": [
                { "bssid": "6a:3f:00:11:22:9c", "ssid": "BSU Free WiFi", "reason": "Rogue hotspot" }
              ]
            }
        """.trimIndent() + "\n"
        assertEquals(expected, RegistryJsonWriter.write(registry))
    }

    @Test
    fun usesLfOnly() {
        val out = RegistryJsonWriter.write(registry)
        assertFalse(out.contains('\r'))
        assertTrue(out.endsWith("}\n"))
    }

    @Test
    fun escapesQuotesBackslashesAndControlCharacters() {
        val tricky = registry.copy(
            blocked = listOf(BlockedAp("6a:3f:00:11:22:9c", "Say \"hi\" \\ there", "line1\nline2\ttab\u0001")),
        )
        val out = RegistryJsonWriter.write(tricky)
        assertTrue(out, out.contains(""""ssid": "Say \"hi\" \\ there""""))
        assertTrue(out, out.contains(""""reason": "line1\nline2\ttab\u0001""""))
    }

    @Test
    fun emptyListsAreWrittenAsEmptyArrays() {
        val out = RegistryJsonWriter.write(registry.copy(campusAsns = emptyList(), blocked = emptyList()))
        assertTrue(out, out.contains("\"campus_asns\": [],"))
        assertTrue(out, out.contains("\"blocked\": []\n"))
    }
}
