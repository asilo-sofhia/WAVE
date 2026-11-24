package com.example.wave.verify

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone's place-package check must agree with backend wave_sign.py verify_package on every case (CONTRACT §7). */
class PlacePackageTest {

    private val vectors = JSONObject(requireNotNull(javaClass.classLoader?.getResource("test_vectors.json")).readText())
    private val roots: Map<String, String> = vectors.getJSONObject("root_public_keys").let { o ->
        o.keys().asSequence().associateWith { o.getString(it) }
    }
    private val cases = vectors.getJSONArray("package_cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun pkg(name: String) = cases.first { it.getString("name") == name }.getString("package_json")

    @Test
    fun vectorCasesMatchPython() {
        assertEquals(7, cases.size)
        for (c in cases) {
            assertEquals(c.getString("name"), c.getString("expect"), PlacePackage.verify(c.getString("package_json"), roots).first.name)
        }
    }

    @Test
    fun validCaseFields() {
        val (result, list) = PlacePackage.verify(pkg("valid"), roots)
        assertEquals(PackageResult.VALID, result)
        list!!
        assertEquals("testplace", list.placeId)
        assertEquals("Test Place", list.placeName)
        assertEquals(2, list.registry.version)
        assertEquals(listOf("TEST-PLACE-WIFI"), list.registry.networks.map { it.ssid })
        assertEquals(pkg("valid"), list.packageJson)
    }

    @Test
    fun onlyValidReturnsTheList() {
        for (c in cases.filter { it.getString("expect") != "VALID" }) {
            assertNull(c.getString("name"), PlacePackage.verify(c.getString("package_json"), roots).second)
        }
    }

    @Test
    fun junkIsMalformed() {
        val valid = JSONObject(pkg("valid"))
        val junk = listOf(
            "", "{}", "[]", "nope",
            JSONObject(pkg("valid")).put("cert", 5).toString(),
            JSONObject(pkg("valid")).put("registry_b64url", "!!!").toString(),
            JSONObject(pkg("valid")).put("registry_b64url", "e30").toString(), // "{}"
            JSONObject(pkg("valid")).apply { remove("signature_b64url") }.toString(),
        )
        for (text in junk) assertEquals(text.take(30), PackageResult.MALFORMED to null, PlacePackage.verify(text, roots))
        assertEquals(PackageResult.VALID, PlacePackage.verify(valid.toString(), roots).first)
    }

    @Test
    fun registryKeysCannotCertify() {
        assertEquals(PackageResult.UNKNOWN_KEY, PlacePackage.verify(pkg("valid"), WaveKeys.trusted).first)
    }
}
