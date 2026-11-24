package com.example.wave.data.admin

import com.example.wave.verify.AdminCert
import com.example.wave.verify.AdminCertificate
import com.example.wave.verify.AdminRequest
import com.example.wave.verify.CertResult
import com.example.wave.verify.WaveKeys
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.Base64

/** Where this phone is in becoming a place admin. */
sealed interface AdminStatus {
    data object NotStarted : AdminStatus

    /** The request was made; waiting for the owner's ID card. */
    data class Requested(val adminName: String, val placeId: String, val request: String) : AdminStatus

    /** The ID card is installed. [validity] is VALID, NOT_YET_VALID or EXPIRED (checked against today's date). */
    data class Enrolled(val cert: AdminCertificate, val validity: CertResult) : AdminStatus
}

enum class InstallResult { INSTALLED, MALFORMED, UNKNOWN_KEY, BAD_SIGNATURE, NOT_YET_VALID, EXPIRED, NOT_THIS_PHONE, NO_KEY }

/**
 * The admin enrolment saved on this phone (CONTRACT §6): the request it shared and the ID card it got back.
 * An ID card is installed only if the WAVE root signed it, it is valid now, and it names THIS phone's key.
 * Saved as files/admin/enrolment.json; a missing or damaged file just means "not started".
 */
class AdminEnrollment(
    private val file: File,
    private val keys: AdminKeys,
    private val rootKeys: Map<String, String> = WaveKeys.roots,
    private val clock: () -> Instant = Instant::now,
) {
    private data class Saved(val adminName: String, val placeId: String, val request: String, val cert: String?)

    private val lock = Any()
    private var saved: Saved? = load()
    private val _status = MutableStateFlow(statusOf(saved))
    val status: StateFlow<AdminStatus> = _status.asStateFlow()

    /** Makes this phone's key (once) and the request text to share. Throws IllegalArgumentException or [NoScreenLock]. */
    fun request(adminName: String, placeId: String): String = synchronized(lock) {
        AdminRequest.problem(adminName, placeId)?.let { throw IllegalArgumentException(it) }
        val text = AdminRequest.make(adminName, placeId, keys.create())
        update(Saved(adminName.trim(), placeId, text, cert = null))
        text
    }

    fun install(certText: String): InstallResult = synchronized(lock) {
        val current = saved
        val myKey = keys.publicKey()
        if (current == null || myKey == null) return InstallResult.NO_KEY
        val (result, cert) = AdminCert.verify(certText, rootKeys, clock())
        if (cert != null && !cert.adminPublicKey.decodedEquals(myKey)) return InstallResult.NOT_THIS_PHONE
        when (result) {
            CertResult.VALID -> {
                update(current.copy(cert = certText.trim()))
                InstallResult.INSTALLED
            }
            CertResult.MALFORMED -> InstallResult.MALFORMED
            CertResult.UNKNOWN_KEY -> InstallResult.UNKNOWN_KEY
            CertResult.BAD_SIGNATURE -> InstallResult.BAD_SIGNATURE
            CertResult.NOT_YET_VALID -> InstallResult.NOT_YET_VALID
            CertResult.EXPIRED -> InstallResult.EXPIRED
        }
    }

    /** Re-checks the ID card's dates (an ID card can expire while the app is installed). */
    fun refresh() = synchronized(lock) { _status.value = statusOf(saved) }

    /** Deletes this phone's admin key and the saved enrolment. */
    fun remove() = synchronized(lock) {
        keys.delete()
        file.delete()
        saved = null
        _status.value = AdminStatus.NotStarted
    }

    private fun statusOf(s: Saved?): AdminStatus {
        if (s == null) return AdminStatus.NotStarted
        val (result, cert) = s.cert?.let { AdminCert.verify(it, rootKeys, clock()) } ?: (CertResult.MALFORMED to null)
        return if (cert != null) AdminStatus.Enrolled(cert, result) else AdminStatus.Requested(s.adminName, s.placeId, s.request)
    }

    private fun update(s: Saved) {
        write(s)
        saved = s
        _status.value = statusOf(s)
    }

    private fun load(): Saved? = try {
        val o = JSONObject(file.readText())
        Saved(
            adminName = o.get("admin_name") as String,
            placeId = o.get("place_id") as String,
            request = (o.get("request") as String).also { require(it.startsWith(AdminRequest.PREFIX)) },
            cert = o.opt("cert") as? String,
        )
    } catch (_: Exception) {
        null // missing, cut short or changed by hand: start over
    }

    private fun write(s: Saved) {
        val json = JSONObject()
            .put("admin_name", s.adminName).put("place_id", s.placeId).put("request", s.request)
            .apply { if (s.cert != null) put("cert", s.cert) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("Couldn't save the admin enrolment")
        }
    }

    private fun String.decodedEquals(spki: ByteArray): Boolean =
        try { Base64.getUrlDecoder().decode(this).contentEquals(spki) } catch (_: IllegalArgumentException) { false }
}
