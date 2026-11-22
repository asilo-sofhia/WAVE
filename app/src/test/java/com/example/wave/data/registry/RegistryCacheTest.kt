package com.example.wave.data.registry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RegistryCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun signed(text: String) = SignedRegistry(text.toByteArray(), "wave-dev", "sig-of-$text")

    private fun cache() = RegistryCache(File(tmp.root, "registry"))

    @Test
    fun emptyDirReadsNull() {
        assertNull(cache().read())
    }

    @Test
    fun writeThenReadRoundTrips() {
        val file = signed("{\"version\":1}\r\n")
        cache().write(file)
        assertEquals(file, cache().read())
    }

    @Test
    fun overwriteReplaces() {
        val c = cache()
        c.write(signed("v1"))
        c.write(signed("v2"))
        assertEquals(signed("v2"), c.read())
    }

    @Test
    fun missingSignatureFileReadsNull() {
        val c = cache()
        c.write(signed("v1"))
        File(tmp.root, "registry/registry.sig").delete()
        assertNull(c.read())
    }

    @Test
    fun noTmpFilesLeftAfterWrite() {
        cache().write(signed("v1"))
        val names = File(tmp.root, "registry").list().orEmpty().toList()
        assertTrue("leftovers: $names", names.none { it.endsWith(".tmp") })
    }
}
