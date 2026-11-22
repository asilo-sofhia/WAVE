package com.example.wave.data.registry

import android.content.Context
import android.util.Log
import java.io.FileNotFoundException

private const val TAG = "WaveRegistry"
private const val FILE = "registry/registry.json"

/**
 * The signed registry shipped inside the app (assets/registry/registry.json + .sig), or null if there is none.
 * Not trusted yet: the caller verifies it. Debug builds get the home test registry from backend/registry/
 * (copied by the copyHomeRegistry Gradle task, git-ignored).
 */
fun readBundledRegistry(context: Context): SignedRegistry? {
    val assets = context.applicationContext.assets
    fun read(path: String): ByteArray? =
        try { assets.open(path).use { it.readBytes() } } catch (_: FileNotFoundException) { null }

    val bytes = read(FILE) ?: return null
    val sigBytes = read("$FILE.sig") ?: run { Log.w(TAG, "Bundled registry has no signature file"); return null }
    val sig = try {
        RegistryJson.parseSignature(sigBytes)
    } catch (e: Exception) {
        Log.w(TAG, "Bundled registry signature file is unreadable", e)
        return null
    }
    if (sig.alg != "Ed25519") {
        Log.w(TAG, "Bundled registry uses unsupported signature algorithm ${sig.alg}")
        return null
    }
    return SignedRegistry(bytes, sig.keyId, sig.signature)
}
