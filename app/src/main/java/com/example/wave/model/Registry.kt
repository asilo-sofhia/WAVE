package com.example.wave.model

import java.time.Instant

/*
 * The school's signed list of real access points: docs/CONTRACT.md §2, field for field.
 * Plain Kotlin on purpose: lane 3 parses and verifies the signed JSON in data/registry/ and maps it to these.
 * Nothing produces a Registry yet, so the classifier runs with registry = null (every network UNKNOWN).
 */

data class Registry(
    /** Always "wave-registry/1" for contract v1. */
    val schema: String,
    /** Goes up on every re-sign; the app rejects a lower version than its cache (rollback). */
    val version: Int,
    val keyId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val campusAsns: List<CampusAsn>,
    val networks: List<RegistryNetwork>,
)

data class CampusAsn(val asn: Int, val name: String)

data class RegistryNetwork(
    /** Exact SSID, case-sensitive. */
    val ssid: String,
    val security: SecurityType,
    val aps: List<RegistryAp>,
)

data class RegistryAp(
    /** Lowercase, colon-separated. One entry per radio. */
    val bssid: String,
    val band: WifiBand,
    val channel: Int,
    val building: String,
    /** Normal signal range for this AP in the surveyed area, dBm. */
    val rssiMin: Int,
    val rssiMax: Int,
)
