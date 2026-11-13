package com.example.wave.verify

/**
 * DEBUG BUILDS ONLY. Throwaway key for at-home testing, made with
 * `wave_sign.py keygen --key-id wave-dev --no-passphrase` (private key stays in backend/keys, git-ignored).
 * Teammates' debug builds trust it too, but only registries signed on that laptop will pass.
 */
internal val DEV_PUBLIC_KEYS: Map<String, String> = mapOf(
    "wave-dev" to "yTugdXwUU27W7qaEKOoLKgqpazgudgiCAo91EXxee1o",
)
