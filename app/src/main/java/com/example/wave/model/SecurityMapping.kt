package com.example.wave.model

/** The registry's names (docs/CONTRACT.md §2) <-> the app's enums. One mapping, used everywhere. */
object SecurityMapping {
    private val contract = mapOf(
        "OPEN" to SecurityType.OPEN,
        "WPA2-PSK" to SecurityType.WPA2_PERSONAL,
        "WPA3-SAE" to SecurityType.WPA3_PERSONAL,
        "WPA2-WPA3" to SecurityType.WPA2_WPA3_PERSONAL,
    )

    /** "WPA2-PSK" -> WPA2_PERSONAL. Anything not in the contract -> null (the registry is then rejected). */
    fun fromContract(value: String): SecurityType? = contract[value]

    /** Inverse. WEP and UNSUPPORTED have no contract value -> null. */
    fun toContract(type: SecurityType): String? = contract.entries.firstOrNull { it.value == type }?.key

    /** "2.4GHz" / "5GHz" / "6GHz" -> WifiBand. */
    fun bandFromContract(value: String): WifiBand? = when (value) {
        "2.4GHz" -> WifiBand.GHZ_2_4
        "5GHz" -> WifiBand.GHZ_5
        "6GHz" -> WifiBand.GHZ_6
        else -> null
    }
}
