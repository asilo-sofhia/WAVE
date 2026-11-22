package com.example.wave.data.registry

import com.example.wave.data.RegistryRepository
import com.example.wave.data.RegistryState
import com.example.wave.data.registryOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One request for the server's current signed registry (`GET /v1/registry`, CONTRACT §4). */
fun interface RegistryDownloader {
    /** @throws java.io.IOException when there's no usable answer. */
    suspend fun download(): SignedRegistry
}

/** What one sync did. */
sealed interface RegistrySync {
    data class Updated(val version: Int) : RegistrySync
    data class UpToDate(val version: Int) : RegistrySync
    data class Rejected(val reason: String) : RegistrySync
    data class Failed(val message: String) : RegistrySync
    /** An automatic sync less than a minute after the last one: nothing was asked. */
    data object Skipped : RegistrySync
}

/**
 * The signed school registry, kept up to date from the WAVE server (CONTRACT §2, §4).
 *
 * - Start: the newer of two verified copies, the one bundled in the app and the one saved on the phone.
 * - Sync: download, verify, and keep it only if its version is higher (the rollback check: an attacker
 *   can't bring back an old list). The new copy is saved on the phone.
 * - Never replaces a good registry with a bad or missing one; a failed download changes nothing.
 */
class SyncingRegistryRepository(
    private val bundled: () -> SignedRegistry?,
    private val cache: RegistryCache,
    private val downloader: RegistryDownloader,
    private val verifier: RegistryVerifier,
    private val minIntervalMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : RegistryRepository {

    private val _state = MutableStateFlow<RegistryState>(RegistryState.Missing())
    override val state: StateFlow<RegistryState> = _state.asStateFlow()

    private val lock = Mutex()
    private var lastAttemptMs: Long? = null

    /** Load what's on the phone, then ask the server once. */
    suspend fun start() {
        loadLocal()
        sync()
    }

    /** The verified newer of the bundled and saved copies. */
    suspend fun loadLocal() = lock.withLock {
        val candidates = withContext(Dispatchers.IO) {
            listOfNotNull(runCatching { bundled() }.getOrNull(), cache.read()).map(verifier::verify)
        }
        val best = candidates.filter { it.registryOrNull != null }.maxByOrNull { it.registryOrNull!!.version }
        when {
            best != null -> _state.value = best
            _state.value.registryOrNull == null -> _state.value = candidates.firstOrNull() ?: RegistryState.Missing()
        }
    }

    /** Ask the server for its registry. Automatic calls ([force] false) run at most once per [minIntervalMs]. */
    suspend fun sync(force: Boolean = false): RegistrySync = lock.withLock {
        val now = clock()
        val last = lastAttemptMs
        if (!force && last != null && now - last < minIntervalMs) return@withLock RegistrySync.Skipped
        lastAttemptMs = now

        val file = try {
            downloader.download()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) { // IOException, SecurityException (per-app network block), ...
            return@withLock RegistrySync.Failed(e.message ?: e.javaClass.simpleName)
        }
        val downloaded = withContext(Dispatchers.IO) { verifier.verify(file) }
        val newVersion = downloaded.registryOrNull?.version
            ?: return@withLock RegistrySync.Rejected((downloaded as? RegistryState.Missing)?.problem ?: "not a valid registry")
        val current = _state.value.registryOrNull?.version
        when {
            current != null && newVersion < current ->
                RegistrySync.Rejected("older version v$newVersion; this phone has v$current")
            current != null && newVersion == current -> RegistrySync.UpToDate(current)
            else -> {
                withContext(Dispatchers.IO) { cache.write(file) }
                _state.value = downloaded
                RegistrySync.Updated(newVersion)
            }
        }
    }

    override suspend fun refresh(): RegistryState {
        sync()
        return state.value
    }
}
