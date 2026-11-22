package com.example.wave.model

/** Registry verdict for one network. Names are a contract with the backend: keep stable. */
enum class Verdict { VERIFIED, UNKNOWN, WARNING }

/** Why a network got [Verdict.WARNING]. Names are a contract with the backend: keep stable. */
enum class WarningReason(val title: String, val explanation: String) {
    UNREGISTERED_BSSID(
        "Not on the official list",
        "This access point isn't in the school's registry of real access points.",
    ),
    LOCALLY_ADMINISTERED(
        "Made-up hardware address",
        "Its hardware address looks invented, which is common for phone hotspots and fake routers.",
    ),
    SECURITY_MISMATCH(
        "Different security",
        "Its security type doesn't match the real network with this name.",
    ),
    RSSI_OUT_OF_RANGE(
        "Unusual signal strength",
        "Its signal is much stronger or weaker than the real network should be here.",
    ),
    EXIT_ASN_MISMATCH(
        "Unexpected internet provider",
        "Your traffic leaves through a provider the real network doesn't use.",
    ),
}
