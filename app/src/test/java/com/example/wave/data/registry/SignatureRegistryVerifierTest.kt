package com.example.wave.data.registry

import com.example.wave.data.RegistryState
import com.example.wave.model.Registry
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

/** The signature check that used to live in BundledRegistryRepository, now shared by every registry source. */
class SignatureRegistryVerifierTest {

    private val vectors: String =
        requireNotNull(javaClass.classLoader?.getResource("test_vectors.json")) { "test_vectors.json missing" }.readText()

    private fun field(obj: String, name: String): String =
        Regex("\"$name\"\\s*:\\s*\"([^\"]*)\"").find(obj)!!.groupValues[1]

    private val trusted = mapOf("wave-test" to Regex("\"wave-test\"\\s*:\\s*\"([^\"]+)\"").find(vectors)!!.groupValues[1])

    private val valid: SignedRegistry = Regex("\\{[^{}]*\"registry_b64url\"[^{}]*\\}").findAll(vectors).map { it.value }
        .first { field(it, "name") == "valid" }
        .let { SignedRegistry(Base64.getUrlDecoder().decode(field(it, "registry_b64url")), field(it, "key_id"), field(it, "signature_b64url")) }

    private val expiresAt = Instant.parse("2027-01-01T00:00:00Z")

    private fun registry(keyId: String = "wave-test") =
        Registry("wave-registry/1", 1, keyId, Instant.parse("2026-11-01T00:00:00Z"), expiresAt, emptyList(), emptyList())

    private fun verifier(
        now: Instant = Instant.parse("2026-11-22T00:00:00Z"),
        keys: Map<String, String> = trusted,
        parse: (ByteArray) -> Registry = { registry() },
    ) = SignatureRegistryVerifier(now = { now }, trustedKeys = keys, parse = parse)

    @Test
    fun genuineIsReady() {
        assertTrue(verifier().verify(valid) is RegistryState.Ready)
    }

    @Test
    fun expiredIsStale() {
        assertTrue(verifier(now = expiresAt.plusSeconds(1)).verify(valid) is RegistryState.Stale)
    }

    @Test
    fun tamperedIsMissing() {
        val bytes = valid.bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val state = verifier().verify(SignedRegistry(bytes, valid.keyId, valid.signatureB64Url))
        assertProblem(state, "signature")
    }

    @Test
    fun unknownKeyIsMissing() {
        assertProblem(verifier(keys = emptyMap()).verify(valid), "unknown key")
    }

    @Test
    fun keyIdMismatchIsMissing() {
        assertProblem(verifier(parse = { registry(keyId = "other") }).verify(valid), "key_id")
    }

    @Test
    fun parseErrorIsMissing() {
        assertProblem(verifier(parse = { throw IllegalArgumentException("no schema") }).verify(valid), "contract")
    }

    private fun assertProblem(state: RegistryState, contains: String) {
        assertTrue("expected Missing, got $state", state is RegistryState.Missing)
        val problem = (state as RegistryState.Missing).problem.orEmpty()
        assertTrue("'$problem' should mention '$contains'", problem.contains(contains))
    }
}
