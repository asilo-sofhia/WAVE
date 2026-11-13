package com.example.wave.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The Kotlin verifier must agree with backend/signing/wave_sign.py on every registry case in
 * backend/test-vectors/test_vectors.json (copied byte-for-byte to src/test/resources).
 */
class RegistrySignatureTest {

    private val vectors: String =
        requireNotNull(javaClass.classLoader?.getResource("test_vectors.json")) { "test_vectors.json missing" }.readText()

    /** The vector file is simple, flat JSON; a regex keeps the test free of a JSON library. */
    private fun field(obj: String, name: String): String =
        Regex("\"$name\"\\s*:\\s*(\"([^\"]*)\"|true|false)").find(obj)!!.groupValues.let { it[2].ifEmpty { it[1] } }

    private val trusted: Map<String, String> =
        mapOf("wave-test" to Regex("\"wave-test\"\\s*:\\s*\"([^\"]+)\"").find(vectors)!!.groupValues[1])

    private val cases: List<String> =
        Regex("\\{[^{}]*\"registry_b64url\"[^{}]*\\}").findAll(vectors).map { it.value }.toList()

    @Test
    fun agreesWithEveryBackendRegistryVector() {
        assertTrue("expected the 6 registry cases", cases.size >= 6)
        for (case in cases) {
            val name = field(case, "name")
            val bytes = Base64.getUrlDecoder().decode(field(case, "registry_b64url"))
            val problem = RegistrySignature.check(bytes, field(case, "key_id"), field(case, "signature_b64url"), trusted)
            assertEquals(name, field(case, "expect_valid").toBoolean(), problem == null)
        }
    }

    @Test
    fun unknownKeyIsReportedAsSuch() {
        val valid = cases.first { field(it, "name") == "valid" }
        val bytes = Base64.getUrlDecoder().decode(field(valid, "registry_b64url"))
        assertEquals(
            RegistrySignature.Problem.UNKNOWN_KEY,
            RegistrySignature.check(bytes, "wave-test", field(valid, "signature_b64url"), trustedKeys = emptyMap()),
        )
        assertNull(RegistrySignature.check(bytes, "wave-test", field(valid, "signature_b64url"), trusted))
    }

    @Test
    fun garbageSignatureIsBadNotACrash() {
        assertEquals(
            RegistrySignature.Problem.BAD_SIGNATURE,
            RegistrySignature.check("{}".toByteArray(), "wave-test", "not base64 !!", trusted),
        )
    }

    @Test
    fun releaseOnlyTrustsBuiltInKeys() {
        // Debug unit tests run with src/debug: the home dev key is present there.
        assertTrue("wave-dev" in WaveKeys.trusted)
        assertTrue("wave-test" !in WaveKeys.trusted) // the vectors' throwaway key is never trusted by the app
    }
}
