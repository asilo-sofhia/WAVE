package com.example.wave.data.registry

import com.example.wave.model.CampusAsn
import com.example.wave.model.Registry
import com.example.wave.model.RegistryAp
import com.example.wave.model.RegistryNetwork
import com.example.wave.model.SecurityMapping
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Reads registry.json and registry.json.sig (docs/CONTRACT.md §2) with Android's built-in org.json.
 * Only call [parse] AFTER the raw bytes passed the signature check (Rule #1).
 * Throws IllegalArgumentException (or JSONException) with a readable message when the file breaks the contract.
 */
object RegistryJson {
    const val SCHEMA = "wave-registry/1"
    private val BSSID = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    data class SignatureFile(val keyId: String, val alg: String, val signature: String)

    fun parseSignature(bytes: ByteArray): SignatureFile {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        return SignatureFile(o.getString("key_id"), o.getString("alg"), o.getString("signature"))
    }

    fun parse(bytes: ByteArray): Registry {
        val o = JSONObject(String(bytes, Charsets.UTF_8))
        val schema = o.getString("schema")
        require(schema == SCHEMA) { "schema is '$schema', expected '$SCHEMA'" }
        val version = o.getInt("version")
        require(version >= 1) { "version must be 1 or more" }
        return Registry(
            schema = schema,
            version = version,
            keyId = o.getString("key_id"),
            issuedAt = Instant.parse(o.getString("issued_at")),
            expiresAt = Instant.parse(o.getString("expires_at")),
            campusAsns = o.getJSONArray("campus_asns").objects().map { CampusAsn(it.getInt("asn"), it.getString("name")) },
            networks = o.getJSONArray("networks").objects().map(::network),
        )
    }

    private fun network(n: JSONObject): RegistryNetwork {
        val ssid = n.getString("ssid")
        val security = n.getString("security")
        return RegistryNetwork(
            ssid = ssid,
            security = requireNotNull(SecurityMapping.fromContract(security)) { "$ssid: unknown security '$security'" },
            aps = n.getJSONArray("aps").objects().map { ap(ssid, it) },
        )
    }

    private fun ap(ssid: String, a: JSONObject): RegistryAp {
        val bssid = a.getString("bssid")
        require(BSSID.matches(bssid)) { "$ssid: bssid '$bssid' must be lowercase xx:xx:xx:xx:xx:xx" }
        val band = a.getString("band")
        val min = a.getInt("rssi_min")
        val max = a.getInt("rssi_max")
        require(min <= max) { "$ssid $bssid: rssi_min is above rssi_max" }
        return RegistryAp(
            bssid = bssid,
            band = requireNotNull(SecurityMapping.bandFromContract(band)) { "$ssid $bssid: unknown band '$band'" },
            channel = a.getInt("channel"),
            building = a.getString("building"),
            rssiMin = min,
            rssiMax = max,
        )
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
}
