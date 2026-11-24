package com.example.wave.data.admin

import com.example.wave.verify.AdminCert
import com.example.wave.verify.AdminRequest
import com.example.wave.verify.CertResult
import com.google.crypto.tink.subtle.Ed25519Sign
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** A phone key that lives in memory (the real one is in Android Keystore). */
class FakeAdminKeys : AdminKeys {
    var spki: ByteArray? = null
    var created = 0
    override fun publicKey(): ByteArray? = spki
    override fun create(): ByteArray = spki ?: newP256().also { spki = it; created++ }
    override fun delete() { spki = null }
}

fun newP256(): ByteArray =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded

private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** What `wave_sign.py certify-admin` makes: the root signs "WAVE-CERT-v1\n" + the certificate bytes. */
fun certify(
    spki: ByteArray,
    root: Ed25519Sign.KeyPair,
    issued: Instant,
    days: Long = 30,
    place: String = "wave-test",
    rootId: String = "wave-root-test",
): String {
    val body = JSONObject()
        .put("schema", "wave-admin-cert/1").put("cert_id", "0123456789abcdef")
        .put("place_id", place).put("place_name", "WAVE Test").put("admin_name", "Test Admin")
        .put("admin_key_alg", "ES256").put("admin_public_key", b64(spki))
        .put("issued_at", issued.toString()).put("expires_at", issued.plus(Duration.ofDays(days)).toString())
        .put("root_key_id", rootId)
        .toString().toByteArray(Charsets.UTF_8)
    return AdminCert.PREFIX + b64(body) + "." + b64(Ed25519Sign(root.privateKey).sign(AdminCert.DOMAIN + body))
}

class AdminEnrollmentTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day1: Instant = Instant.parse("2026-11-24T00:00:00Z")
    private val root = Ed25519Sign.KeyPair.newKeyPair()
    private val roots = mapOf("wave-root-test" to b64(root.publicKey))
    private var now = day1.plus(Duration.ofHours(1))
    private val keys = FakeAdminKeys()
    private val file: File get() = File(tmp.root, "admin/enrolment.json")

    private fun store() = AdminEnrollment(file, keys, roots) { now }

    @Test
    fun requestCreatesKeyOnceAndSaves() {
        val s = store()
        val first = s.request("Test Admin", "wave-test")
        val second = s.request("Test Admin", "wave-test")
        assertEquals(1, keys.created)
        assertEquals(first, second)
        assertTrue(first.startsWith(AdminRequest.PREFIX))
        assertEquals(AdminStatus.Requested("Test Admin", "wave-test", first), s.status.value)
        assertEquals(AdminStatus.Requested("Test Admin", "wave-test", first), store().status.value)
    }

    @Test
    fun installValidCert() {
        val s = store()
        s.request("Test Admin", "wave-test")
        assertEquals(InstallResult.INSTALLED, s.install(certify(keys.spki!!, root, day1)))
        val enrolled = s.status.value as AdminStatus.Enrolled
        assertEquals(CertResult.VALID, enrolled.validity)
        assertEquals("WAVE Test", enrolled.cert.placeName)
        assertEquals(enrolled, store().status.value)
    }

    @Test
    fun installCertForOtherKeyIsRefused() {
        val s = store()
        s.request("Test Admin", "wave-test")
        val before = s.status.value
        assertEquals(InstallResult.NOT_THIS_PHONE, s.install(certify(newP256(), root, day1)))
        assertEquals(before, s.status.value)
        assertEquals(before, store().status.value)
    }

    @Test
    fun installExpiredOrUnknownRoot() {
        val s = store()
        s.request("Test Admin", "wave-test")
        val before = s.status.value
        val expired = certify(keys.spki!!, root, day1.minus(Duration.ofDays(60)), days = 30)
        assertEquals(InstallResult.EXPIRED, s.install(expired))
        val stranger = Ed25519Sign.KeyPair.newKeyPair()
        assertEquals(InstallResult.UNKNOWN_KEY, s.install(certify(keys.spki!!, stranger, day1, rootId = "someone")))
        assertEquals(InstallResult.BAD_SIGNATURE, s.install(certify(keys.spki!!, stranger, day1)))
        assertEquals(InstallResult.NOT_YET_VALID, s.install(certify(keys.spki!!, root, day1.plus(Duration.ofDays(5)))))
        assertEquals(InstallResult.MALFORMED, s.install("nope"))
        assertEquals(before, s.status.value)
    }

    @Test
    fun installWithoutKey() {
        assertEquals(InstallResult.NO_KEY, store().install(certify(newP256(), root, day1)))
    }

    @Test
    fun enrolledCertExpiresLater() {
        val s = store()
        s.request("Test Admin", "wave-test")
        s.install(certify(keys.spki!!, root, day1, days = 30))
        now = day1.plus(Duration.ofDays(31))
        s.refresh()
        assertEquals(CertResult.EXPIRED, (s.status.value as AdminStatus.Enrolled).validity)
        assertEquals(CertResult.EXPIRED, (store().status.value as AdminStatus.Enrolled).validity)
    }

    @Test
    fun corruptFileLoadsAsNotStarted() {
        for (junk in listOf("{", "", "[]", "{\"admin_name\": 5}", "\u0000\u0001")) {
            file.parentFile!!.mkdirs()
            file.writeText(junk)
            assertEquals(junk, AdminStatus.NotStarted, store().status.value)
        }
    }

    @Test
    fun removeClearsEverything() {
        val s = store()
        s.request("Test Admin", "wave-test")
        s.install(certify(keys.spki!!, root, day1))
        s.remove()
        assertNull(keys.publicKey())
        assertFalse(file.exists())
        assertEquals(AdminStatus.NotStarted, s.status.value)
        assertEquals(AdminStatus.NotStarted, store().status.value)
    }
}
