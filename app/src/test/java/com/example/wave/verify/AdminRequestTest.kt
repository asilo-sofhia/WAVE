package com.example.wave.verify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** The request text the phone shares must be what backend wave_sign.py parse_admin_request reads (CONTRACT §6). */
class AdminRequestTest {

    private val spki = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().public.encoded

    @Test
    fun makeHasExactlyTheFourFields() {
        val text = AdminRequest.make("  Ana Cruz ", "batstateu", spki)
        assertTrue(text.startsWith(AdminRequest.PREFIX))
        val body = text.removePrefix(AdminRequest.PREFIX)
        assertTrue("no padding", !body.contains('='))
        val o = JSONObject(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8))
        assertEquals(setOf("admin_name", "place_id", "admin_key_alg", "admin_public_key"), o.keys().asSequence().toSet())
        assertEquals("Ana Cruz", o.getString("admin_name"))
        assertEquals("batstateu", o.getString("place_id"))
        assertEquals("ES256", o.getString("admin_key_alg"))
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(spki), o.getString("admin_public_key"))
    }

    @Test
    fun problems() {
        for (name in listOf("", "   ", "a".repeat(65))) assertNotNull(name, AdminRequest.problem(name, "batstateu"))
        for (place in listOf("BatStateU", "-x", "a".repeat(33), "", "bat state")) {
            assertNotNull(place, AdminRequest.problem("Ana", place))
        }
        assertNull(AdminRequest.problem("a".repeat(64), "batstateu"))
        assertNull(AdminRequest.problem("Ana Cruz", "sm-mall-2"))
        // Length is in characters (code points), like Python's len(): 64 emoji are fine.
        assertNull(AdminRequest.problem("😀".repeat(64), "batstateu"))
    }

    @Test
    fun makeRefusesBadInput() {
        assertThrows(IllegalArgumentException::class.java) { AdminRequest.make("", "batstateu", spki) }
        assertThrows(IllegalArgumentException::class.java) { AdminRequest.make("Ana", "Bad", spki) }
    }
}
