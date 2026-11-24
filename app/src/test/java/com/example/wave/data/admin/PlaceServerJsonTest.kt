package com.example.wave.data.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Reading the WAVE server's place answers (CONTRACT §4). */
class PlaceServerJsonTest {

    @Test
    fun latestVersion() {
        val body = """{"places":[{"place_id":"a","place_name":"A","version":3,"updated_at":"x"},""" +
            """{"place_id":"wave-test","place_name":"WAVE Test","version":7,"updated_at":"x"}]}"""
        assertEquals(7, PlaceServerJson.latestVersion(body, "wave-test"))
        assertEquals(0, PlaceServerJson.latestVersion(body, "batstateu"))
        assertEquals(0, PlaceServerJson.latestVersion("""{"places":[]}""", "wave-test"))
    }

    @Test
    fun latestVersionJunkThrows() {
        for (junk in listOf("nope", """{"places":5}""", """{"places":[{"place_id":"wave-test","version":"7"}]}""", "")) {
            assertThrows(junk, Exception::class.java) { PlaceServerJson.latestVersion(junk, "wave-test") }
        }
    }

    @Test
    fun answers() {
        assertEquals(UploadAnswer("STORED", 2), PlaceServerJson.answer("""{"result":"STORED","place_id":"x","version":2}"""))
        assertEquals(UploadAnswer("STALE_VERSION", null), PlaceServerJson.answer("""{"result":"STALE_VERSION"}"""))
        for (junk in listOf("<html>", "{}", """{"result":5}""")) {
            assertThrows(junk, Exception::class.java) { PlaceServerJson.answer(junk) }
        }
    }
}
