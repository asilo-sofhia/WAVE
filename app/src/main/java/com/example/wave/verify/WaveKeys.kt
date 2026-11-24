package com.example.wave.verify

/**
 * Public keys the app trusts, built in (CONTRACT §1: the app never downloads keys).
 * Key id -> base64url Ed25519 public key, as printed by `wave_sign.py keygen`.
 */
object WaveKeys {
    /** The real campus key. Its private half lives only on the offline signing laptop. */
    private val production: Map<String, String> = mapOf(
        "wave-2026-a" to "-7S2fp11D9SujoVZt6pCM49tCvp3yuhUdVVzzHlHU4g",
    )

    /**
     * Root keys: the ONLY keys that certify place admins (CONTRACT §6). Never add registry/QR keys here, and never
     * add a root key to [trusted]. The private half of wave-root-2026 lives only on the owner's laptop.
     */
    val roots: Map<String, String> = mapOf(
        "wave-root-2026" to "GIEvVDzjd_HZEGv2cLikRTGqzKxhz5AiGXA2f-ZIQQw",
    )

    /** Production keys + (debug builds only) dev keys from src/debug. Release builds get no dev keys. */
    val trusted: Map<String, String> = production + DEV_PUBLIC_KEYS
}