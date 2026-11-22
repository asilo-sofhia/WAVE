package com.example.wave.data.exitpath

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** Runs on a device/emulator because org.json is part of Android (not available in plain JUnit). */
@RunWith(AndroidJUnit4::class)
class ExitPathJsonTest {

    @Test
    fun parsesFullAnswer() {
        // docs/CONTRACT.md §4 example
        val json = """
            {
              "public_ip": "203.0.113.7",
              "asn": 4775,
              "as_name": "Globe Telecoms",
              "checked_at": "2026-11-10T03:00:00Z"
            }
        """.trimIndent()
        assertEquals(ExitPathAnswer("203.0.113.7", 4775, "Globe Telecoms", null), parseExitPathAnswer(json))
    }

    @Test
    fun parsesUnknownAnswerWithNote() {
        val json = """{"public_ip":null,"asn":null,"as_name":null,"checked_at":"2026-11-10T03:00:00Z","note":"private"}"""
        val answer = parseExitPathAnswer(json)
        assertNull(answer.asn)
        assertNull(answer.publicIp)
        assertEquals("private", answer.note)
    }

    @Test
    fun badJsonThrowsIOException() {
        assertThrows(IOException::class.java) { parseExitPathAnswer("<html>") }
    }
}
