package com.example.wave.data.exitpath

import com.example.wave.data.RegistryState
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.model.CampusAsn
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.ExitPathStatus.FAILED
import com.example.wave.model.ExitPathStatus.PASSED
import com.example.wave.model.ExitPathStatus.PENDING
import com.example.wave.model.ExitPathStatus.UNREACHABLE
import com.example.wave.model.Registry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ServerExitPathCheckerTest {

    private fun registry(vararg asns: Int) = MutableStateFlow<RegistryState>(
        RegistryState.Ready(
            Registry(
                schema = "wave-registry/1",
                version = 1,
                keyId = "wave-dev",
                issuedAt = Instant.parse("2026-11-01T00:00:00Z"),
                expiresAt = Instant.parse("2027-11-01T00:00:00Z"),
                campusAsns = asns.map { CampusAsn(it, "AS$it") },
                networks = emptyList(),
            ),
        ),
    )

    private fun answer(asn: Int?) = ExitPathAnswer(publicIp = "203.0.113.7", asn = asn, asName = "Test", note = null)

    /** Counts calls; each call runs [reply] with the 1-based call number. */
    private class CountingFetcher(private val reply: suspend (Int) -> ExitPathAnswer) : ExitPathFetcher {
        val calls = AtomicInteger()
        override suspend fun fetch(): ExitPathAnswer = reply(calls.incrementAndGet())
    }

    private fun checker(fetcher: ExitPathFetcher, vararg asns: Int, timeoutMs: Long = 30_000) =
        ServerExitPathChecker(fetcher, registry(*asns), retryDelaysMs = listOf(0, 0), overallTimeoutMs = timeoutMs)

    private fun run(c: ServerExitPathChecker): List<ExitPathStatus> =
        runBlocking { withTimeout(2_000) { c.check(FakeFixtures.campus).toList() } }

    @Test
    fun matchingAsnPasses() {
        assertEquals(listOf(PENDING, PASSED), run(checker(CountingFetcher { answer(4775) }, 4775)))
    }

    @Test
    fun otherAsnFails() {
        assertEquals(listOf(PENDING, FAILED), run(checker(CountingFetcher { answer(9299) }, 4775)))
    }

    @Test
    fun placeholderAsnZeroNeverMatches() {
        assertEquals(listOf(PENDING, FAILED), run(checker(CountingFetcher { answer(0) }, 0)))
    }

    @Test
    fun emptyCampusAsnsFails() {
        assertEquals(listOf(PENDING, FAILED), run(checker(CountingFetcher { answer(4775) })))
    }

    @Test
    fun twoFailuresThenSuccessPasses() {
        val fetcher = CountingFetcher { n -> if (n < 3) throw IOException("timeout") else answer(4775) }
        assertEquals(listOf(PENDING, PASSED), run(checker(fetcher, 4775)))
        assertEquals(3, fetcher.calls.get())
    }

    @Test
    fun threeFailuresIsUnreachable() {
        val fetcher = CountingFetcher { throw IOException("connection refused") }
        assertEquals(listOf(PENDING, UNREACHABLE), run(checker(fetcher, 4775)))
        assertEquals(3, fetcher.calls.get())
    }

    @Test
    fun nullAsnCountsAsFailedAttempt() {
        val fetcher = CountingFetcher { ExitPathAnswer(null, null, null, note = "private address") }
        assertEquals(listOf(PENDING, UNREACHABLE), run(checker(fetcher, 4775)))
        assertEquals(3, fetcher.calls.get())
    }

    @Test
    fun hangingServerTimesOut() {
        val fetcher = CountingFetcher { awaitCancellation() }
        assertEquals(listOf(PENDING, UNREACHABLE), run(checker(fetcher, 4775, timeoutMs = 200)))
    }

    @Test
    fun cancellationStopsRetries() = runBlocking {
        val fetcher = CountingFetcher { throw IOException("down") }
        val c = ServerExitPathChecker(fetcher, registry(4775), retryDelaysMs = listOf(10_000, 10_000))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { c.check(FakeFixtures.campus).collect {} }
        while (fetcher.calls.get() == 0) yield()
        job.cancel()
        job.join()
        assertEquals(1, fetcher.calls.get())
    }
}
