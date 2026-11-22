package com.example.wave

import com.example.wave.data.fake.FakeFixtures
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.QrResult
import com.example.wave.model.Verdict
import com.example.wave.model.WarningReason
import com.example.wave.model.WaveyMood
import com.example.wave.model.waveyMoodFor
import com.example.wave.model.withExitPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WaveyRulesTest {

    @Test
    fun happyOnlyWhenConnectedVerifiedAndExitPassed() {
        assertEquals(WaveyMood.HAPPY, waveyMoodFor(Verdict.VERIFIED, ExitPathStatus.PASSED, connected = true))
        for (verdict in Verdict.entries) {
            for (exit in ExitPathStatus.entries + listOf<ExitPathStatus?>(null)) {
                for (connected in listOf(true, false)) {
                    val isHappyCase = verdict == Verdict.VERIFIED && exit == ExitPathStatus.PASSED && connected
                    if (!isHappyCase) {
                        assertNotEquals(
                            "verdict=$verdict exit=$exit connected=$connected",
                            WaveyMood.HAPPY,
                            waveyMoodFor(verdict, exit, connected),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun connectedToWarningOrFailedExitIsScared() {
        assertEquals(WaveyMood.SCARED, waveyMoodFor(Verdict.WARNING, null, connected = true))
        assertEquals(WaveyMood.SCARED, waveyMoodFor(Verdict.VERIFIED, ExitPathStatus.FAILED, connected = true))
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
    fun nearbyMoods() {
        assertEquals(WaveyMood.UNCOMFORTABLE, waveyMoodFor(Verdict.WARNING, null, connected = false))
        assertEquals(WaveyMood.CURIOUS, waveyMoodFor(Verdict.UNKNOWN, null, connected = false))
        assertEquals(WaveyMood.NORMAL, waveyMoodFor(Verdict.VERIFIED, null, connected = false))
        assertEquals(WaveyMood.NORMAL, waveyMoodFor(Verdict.VERIFIED, ExitPathStatus.PENDING, connected = true))
        assertEquals(WaveyMood.NORMAL, waveyMoodFor(null, null, connected = false))
    }

    @Test
    fun qrMoods() {
        assertEquals(WaveyMood.NORMAL, waveyMoodFor(QrResult.VALID))
        assertEquals(WaveyMood.SAD, waveyMoodFor(QrResult.BAD_SIGNATURE))
        assertEquals(WaveyMood.SAD, waveyMoodFor(QrResult.EXPIRED))
        assertEquals(WaveyMood.SAD, waveyMoodFor(QrResult.UNSIGNED))
        assertEquals(WaveyMood.SAD, waveyMoodFor(QrResult.MALFORMED))
        assertEquals(WaveyMood.SAD, waveyMoodFor(QrResult.UNKNOWN_KEY))
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
}
