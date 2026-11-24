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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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

    /**
     * With several lists, only the ASNs of the list that holds the connected access point count: a place list
     * without a provider can't judge (never FAILED because ANOTHER place's provider differs).
     */
    @Test
    fun onlyTheConnectedListsAsnsCount() {
        fun perList(asnsForBssid: Set<Int>) = ServerExitPathChecker(
            CountingFetcher { answer(4775) }, registry(9299), retryDelaysMs = listOf(0, 0),
            asnsFor = { bssid -> if (bssid == FakeFixtures.campus.bssid) asnsForBssid else setOf(1) },
        )
        assertEquals(listOf(PENDING, UNREACHABLE), run(perList(emptySet())))
        assertEquals(listOf(PENDING, PASSED), run(perList(setOf(4775))))
        assertEquals(listOf(PENDING, FAILED), run(perList(setOf(9299))))
    }

    /** A registry whose campus ASN was never measured (placeholder 0) can't judge anything: never FAILED. */
    @Test
    fun placeholderAsnZeroCantBeChecked() {
        val fetcher = CountingFetcher { answer(0) }
        assertEquals(listOf(PENDING, UNREACHABLE), run(checker(fetcher, 0)))
        assertEquals(0, fetcher.calls.get())
    }

    @Test
    fun emptyCampusAsnsCantBeChecked() {
        val fetcher = CountingFetcher { answer(4775) }
        assertEquals(listOf(PENDING, UNREACHABLE), run(checker(fetcher)))
        assertEquals(0, fetcher.calls.get())
    }

    /** E.g. SecurityException from a per-app network block: must not crash the screen. */
    @Test
    fun nonIoExceptionCountsAsFailedAttempt() {
        val fetcher = CountingFetcher { throw SecurityException("Permission denied (missing INTERNET permission?)") }
        assertEquals(listOf(PENDING, UNREACHABLE), run(checker(fetcher, 4775)))
        assertEquals(3, fetcher.calls.get())
    }

    /** A real HTTP call blocks a thread and ignores coroutine cancellation; the 30 s limit must still hold. */
    @Test
    fun threadBlockingFetcherStillTimesOut() {
        val release = CountDownLatch(1)
        val fetcher = CountingFetcher { withContext(Dispatchers.IO) { release.await(5, TimeUnit.SECONDS) }; answer(4775) }
        val start = System.nanoTime()
        try {
            assertEquals(listOf(PENDING, UNREACHABLE), runBlocking { checker(fetcher, 4775, timeoutMs = 200).check(FakeFixtures.campus).toList() })
            assertTrue("took ${(System.nanoTime() - start) / 1_000_000} ms", System.nanoTime() - start < 1_500_000_000L)
        } finally {
            release.countDown()
        }
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
