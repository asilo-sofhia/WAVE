package com.example.wave.data.exitpath

import com.example.wave.data.ExitPathChecker
import com.example.wave.data.RegistryState
import com.example.wave.data.registryOrNull
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.NetworkUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * The real exit-path check (CONTRACT §4): asks the WAVE server which provider (ASN) this Wi-Fi exits through.
 *
 * - `asn` in the registry's `campus_asns` -> PASSED; any other ASN -> FAILED.
 *   An empty list, or the placeholder ASN 0, never matches.
 * - No usable answer (error, timeout, or `asn` null) is a failed attempt. After [retryDelaysMs].size + 1 attempts,
 *   or after [overallTimeoutMs] in total -> UNREACHABLE ("Request fails 3 times in 30 s").
 *
 * Emits PENDING first, then exactly one result, then completes.
 */
class ServerExitPathChecker(
    private val fetcher: ExitPathFetcher,
    private val registry: StateFlow<RegistryState>,
    private val retryDelaysMs: List<Long> = listOf(2_000, 4_000),
    private val overallTimeoutMs: Long = 30_000,
) : ExitPathChecker {

    override fun check(network: NetworkUi): Flow<ExitPathStatus> = flow {
        emit(ExitPathStatus.PENDING)
        val asn = withTimeoutOrNull(overallTimeoutMs) { firstAsn() }
        emit(
            when {
                asn == null -> ExitPathStatus.UNREACHABLE
                asn in campusAsns() -> ExitPathStatus.PASSED
                else -> ExitPathStatus.FAILED
            },
        )
    }

    /** The first ASN the server reports, or null when every attempt failed. */
    private suspend fun firstAsn(): Int? {
        for (attempt in 0..retryDelaysMs.size) {
            if (attempt > 0) delay(retryDelaysMs[attempt - 1])
            val asn = try {
                fetcher.fetch().asn
            } catch (_: IOException) {
                null
            }
            if (asn != null) return asn
        }
        return null
    }

    private fun campusAsns(): Set<Int> =
        registry.value.registryOrNull?.campusAsns.orEmpty().map { it.asn }.filter { it != 0 }.toSet()
}
