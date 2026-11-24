package com.example.wave.ui

import com.example.wave.data.admin.PublishOutcome
import com.example.wave.data.admin.UploadAnswer
import com.example.wave.ui.screens.tools.publishMessage
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the admin reads after tapping Publish, for every outcome (plan Task 3 table). */
class PublishMessageTest {
    private fun answered(result: String, version: Int? = null) = PublishOutcome.Answered(UploadAnswer(result, version), 5)
    private fun msg(o: PublishOutcome) = publishMessage(o, "WAVE Test")

    @Test
    fun serverAnswers() {
        assertEquals("Published version 5 to WAVE Test. Phones get it on their next update.", msg(answered("STORED", 5)))
        assertEquals("A newer list is already published. Tap Publish again.", msg(answered("STALE_VERSION")))
        assertEquals("Your ID card has expired. Ask the WAVE owner for a new one.", msg(answered("EXPIRED")))
        assertEquals("The server's clock and this phone's disagree. Check the phone's date.", msg(answered("NOT_YET_VALID")))
        for (r in listOf("UNKNOWN_KEY", "BAD_SIGNATURE", "WRONG_PLACE")) {
            assertEquals("The server refused this list ($r).", msg(answered(r)))
        }
        for (r in listOf("MALFORMED", "TOO_LARGE")) assertEquals("The server couldn't read this list ($r).", msg(answered(r)))
        for (r in listOf("UPLOADS_OFF", "STORAGE_UNAVAILABLE")) {
            assertEquals("The server can't save lists right now. Try again later.", msg(answered(r)))
        }
        assertEquals("The server answered SOMETHING_NEW.", msg(answered("SOMETHING_NEW")))
    }

    @Test
    fun phoneSideOutcomes() {
        assertEquals("Can't reach the WAVE server. Try again in a minute.", msg(PublishOutcome.ServerUnreachable))
        assertEquals("Not published: unlock was cancelled.", msg(PublishOutcome.NeedsUnlock))
        assertEquals("This phone's admin key is gone. Remove admin and enrol again.", msg(PublishOutcome.KeyGone))
        assertEquals("Install your admin ID card first.", msg(PublishOutcome.NotAdmin))
        assertEquals(
            "Couldn't sign on this phone. Try again; if it keeps failing, remove admin and enrol again.",
            msg(PublishOutcome.CouldNotSign),
        )
        assertEquals("Approve at least one network first.", msg(PublishOutcome.NothingToPublish))
    }
}
