package com.example.wave.data.exitpath

import com.example.wave.data.ExitPathChecker
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.NetworkUi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Used with real Wi-Fi until the exit-path server exists (lane 3). It never claims PASSED or FAILED,
 * so real networks are never shown as "all clear" (and Wavey is never HAPPY) on fake evidence.
 */
class UnavailableExitPathChecker : ExitPathChecker {
    override fun check(network: NetworkUi): Flow<ExitPathStatus> = flow {
        emit(ExitPathStatus.PENDING)
        awaitCancellation()
    }
}
