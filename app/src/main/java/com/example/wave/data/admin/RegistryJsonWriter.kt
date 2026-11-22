package com.example.wave.data.admin

import com.example.wave.model.Registry
import com.example.wave.model.SecurityMapping
import java.time.format.DateTimeFormatter

/**
 * Writes a registry as CONTRACT §2 JSON, in the contract's field order, 2-space indent, LF line endings.
 * Built by hand (no JSON library) so the exact bytes are predictable and unit-tested: these are the bytes the
 * campus key signs on the laptop.
 */
object RegistryJsonWriter {

    fun write(r: Registry): String = buildString {
        append("{\n")
        append("  \"schema\": ${str(r.schema)},\n")
        append("  \"version\": ${r.version},\n")
        append("  \"key_id\": ${str(r.keyId)},\n")
        append("  \"issued_at\": ${str(DateTimeFormatter.ISO_INSTANT.format(r.issuedAt))},\n")
        append("  \"expires_at\": ${str(DateTimeFormatter.ISO_INSTANT.format(r.expiresAt))},\n")
        append("  \"campus_asns\": ")
        array(r.campusAsns.map { "{ \"asn\": ${it.asn}, \"name\": ${str(it.name)} }" }, indent = "  ")
        append(",\n")
        append("  \"networks\": ")
        array(
            r.networks.map { n ->
                buildString {
                    append("{\n")
                    append("      \"ssid\": ${str(n.ssid)},\n")
                    append("      \"security\": ${str(requireNotNull(SecurityMapping.toContract(n.security)) { "${n.ssid}: no contract security for ${n.security}" })},\n")
                    append("      \"aps\": ")
                    array(
                        n.aps.map { a ->
                            "{ \"bssid\": ${str(a.bssid)}, \"band\": ${str(SecurityMapping.bandToContract(a.band))}, " +
                                "\"channel\": ${a.channel}, \"building\": ${str(a.building)}, " +
                                "\"rssi_min\": ${a.rssiMin}, \"rssi_max\": ${a.rssiMax} }"
                        },
                        indent = "      ",
                    )
                    append("\n    }")
                }
            },
            indent = "  ",
        )
        append(",\n")
        append("  \"blocked\": ")
        array(r.blocked.map { "{ \"bssid\": ${str(it.bssid)}, \"ssid\": ${str(it.ssid)}, \"reason\": ${str(it.reason)} }" }, indent = "  ")
        append("\n}\n")
    }

    /** `[]`, or one item per line, indented one level deeper than [indent]. */
    private fun StringBuilder.array(items: List<String>, indent: String) {
        if (items.isEmpty()) {
            append("[]")
            return
        }
        append("[\n")
        items.forEachIndexed { i, item ->
            append(indent).append("  ").append(item)
            if (i < items.lastIndex) append(",")
            append("\n")
        }
        append(indent).append("]")
    }

    /** A JSON string literal. */
    private fun str(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
