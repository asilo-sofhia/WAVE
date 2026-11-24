package com.example.wave

import com.example.wave.data.fake.FakeFixtures
import com.example.wave.data.fake.Scenario
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.QrResult
import com.example.wave.model.Verdict
import com.example.wave.model.WarningReason
import com.example.wave.model.WaveyMood
import com.example.wave.model.needsExitPathCheck
import com.example.wave.model.waveyMoodFor
import com.example.wave.model.withExitPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WaveyRulesTest {

    @Test
    fun theThreeVerdictMoodsAreTheSameEverywhere() {
        for (exit in ExitPathStatus.entries.filter { it != ExitPathStatus.FAILED } + listOf<ExitPathStatus?>(null)) {
            for (connected in listOf(true, false)) {
                val case = "exit=$exit connected=$connected"
                assertEquals(case, WaveyMood.HAPPY, waveyMoodFor(Verdict.VERIFIED, exit, connected))
                assertEquals(case, WaveyMood.CONFUSED, waveyMoodFor(Verdict.UNKNOWN, exit, connected))
                assertEquals(case, WaveyMood.SCARED, waveyMoodFor(Verdict.WARNING, exit, connected))
            }
        }
        assertEquals(WaveyMood.HAPPY, waveyMoodFor(Verdict.VERIFIED))
        assertEquals(WaveyMood.CONFUSED, waveyMoodFor(Verdict.UNKNOWN))
        assertEquals(WaveyMood.SCARED, waveyMoodFor(Verdict.WARNING))
    }

    @Test
    fun happyNeverWhenTheExitPathFailed() {
        assertEquals(WaveyMood.SCARED, waveyMoodFor(Verdict.VERIFIED, ExitPathStatus.FAILED, connected = true))
        assertEquals(WaveyMood.SCARED, waveyMoodFor(Verdict.WARNING, null, connected = true))
    }

    @Test
    fun wavingIsNeverAVerdict() {
        for (verdict in Verdict.entries + listOf<Verdict?>(null)) {
            for (exit in ExitPathStatus.entries + listOf<ExitPathStatus?>(null)) {
                for (connected in listOf(true, false)) {
                    assertNotEquals(WaveyMood.WAVING, waveyMoodFor(verdict, exit, connected))
                }
            }
        }
        for (qr in QrResult.entries) assertNotEquals(WaveyMood.WAVING, waveyMoodFor(qr))
    }

    @Test
    fun nothingCheckedYetIsNormal() {
        assertEquals(WaveyMood.NORMAL, waveyMoodFor(null, null, connected = false))
        assertEquals(WaveyMood.NORMAL, waveyMoodFor(null, ExitPathStatus.PENDING, connected = true))
    }

    @Test
    fun qrMoods() {
        // A readable Wi-Fi code waits for its network check; codes that can't be used are SAD.
        assertEquals(WaveyMood.NORMAL, waveyMoodFor(QrResult.VALID))
        assertEquals(WaveyMood.NORMAL, waveyMoodFor(QrResult.UNSIGNED))
        assertEquals(WaveyMood.NORMAL, waveyMoodFor(QrResult.UNKNOWN_KEY))
        assertEquals(WaveyMood.SAD, waveyMoodFor(QrResult.BAD_SIGNATURE))
        assertEquals(WaveyMood.SAD, waveyMoodFor(QrResult.EXPIRED))
        assertEquals(WaveyMood.SAD, waveyMoodFor(QrResult.MALFORMED))
    }

    @Test
    fun sadIsNeverANetworkMood() {
        for (verdict in Verdict.entries + listOf<Verdict?>(null)) {
            for (exit in ExitPathStatus.entries + listOf<ExitPathStatus?>(null)) {
                for (connected in listOf(true, false)) {
                    assertNotEquals(WaveyMood.SAD, waveyMoodFor(verdict, exit, connected))
                }
            }
        }
    }

    @Test
    fun failedExitPathTurnsNetworkIntoWarning() {
        val failed = FakeFixtures.campus.withExitPath(ExitPathStatus.FAILED)
        assertEquals(Verdict.WARNING, failed.verdict)
        assertEquals(listOf(WarningReason.EXIT_ASN_MISMATCH), failed.reasons)
        assertEquals(FakeFixtures.campus, FakeFixtures.campus.withExitPath(ExitPathStatus.PASSED))
    }

    @Test
    fun unreachableKeepsVerdictAndMood() {
        val n = FakeFixtures.campus // VERIFIED
        assertEquals(n, n.withExitPath(ExitPathStatus.UNREACHABLE))
        assertEquals(WaveyMood.HAPPY, waveyMoodFor(Verdict.VERIFIED, ExitPathStatus.UNREACHABLE, connected = true))
    }

    @Test
    fun onlyVerifiedNetworksGetTheExitPathCheck() {
        assertEquals(true, FakeFixtures.campus.needsExitPathCheck())
        assertEquals(false, FakeFixtures.library.needsExitPathCheck()) // UNKNOWN
        assertEquals(false, FakeFixtures.twin.needsExitPathCheck()) // WARNING
    }

    @Test
    fun scenarioCoversUnreachable() {
        assertEquals(true, Scenario.entries.any { it.exitPath == ExitPathStatus.UNREACHABLE })
    }
}
