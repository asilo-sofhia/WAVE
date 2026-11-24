package com.example.wave.verify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64

/** The Kotlin certificate check must agree with backend wave_sign.py verify_cert on every case (CONTRACT §6). */
class AdminCertTest {

    private val vectors = JSONObject(
        requireNotNull(javaClass.classLoader?.getResource("test_vectors.json")).readText(),
    )
    private val roots: Map<String, String> = vectors.getJSONObject("root_public_keys").let { o ->
        o.keys().asSequence().associateWith { o.getString(it) }
    }
    private val cases = vectors.getJSONArray("cert_cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun case(name: String) = cases.first { it.getString("name") == name }

    @Test
    fun vectorCasesMatchPython() {
        assertEquals(7, cases.size)
        for (c in cases) {
            val (result, _) = AdminCert.verify(c.getString("cert"), roots, Instant.parse(c.getString("at")))
            assertEquals(c.getString("name"), c.getString("expect"), result.name)
        }
    }

    @Test
    fun validCertFields() {
        val c = case("valid")
        val (result, cert) = AdminCert.verify(c.getString("cert"), roots, Instant.parse(c.getString("at")))
        assertEquals(CertResult.VALID, result)
        cert!!
        assertEquals("testplace", cert.placeId)
        assertEquals("Test Place", cert.placeName)
        assertEquals("Test Admin", cert.adminName)
        assertEquals("wave-root-test", cert.rootKeyId)
        assertTrue(cert.issuedAt < cert.expiresAt)
    }

    @Test
    fun expiredStillReturnsTheCertificate() {
        val c = case("expired")
        val (result, cert) = AdminCert.verify(c.getString("cert"), roots, Instant.parse(c.getString("at")))
        assertEquals(CertResult.EXPIRED, result)
        assertEquals("testplace", cert?.placeId)
    }

    @Test
    fun unverifiedCertificatesAreNotReturned() {
        for (name in listOf("tampered_place", "unknown_root", "malformed")) {
            val c = case(name)
            assertNull(name, AdminCert.verify(c.getString("cert"), roots, Instant.parse(c.getString("at"))).second)
        }
    }

    /** The valid certificate with one field changed (the signature no longer matters: format is checked first). */
    private fun withField(key: String, value: Any): String {
        val text = case("valid").getString("cert")
        val (body, sig) = text.removePrefix(AdminCert.PREFIX).split(".")
        val o = JSONObject(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8)).put(key, value)
        return AdminCert.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().toByteArray()) + "." + sig
    }

    @Test
    fun junkIsMalformedNeverThrows() {
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }
            .generateKeyPair().public.encoded
        val at = Instant.parse(case("valid").getString("at"))
        val junk = listOf(
            "", "hello", AdminCert.PREFIX, AdminCert.PREFIX + "a.b.c", AdminCert.PREFIX + "!!!.???",
            AdminCert.PREFIX + Base64.getUrlEncoder().encodeToString("[1,2]".toByteArray()) + ".AAAA",
            AdminCert.PREFIX + "x".repeat(100_000),
            withField("admin_public_key", Base64.getUrlEncoder().withoutPadding().encodeToString(p384)),
            withField("admin_name", "a".repeat(65)),
            withField("admin_name", "   "),
            withField("issued_at", "2026-11-24T00:00:00+00:00"),
            withField("expires_at", "2020-01-01T00:00:00Z"),
            withField("cert_id", "XYZ"),
            withField("place_id", "Bad Place"),
            withField("schema", "wave-admin-cert/2"),
            withField("admin_key_alg", "EdDSA"),
            withField("place_name", 42),
        )
        for (text in junk) {
            assertEquals(text.take(40), CertResult.MALFORMED to null, AdminCert.verify(text, roots, at))
        }
    }

    @Test
    fun rootsAreSeparateFromRegistryKeys() {
        assertTrue(WaveKeys.roots.containsKey("wave-root-2026"))
        assertTrue((WaveKeys.roots.keys intersect WaveKeys.trusted.keys).isEmpty())
        assertTrue((WaveKeys.roots.values intersect WaveKeys.trusted.values.toSet()).isEmpty())
    }
}
