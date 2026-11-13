package com.example.wave

import com.example.wave.data.fake.FakeFixtures
import com.example.wave.data.fake.Scenario
import com.example.wave.model.CheckKind
import com.example.wave.model.CheckOutcome
import com.example.wave.model.Verdict
import com.example.wave.model.isLocallyAdministered
import com.example.wave.ui.components.checksSummary
import com.example.wave.ui.components.lastSeenLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChecksTest {

    private val scanned = Scenario.entries.flatMap { it.nearby + listOfNotNull(it.connection) }.distinct()

    @Test
    fun everyScannedNetworkHasMetricsAndAllChecks() {
        for (n in scanned) {
            assertNotNull("${n.ssid} metrics", n.metrics)
            assertEquals("${n.ssid} check order", CheckKind.entries, n.checks.map { it.kind })
        }
    }

    @Test
    fun failedChecksMatchWarningReasons() {
        for (n in scanned) {
            val failedReasons = n.checks.filter { it.outcome == CheckOutcome.FAILED }.mapNotNull { it.kind.reason }
            assertEquals("${n.ssid} ${n.bssid}", n.reasons.toSet(), failedReasons.toSet())
        }
    }

    @Test
    fun verdictFollowsChecks() {
        for (n in scanned) {
            val outcomes = n.checks.map { it.outcome }
            val expected = when {
                CheckOutcome.FAILED in outcomes -> Verdict.WARNING
                CheckOutcome.UNKNOWN in outcomes -> Verdict.UNKNOWN
                else -> Verdict.VERIFIED
            }
            assertEquals("${n.ssid} ${n.bssid}", expected, n.verdict)
        }
    }

    @Test
    fun hardwareAddressCheckMatchesTheBssidBit() {
        for (n in scanned) {
            val check = n.checks.first { it.kind == CheckKind.HARDWARE_ADDRESS }
            val expected = if (isLocallyAdministered(n.bssid)) CheckOutcome.FAILED else CheckOutcome.PASSED
            assertEquals(n.bssid, expected, check.outcome)
        }
    }

    @Test
    fun locallyAdministeredBit() {
        assertTrue(isLocallyAdministered("02:1A:7F:33:9C:01"))
        assertTrue(isLocallyAdministered("da:a1:19:00:00:01"))
        assertFalse(isLocallyAdministered("8c:3b:ad:72:10:4f"))
        assertFalse(isLocallyAdministered("not-a-mac"))
    }

    @Test
    fun summaries() {
        assertEquals("3 of 5 checks failed.", checksSummary(FakeFixtures.twin))
        assertEquals("All 5 checks passed.", checksSummary(FakeFixtures.campus))
        assertEquals("Not on the registry, so only 1 of 5 checks could run.", checksSummary(FakeFixtures.library))
    }

    @Test
    fun lastSeenLabels() {
        assertEquals("Not reported", lastSeenLabel(null, 10_000))
        assertEquals("Just now", lastSeenLabel(9_000, 10_000))
        assertEquals("30 s ago", lastSeenLabel(0, 30_000))
        assertEquals("2 min ago", lastSeenLabel(0, 125_000))
    }
}
