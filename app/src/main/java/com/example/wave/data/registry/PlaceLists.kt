package com.example.wave.data.registry

import com.example.wave.data.admin.PlaceServerJson
import com.example.wave.data.admin.PlaceSummary
import com.example.wave.data.net.httpsGet
import com.example.wave.verify.AdminCert
import com.example.wave.verify.PlaceList
import com.example.wave.verify.PlacePackage
import com.example.wave.verify.WaveKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URL
import java.time.Instant

/** The WAVE server's place lists, for users' phones (CONTRACT §4). */
interface PlaceDirectory {
    /** `GET /v1/places`. Throws IOException when there's no usable answer. */
    suspend fun index(): List<PlaceSummary>

    /** `GET /v1/places/{id}/registry`: the package text. Throws IOException. */
    suspend fun download(placeId: String): String
}

class HttpPlaceDirectory(private val baseUrl: () -> String?) : PlaceDirectory {
    private fun url(path: String): URL = URL((baseUrl()?.trimEnd('/') ?: throw IOException("No WAVE server address set")) + path)

    // Long read timeout: a sleeping Render server takes up to a minute to answer.
    override suspend fun index(): List<PlaceSummary> {
        val body = httpsGet({ url("/v1/places").openConnection() }, maxBytes = MAX_INDEX_BYTES, readTimeoutMs = 60_000)
        return try { PlaceServerJson.index(body) } catch (e: Exception) { throw IOException("The place list isn't readable", e) }
    }

    override suspend fun download(placeId: String): String =
        httpsGet({ url("/v1/places/$placeId/registry").openConnection() }, maxBytes = MAX_PACKAGE_BYTES, readTimeoutMs = 60_000)

    companion object {
        const val MAX_INDEX_BYTES = 1_048_576
        const val MAX_PACKAGE_BYTES = 524_288
    }
}

/** One file per place in [dir] (`<place_id>.json`, the package exactly as downloaded). Checked again on every load. */
class PlaceListStore(private val dir: File) {
    fun readAll(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            try { f.readText() } catch (_: IOException) { null }
        }

    /** [placeId] is a checked place id (lowercase letters, digits, '-'), so it is a safe file name. */
    fun write(placeId: String, packageJson: String) {
        require(AdminCert.PLACE_ID.matches(placeId)) { "bad place id" }
        dir.mkdirs()
        val target = File(dir, "$placeId.json")
        val tmp = File(dir, "$placeId.json.tmp")
        tmp.writeText(packageJson)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw IOException("Couldn't save the $placeId list")
            }
        }
    }
}

/** What one place sync did: places updated, places refused (and why), or why the index failed. */
data class PlacesSync(val updated: List<String>, val refused: Map<String, String>, val failed: String?)

/**
 * Every place's signed Wi-Fi list on this phone (CONTRACT §7, spec Flows 3).
 *
 * - Load: every saved package, checked again from the root down (a damaged copy is ignored).
 * - Sync: the server's index, then only places whose version is higher than the saved one. Each download is
 *   kept only if it is VALID, for the place the index named, and newer than the saved copy (rollback check).
 *   One bad place never stops the others; a failed index changes nothing. Places that vanish from the index
 *   are kept (removing a place needs a signed revocation, phase 6).
 * - [lists] holds only lists still inside their expires_at: an expired admin list stops vouching.
 */
class PlaceListsRepository(
    private val directory: PlaceDirectory,
    private val store: PlaceListStore,
    private val rootKeys: Map<String, String> = WaveKeys.roots,
    private val now: () -> Instant = Instant::now,
    private val minIntervalMs: Long = 60_000,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val _lists = MutableStateFlow<Map<String, PlaceList>>(emptyMap())
    val lists: StateFlow<Map<String, PlaceList>> = _lists.asStateFlow()

    private val lock = Mutex()
    private var saved: Map<String, PlaceList> = emptyMap()
    private var lastAttemptMs: Long? = null

    suspend fun loadLocal() = lock.withLock {
        val found = withContext(Dispatchers.IO) {
            store.readAll().mapNotNull { PlacePackage.verify(it, rootKeys).second }
        }
        saved = found.groupBy { it.placeId }.mapValues { (_, l) -> l.maxBy { it.registry.version } }
        publish()
    }

    /** Never throws except on cancel. Automatic calls ([force] false) run at most once per [minIntervalMs]. */
    suspend fun sync(force: Boolean = false): PlacesSync = lock.withLock {
        val nowMs = clockMs()
        val last = lastAttemptMs
        if (!force && last != null && nowMs - last < minIntervalMs) return@withLock PlacesSync(emptyList(), emptyMap(), null)
        lastAttemptMs = nowMs

        val index = try {
            directory.index()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock PlacesSync(emptyList(), emptyMap(), e.message ?: e.javaClass.simpleName)
        }
        val updated = mutableListOf<String>()
        val refused = linkedMapOf<String, String>()
        for (entry in index) {
            val have = saved[entry.placeId]?.registry?.version ?: 0
            if (entry.version <= have) continue
            val text = try {
                directory.download(entry.placeId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refused[entry.placeId] = "download failed: ${e.message ?: e.javaClass.simpleName}"
                continue
            }
            val (result, list) = withContext(Dispatchers.Default) { PlacePackage.verify(text, rootKeys) }
            when {
                list == null -> refused[entry.placeId] = result.name
                list.placeId != entry.placeId -> refused[entry.placeId] = "the list is for ${list.placeId}"
                list.registry.version <= have -> refused[entry.placeId] = "older version v${list.registry.version}; this phone has v$have"
                else -> {
                    saved = saved + (list.placeId to list) // verified: used now even if saving fails
                    try {
                        withContext(Dispatchers.IO) { store.write(list.placeId, text) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Not saved: this run still uses it, and the next sync downloads it again.
                    }
                    updated += list.placeId
                }
            }
        }
        publish()
        PlacesSync(updated, refused, null)
    }

    private fun publish() {
        val t = now()
        _lists.value = saved.filterValues { !it.registry.expiresAt.isBefore(t) }
    }
}
