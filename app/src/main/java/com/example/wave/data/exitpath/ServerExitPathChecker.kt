package com.example.wave.data.exitpath

import com.example.wave.data.ExitPathChecker
import com.example.wave.data.RegistryState
import com.example.wave.data.registryOrNull
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.NetworkUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The real exit-path check (CONTRACT §4): asks the WAVE server which provider (ASN) this Wi-Fi exits through.
 *
 * - `asn` in the registry's `campus_asns` -> PASSED; any other ASN -> FAILED.
 * - No usable answer (any error, timeout, or `asn` null) is a failed attempt. After [retryDelaysMs].size + 1
 *   attempts, or after [overallTimeoutMs] in total -> UNREACHABLE ("Request fails 3 times in 30 s").
 * - A registry with no real campus ASN (empty, or only the placeholder 0) can't judge anything, so the server
 *   isn't asked and the result is UNREACHABLE, never FAILED: a FAILED would call the real network fake.
 *
 * Emits PENDING first, then exactly one result, then completes.
 */
class ServerExitPathChecker(
    private val fetcher: ExitPathFetcher,
    private val registry: StateFlow<RegistryState>,
    private val retryDelaysMs: List<Long> = listOf(2_000, 4_000),
    private val overallTimeoutMs: Long = 30_000,
) : ExitPathChecker {

    /**
     * Requests run here, not in the caller's coroutine: a blocked HTTP read ignores cancellation, and the caller
     * (time limit, or a Wi-Fi change in flatMapLatest) must not wait for it. Cancelling the request still asks
     * the fetcher to abort it.
     */
    private val requests = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun check(network: NetworkUi): Flow<ExitPathStatus> = flow {
        emit(ExitPathStatus.PENDING)
        val campus = campusAsns()
        if (campus.isEmpty()) {
            emit(ExitPathStatus.UNREACHABLE)
            return@flow
        }
        val asn = withTimeoutOrNull(overallTimeoutMs) { firstAsn() }
        emit(
            when (asn) {
                null -> ExitPathStatus.UNREACHABLE
                in campus -> ExitPathStatus.PASSED
                else -> ExitPathStatus.FAILED
            },
        )
    }

    /** The first ASN the server reports, or null when every attempt failed. */
    private suspend fun firstAsn(): Int? {
        for (attempt in 0..retryDelaysMs.size) {
            if (attempt > 0) delay(retryDelaysMs[attempt - 1])
            val request = requests.async { fetcher.fetch() }
            val asn = try {
                request.await().asn
            } catch (e: CancellationException) {
                request.cancel()
                throw e
            } catch (_: Exception) {
                null // IOException, SecurityException (per-app network block), ...: a failed attempt
            }
            if (asn != null) return asn
        }
        return null
    }

    private fun campusAsns(): Set<Int> =
        registry.value.registryOrNull?.campusAsns.orEmpty().map { it.asn }.filter { it != 0 }.toSet()
}
