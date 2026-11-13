package com.example.wave.data.registry

import android.content.Context
import android.util.Log
import com.example.wave.data.RegistryRepository
import com.example.wave.data.RegistryState
import com.example.wave.verify.RegistrySignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.Instant

/**
 * Loads the signed registry bundled in the app's assets (registry/registry.json + registry.json.sig) and only
 * trusts it after the Ed25519 signature checks out (CONTRACT Rule #1: verify raw bytes first, then parse).
 *
 * Debug builds get the home test registry from backend/registry/ (copied by the copyHomeRegistry Gradle task,
 * git-ignored). Downloading from /v1/registry, caching and the rollback check come later (lane 3).
 */
class BundledRegistryRepository(
    context: Context,
    scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
) : RegistryRepository {

    private val assets = context.applicationContext.assets

    private val _state = MutableStateFlow<RegistryState>(RegistryState.Missing())
    override val state: StateFlow<RegistryState> = _state.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    override suspend fun refresh(): RegistryState = withContext(Dispatchers.IO) {
        val loaded = load()
        // Never replace a good registry with a missing or broken one.
        if (loaded is RegistryState.Missing && _state.value !is RegistryState.Missing) return@withContext _state.value
        if (loaded is RegistryState.Missing && loaded.problem != null) Log.w(TAG, "Registry rejected: ${loaded.problem}")
        _state.value = loaded
        loaded
    }

    private fun load(): RegistryState {
        val bytes = read(FILE) ?: return RegistryState.Missing()
        val sigBytes = read("$FILE.sig") ?: return RegistryState.Missing("signature file is missing")
        val sig = try {
            RegistryJson.parseSignature(sigBytes)
        } catch (e: Exception) {
            return RegistryState.Missing("signature file is unreadable")
        }
        if (sig.alg != "Ed25519") return RegistryState.Missing("unsupported signature algorithm ${sig.alg}")

        when (RegistrySignature.check(bytes, sig.keyId, sig.signature)) {
            RegistrySignature.Problem.UNKNOWN_KEY -> return RegistryState.Missing("signed by unknown key '${sig.keyId}'")
            RegistrySignature.Problem.BAD_SIGNATURE -> return RegistryState.Missing("signature doesn't match the file")
            null -> Unit
        }

        val registry = try {
            RegistryJson.parse(bytes)
        } catch (e: Exception) {
            return RegistryState.Missing("content breaks the contract: ${e.message}")
        }
        if (registry.keyId != sig.keyId) return RegistryState.Missing("key_id inside the file doesn't match its signature")

        return if (registry.expiresAt.isBefore(now())) RegistryState.Stale(registry) else RegistryState.Ready(registry)
    }

    private fun read(path: String): ByteArray? =
        try { assets.open(path).use { it.readBytes() } } catch (_: FileNotFoundException) { null }

    private companion object {
        const val TAG = "WaveRegistry"
        const val FILE = "registry/registry.json"
    }
}
