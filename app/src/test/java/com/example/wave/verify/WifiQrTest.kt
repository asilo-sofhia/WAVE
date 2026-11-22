package com.example.wave.verify

import com.example.wave.data.RegistryState
import com.example.wave.data.qr.SignedQrVerifier
import com.example.wave.model.QrResult
import com.example.wave.model.Registry
import com.example.wave.model.RegistryNetwork
import com.example.wave.model.SecurityType
import com.example.wave.model.Verdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The Kotlin QR checker must agree with backend wave_sign.py verify_qr on every case in test_vectors.json. */
class WifiQrTest {

    private val vectors = requireNotNull(javaClass.classLoader?.getResource("test_vectors.json")).readText()

    /** Minimal JSON string reader for the flat vector file (handles \" \\ \/ \n \t \uXXXX). */
    private fun unescapeJson(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\') { out.append(c); i++; continue }
            when (val e = s[i + 1]) {
                'u' -> { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                else -> { out.append(e); i += 2 }
            }
        }
        return out.toString()
    }

    private fun str(obj: String, name: String) =
        unescapeJson(Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj)!!.groupValues[1])

    private val trusted = mapOf("wave-test" to Regex("\"wave-test\"\\s*:\\s*\"([^\"]+)\"").find(vectors)!!.groupValues[1])
    private val today: LocalDate = LocalDate.parse(Regex("\"qr_today\"\\s*:\\s*\"(\\d{8})\"").find(vectors)!!.groupValues[1]
        .let { "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6)}" })

    private val cases: List<String> = vectors.substringAfter("\"qr_cases\"").let { section ->
        Regex("\\{(?:[^{}\"]|\"(?:[^\"\\\\]|\\\\.)*\")*\\}").findAll(section).map { it.value }.toList()
    }

    @Test
    fun agreesWithEveryBackendQrVector() {
        assertEquals("expected all 16 QR cases", 16, cases.size)
        for (case in cases) {
            val result = WifiQr.verify(str(case, "raw"), trusted, today)
            assertEquals(str(case, "name"), QrResult.valueOf(str(case, "expect")), result.result)
        }
    }

    @Test
    fun specialCharactersAreUnescaped() {
        val raw = str(cases.first { str(it, "name") == "valid_special_chars" }, "raw")
        val check = WifiQr.verify(raw, trusted, today)
        assertEquals(QrResult.VALID, check.result)
        assertEquals("Café;Lab:1, \"A\"", check.ssid)
    }

    @Test
    fun theWifiPasswordIsNeverShown() {
        val raw = "WIFI:T:WPA;S:Home;P:my\\;secret;WAVE-KID:wave-test;WAVE-UNTIL:20261210;WAVE-SIG:x;;"
        val shown = WifiQr.maskPassword(raw)
        assertFalse(shown.contains("secret"))
        assertTrue(shown.startsWith("WIFI:T:WPA;S:Home;P:••••••••;WAVE-KID:"))
    }

    @Test
    fun validCodeForARegisteredNetworkIsVerifiedOtherwiseUnknown() = runBlocking {
        val raw = str(cases.first { str(it, "name") == "valid_wpa" }, "raw") // S:BSU-ICT-TESTLAB, T:WPA
        val registry = Registry(
            "wave-registry/1", 1, "wave-test", Instant.EPOCH, Instant.MAX, emptyList(),
            listOf(RegistryNetwork("BSU-ICT-TESTLAB", SecurityType.WPA2_PERSONAL, emptyList())),
        )
        // Signed + listed in the registry -> Verified.
        val registered = SignedQrVerifier(MutableStateFlow(RegistryState.Ready(registry)), { today }, trusted).verify(raw)
        assertEquals(QrResult.VALID, registered.result)
        assertEquals("BSU-ICT-TESTLAB", registered.network?.ssid)
        assertEquals(Verdict.VERIFIED, registered.network?.verdict)
        assertEquals("wave-test", registered.signer)
        assertFalse(registered.rawPayload.contains("testlab-2026")) // password masked

        // Signed but not on the registry (or no registry) -> Unknown (CONTRACT §3).
        val noRegistry = SignedQrVerifier(MutableStateFlow(RegistryState.Missing()), { today }, trusted).verify(raw)
        assertEquals(QrResult.VALID, noRegistry.result)
        assertEquals(Verdict.UNKNOWN, noRegistry.network?.verdict)

        // The real app never trusts the vectors' throwaway key.
        val appKeys = SignedQrVerifier(MutableStateFlow(RegistryState.Ready(registry)), { today }).verify(raw)
        assertEquals(QrResult.UNKNOWN_KEY, appKeys.result)
    }
}
