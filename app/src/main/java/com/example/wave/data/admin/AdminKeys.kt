package com.example.wave.data.admin

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

/** The place admin's own key on this phone (P-256, ES256). Only its PUBLIC half ever leaves the phone. */
interface AdminKeys {
    /** The public key as SubjectPublicKeyInfo DER, or null when this phone has no admin key. */
    fun publicKey(): ByteArray?

    /** Makes the key if it doesn't exist yet and returns its public key. Throws [NoScreenLock]. */
    fun create(): ByteArray

    fun delete()
}

/** The phone has no PIN, pattern, password or fingerprint, so a key that needs unlocking can't be made. */
class NoScreenLock : Exception("Set a screen lock first")

/**
 * The key lives in Android Keystore (the phone's secure hardware; StrongBox when the phone has it). Using it
 * to sign (phase 4) needs the owner's fingerprint, face or screen lock within the last [AUTH_SECONDS] seconds.
 */
class KeystoreAdminKeys(private val context: Context) : AdminKeys {

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun publicKey(): ByteArray? = keyStore().getCertificate(ALIAS)?.publicKey?.encoded

    override fun create(): ByteArray {
        publicKey()?.let { return it }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isDeviceSecure) throw NoScreenLock()
        try {
            generate(strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
        return requireNotNull(publicKey()) { "the admin key was not saved" }
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(
                        AUTH_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setUserAuthenticationValidityDurationSeconds(AUTH_SECONDS)
                }
            }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    override fun delete() {
        keyStore().deleteEntry(ALIAS)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "wave_admin_p256"
        const val AUTH_SECONDS = 30
    }
}
