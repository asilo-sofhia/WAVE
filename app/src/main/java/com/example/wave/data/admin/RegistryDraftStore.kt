package com.example.wave.data.admin

import com.example.wave.model.BlockedAp
import com.example.wave.model.CampusAsn
import com.example.wave.model.Registry
import com.example.wave.model.SecurityMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * The admin's draft, saved on this phone (app storage, never shared) so it survives restarts.
 * The first time, it starts from the registry the phone currently trusts.
 */
class RegistryDraftStore(private val file: File) {

    private val _draft = MutableStateFlow<RegistryDraft?>(null)
    /** Null until [load] has run. */
    val draft: StateFlow<RegistryDraft?> = _draft.asStateFlow()

    private val lock = Mutex()

    /** The saved draft, or (first time) a new one that starts from [current]. */
    suspend fun load(current: Registry?): RegistryDraft = lock.withLock {
        _draft.value?.let { return@withLock it }
        val loaded = withContext(Dispatchers.IO) { if (file.exists()) decode(file.readText()) else null }
            ?: RegistryDraft.from(current).also { save(it) }
        _draft.value = loaded
        loaded
    }

    /** Changes the draft and saves it. */
    suspend fun update(transform: (RegistryDraft) -> RegistryDraft) = lock.withLock {
        val current = requireNotNull(_draft.value) { "load() first" }
        val next = transform(current)
        save(next)
        _draft.value = next
    }

    /** Throws the draft away and starts again from [current] (e.g. after a newer signed registry arrived). */
    suspend fun reset(current: Registry?) = lock.withLock {
        val fresh = RegistryDraft.from(current)
        save(fresh)
        _draft.value = fresh
    }

    private suspend fun save(d: RegistryDraft) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(encode(d))
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Couldn't save the registry draft")
        }
    }

    internal companion object {
        fun encode(d: RegistryDraft): String = JSONObject()
            .put("base_version", d.baseVersion)
            .put("campus_asns", JSONArray(d.campusAsns.map { JSONObject().put("asn", it.asn).put("name", it.name) }))
            .put(
                "approved",
                JSONArray(
                    d.approved.map {
                        JSONObject()
                            .put("bssid", it.bssid).put("ssid", it.ssid)
                            .put("security", SecurityMapping.toContract(it.security))
                            .put("band", SecurityMapping.bandToContract(it.band)).put("channel", it.channel)
                            .put("building", it.building).put("rssi_min", it.rssiMin).put("rssi_max", it.rssiMax)
                            .put("source", it.source.name)
                    },
                ),
            )
            .put("blocked", JSONArray(d.blocked.map { JSONObject().put("bssid", it.bssid).put("ssid", it.ssid).put("reason", it.reason) }))
            .toString(2)

        /** Null when the file is damaged: the caller starts a fresh draft instead of crashing. */
        fun decode(text: String): RegistryDraft? = try {
            val o = JSONObject(text)
            RegistryDraft(
                baseVersion = o.getInt("base_version"),
                campusAsns = o.getJSONArray("campus_asns").objects().map { CampusAsn(it.getInt("asn"), it.getString("name")) },
                approved = o.getJSONArray("approved").objects().map {
                    DraftAp(
                        bssid = it.getString("bssid"),
                        ssid = it.getString("ssid"),
                        security = requireNotNull(SecurityMapping.fromContract(it.getString("security"))),
                        band = requireNotNull(SecurityMapping.bandFromContract(it.getString("band"))),
                        channel = it.getInt("channel"),
                        building = it.getString("building"),
                        rssiMin = it.getInt("rssi_min"),
                        rssiMax = it.getInt("rssi_max"),
                        source = DraftSource.valueOf(it.getString("source")),
                    )
                },
                blocked = o.getJSONArray("blocked").objects().map { BlockedAp(it.getString("bssid"), it.getString("ssid"), it.getString("reason")) },
            )
        } catch (e: Exception) {
            null
        }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    }
}
