package com.example.wave.data.registry

import com.example.wave.data.RegistryRepository
import com.example.wave.data.RegistryState
import com.example.wave.data.registryOrNull
import com.example.wave.model.Registry
import com.example.wave.model.RegistryAp
import com.example.wave.model.SecurityType
import com.example.wave.verify.PlaceList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * One registry from the campus list and every place's list, for the classifier, QR check and exit-path check.
 * - The same (ssid, security) from several lists: their access points are joined (the first copy of a BSSID wins).
 * - Blocked: every list's blocks, EXCEPT an access point that some list approves (one place can't turn another
 *   place's access point into a Warning).
 * - campus_asns: all of them.
 * - version: the sum of all versions. It only goes up, so screens notice when any list updates.
 * Pure. Null when there is nothing at all; the campus registry unchanged when there are no places.
 */
fun mergeRegistries(campus: Registry?, places: List<Registry>): Registry? {
    if (places.isEmpty()) return campus
    val all = listOfNotNull(campus) + places
    val networks = LinkedHashMap<Pair<String, SecurityType>, LinkedHashMap<String, RegistryAp>>()
    for (r in all) for (n in r.networks) {
        val aps = networks.getOrPut(n.ssid to n.security) { LinkedHashMap() }
        for (ap in n.aps) aps.putIfAbsent(ap.bssid, ap)
    }
    val approved = networks.values.flatMap { it.keys }.toSet()
    val base = campus ?: Registry(
        schema = places.first().schema,
        version = 0,
        keyId = "places",
        issuedAt = places.minOf { it.issuedAt },
        expiresAt = places.maxOf { it.expiresAt },
        campusAsns = emptyList(),
        networks = emptyList(),
    )
    val template = all.flatMap { it.networks }.associateBy { it.ssid to it.security }
    return base.copy(
        version = all.sumOf { it.version },
        campusAsns = all.flatMap { it.campusAsns }.distinctBy { it.asn },
        networks = networks.map { (key, aps) -> template.getValue(key).copy(aps = aps.values.toList()) },
        blocked = all.flatMap { it.blocked }.distinctBy { it.bssid }.filter { it.bssid !in approved },
    )
}

/**
 * For the exit-path check: each listed access point -> the real campus ASNs (not the 0 placeholder) of the
 * list(s) that hold it. Pure.
 */
fun asnsByBssid(campus: Registry?, places: List<Registry>): Map<String, Set<Int>> {
    val out = HashMap<String, MutableSet<Int>>()
    for (r in listOfNotNull(campus) + places) {
        val asns = r.campusAsns.map { it.asn }.filter { it != 0 }
        for (n in r.networks) for (ap in n.aps) out.getOrPut(ap.bssid) { mutableSetOf() } += asns
    }
    return out
}

/**
 * The registry every screen sees: [campus] (the signed school list, bundled or downloaded) merged with every
 * verified place list. Stale when the campus list is Stale; Missing only when there is nothing at all.
 */
class CombinedRegistryRepository(
    private val campus: RegistryRepository,
    places: StateFlow<Map<String, PlaceList>>,
    private val refreshPlaces: suspend () -> Unit,
    scope: CoroutineScope,
) : RegistryRepository {

    @Volatile private var asns: Map<String, Set<Int>> = emptyMap()

    /** The real ASNs of the list(s) holding [bssid] (for the exit-path check). */
    fun asnsFor(bssid: String): Set<Int> = asns[bssid].orEmpty()

    override val state: StateFlow<RegistryState> = combine(campus.state, places, ::combined)
        .stateIn(scope, SharingStarted.Eagerly, combined(campus.state.value, places.value))

    override suspend fun refresh(): RegistryState {
        refreshPlaces()
        campus.refresh()
        return state.value
    }

    private fun combined(c: RegistryState, p: Map<String, PlaceList>): RegistryState {
        val placeRegs = p.toSortedMap().values.map { it.registry }
        asns = asnsByBssid(c.registryOrNull, placeRegs)
        val merged = mergeRegistries(c.registryOrNull, placeRegs) ?: return c
        return if (c is RegistryState.Stale) RegistryState.Stale(merged) else RegistryState.Ready(merged)
    }
}
