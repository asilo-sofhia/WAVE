package com.example.wave.data.admin

import com.example.wave.model.BlockedAp
import com.example.wave.model.CampusAsn
import com.example.wave.model.Registry
import com.example.wave.model.RegistryAp
import com.example.wave.model.RegistryNetwork
import com.example.wave.model.SecurityMapping
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Where an approved access point came from. Only SURVEYED is made now (everything comes from the admin's radar);
 * FROM_REGISTRY is only read from older saved drafts, which are cleaned up on load.
 */
enum class DraftSource { FROM_REGISTRY, SURVEYED }

/** One approved access point (one radio), in the draft. */
data class DraftAp(
    val bssid: String,
    val ssid: String,
    val security: SecurityType,
    val band: WifiBand,
    val channel: Int,
    val building: String,
    val rssiMin: Int,
    val rssiMax: Int,
    val source: DraftSource,
)

/**
 * The campus admin's list: what was approved and what was blocked on the admin radar. It starts empty and holds
 * only what the admin decided while scanning. Publishing it means saving it as an (unsigned) registry.json, which is
 * signed on the laptop with the campus key; the phone never holds that key.
 */
data class RegistryDraft(
    /** Version of the registry this phone had when the list was started (0 = none). Publishing uses this + 1. */
    val baseVersion: Int,
    val campusAsns: List<CampusAsn>,
    val approved: List<DraftAp>,
    val blocked: List<BlockedAp>,
) {
    /** Adds or replaces these access points (same BSSID = replaced) and takes them off the block list. */
    fun approve(aps: List<DraftAp>): RegistryDraft {
        val ok = aps.filter { SecurityMapping.toContract(it.security) != null } // WEP etc. can't be in the registry
        val bssids = ok.map { it.bssid }.toSet()
        return copy(
            approved = approved.filterNot { it.bssid in bssids } + ok,
            blocked = blocked.filterNot { it.bssid in bssids },
        )
    }

    /** Puts the access point on the block list (replacing an earlier entry) and takes it off the approved list. */
    fun block(bssid: String, ssid: String, reason: String): RegistryDraft = copy(
        approved = approved.filterNot { it.bssid == bssid },
        blocked = blocked.filterNot { it.bssid == bssid } + BlockedAp(bssid, ssid, reason),
    )

    /** Takes the access point off whichever list it is on. */
    fun remove(bssid: String): RegistryDraft = copy(
        approved = approved.filterNot { it.bssid == bssid },
        blocked = blocked.filterNot { it.bssid == bssid },
    )

    /** Takes all of a network's radios off whichever list they are on. */
    fun removeAll(bssids: Collection<String>): RegistryDraft = copy(
        approved = approved.filterNot { it.bssid in bssids },
        blocked = blocked.filterNot { it.bssid in bssids },
    )

    /** Moves all of a network's radios to the block list (or changes their reason if already blocked). */
    fun blockAll(bssids: Collection<String>, ssid: String, reason: String): RegistryDraft =
        bssids.fold(this) { d, bssid -> d.block(bssid, ssid, reason) }

    /** Sets the building of all of a network's approved radios. */
    fun withBuilding(bssids: Collection<String>, building: String): RegistryDraft = copy(
        approved = approved.map { if (it.bssid in bssids) it.copy(building = building.trim()) else it },
    )

    /** Adds the campus provider; the "measure on campus" placeholder (ASN 0) goes away. */
    fun withCampusAsn(asn: CampusAsn): RegistryDraft {
        val real = campusAsns.filter { it.asn != 0 }
        return copy(campusAsns = if (real.any { it.asn == asn.asn }) real else real + asn)
    }

    /** The registry to export (CONTRACT §2): next version, issued now, valid for [VALID_FOR]. */
    fun toRegistry(now: Instant): Registry {
        val issued = now.truncatedTo(ChronoUnit.SECONDS)
        val networks = approved
            .groupBy { it.ssid to it.security }
            .map { (key, aps) ->
                RegistryNetwork(
                    ssid = key.first,
                    security = key.second,
                    aps = aps.sortedBy { it.bssid }.map { RegistryAp(it.bssid, it.band, it.channel, it.building, it.rssiMin, it.rssiMax) },
                )
            }
            .sortedWith(compareBy({ it.ssid }, { SecurityMapping.toContract(it.security) }))
        return Registry(
            schema = SCHEMA,
            version = baseVersion + 1,
            keyId = CAMPUS_KEY_ID,
            issuedAt = issued,
            expiresAt = issued.plus(VALID_FOR),
            campusAsns = campusAsns,
            networks = networks,
            blocked = blocked,
        )
    }

    companion object {
        const val SCHEMA = "wave-registry/1"
        const val CAMPUS_KEY_ID = "wave-2026-a"
        val VALID_FOR: Duration = Duration.ofDays(180)

        /** An empty list; publishing it later gives version [baseVersion] + 1. */
        fun empty(baseVersion: Int): RegistryDraft = RegistryDraft(baseVersion, emptyList(), emptyList(), emptyList())
    }
}

/**
 * The normal signal range to register for an access point, from what the admin saw while walking.
 * Widened on purpose: a too-tight range would warn real users who stand a bit further away or closer.
 */
fun surveyedRange(observedMin: Int, observedMax: Int): IntRange {
    val lo = minOf(observedMin - 10, -85).coerceIn(-100, -10)
    val hi = maxOf(observedMax + 10, -30).coerceIn(-100, -10)
    return lo..hi
}

/** Weakest and strongest signal seen per access point during this admin session. */
class SignalTracker {
    private val ranges = mutableMapOf<String, IntRange>()

    @Synchronized
    fun record(bssid: String, rssi: Int) {
        val r = ranges[bssid]
        ranges[bssid] = if (r == null) rssi..rssi else minOf(r.first, rssi)..maxOf(r.last, rssi)
    }

    @Synchronized
    fun range(bssid: String): IntRange? = ranges[bssid]
}
