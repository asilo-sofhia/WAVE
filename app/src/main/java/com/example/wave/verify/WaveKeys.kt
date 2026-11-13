package com.example.wave.verify

/**
 * Public keys the app trusts, built in (CONTRACT §1: the app never downloads keys).
 * Key id -> base64url Ed25519 public key, as printed by `wave_sign.py keygen`.
 */
object WaveKeys {
    /** The real campus key goes here once it's made on the offline signing laptop. */
    private val production: Map<String, String> = emptyMap()

    /** Production keys + (debug builds only) dev keys from src/debug. Release builds get no dev keys. */
    val trusted: Map<String, String> = production + DEV_PUBLIC_KEYS
}
