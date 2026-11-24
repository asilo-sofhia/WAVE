package com.example.wave.ui

import com.example.wave.data.admin.AdminStatus
import com.example.wave.data.admin.NoScreenLock
import com.example.wave.ui.screens.tools.NO_SCREEN_LOCK
import com.example.wave.ui.screens.tools.adminAction
import com.example.wave.ui.screens.tools.canOpenManageRegistry
import com.example.wave.verify.AdminCertificate
import com.example.wave.verify.CertResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.ProviderException
import java.time.Instant

class AdminAccessLogicTest {
    private val cert = AdminCertificate(
        "0123456789abcdef", "wave-test", "WAVE Test", "Test Admin", "", Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "r",
    )

    /** Every way into Manage registry (the gate, Admin Scan's "View details") needs a valid ID card in release builds. */
    @Test
    fun manageRegistryNeedsAValidCardInRelease() {
        assertFalse(canOpenManageRegistry(AdminStatus.NotStarted, debug = false))
        assertFalse(canOpenManageRegistry(AdminStatus.Requested("a", "b", "c"), debug = false))
        assertFalse(canOpenManageRegistry(AdminStatus.Enrolled(cert, CertResult.EXPIRED), debug = false))
        assertFalse(canOpenManageRegistry(AdminStatus.Enrolled(cert, CertResult.NOT_YET_VALID), debug = false))
        assertTrue(canOpenManageRegistry(AdminStatus.Enrolled(cert, CertResult.VALID), debug = false))
        assertTrue(canOpenManageRegistry(AdminStatus.NotStarted, debug = true))
    }

    /** Key or storage failures become a message on the card, never a crash. */
    @Test
    fun adminActionTurnsFailuresIntoMessages() {
        assertNull(adminAction { null })
        assertEquals(NO_SCREEN_LOCK, adminAction { throw NoScreenLock() })
        assertEquals("Name: 1–64 characters", adminAction { throw IllegalArgumentException("Name: 1–64 characters") }?.text)
        for (e in listOf(ProviderException("keystore"), IOException("disk full"), IllegalStateException("x"))) {
            val m = adminAction { throw e }
            assertTrue(m!!.isError)
            assertEquals("Something went wrong on this phone. Try again.", m.text)
        }
    }
}
