package com.example.wave.data.registry

import com.example.wave.data.admin.PlaceServerJson
import com.example.wave.data.admin.PlaceSummary
import com.example.wave.data.admin.RegistryJsonWriter
import com.example.wave.data.admin.certify
import com.example.wave.data.admin.newPair
import com.example.wave.model.CampusAsn
import com.example.wave.model.Registry
import com.example.wave.model.RegistryAp
import com.example.wave.model.RegistryNetwork
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import com.example.wave.verify.RegistrySignature
import com.google.crypto.tink.subtle.Ed25519Sign
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.Signature
import java.time.Duration
import java.time.Instant
import java.util.Base64

private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** A real place package: ID card from [root], list signed by [admin] (what an admin phone uploads). */
fun placePackage(
    place: String,
    version: Int,
    root: Ed25519Sign.KeyPair,
    admin: KeyPair,
    issued: Instant,
    listDays: Long = 20,
    cardDays: Long = 30,
): String {
    val card = certify(admin.public.encoded, root, issued.minus(Duration.ofHours(1)), days = cardDays, place = place)
    val reg = Registry(
        schema = "wave-registry/1", version = version, keyId = "0123456789abcdef",
        issuedAt = issued, expiresAt = issued.plus(Duration.ofDays(listDays)),
        campusAsns = listOf(CampusAsn(64500, "Example ISP")),
        networks = listOf(
            RegistryNetwork("NET-$place", SecurityType.WPA2_PERSONAL, listOf(RegistryAp("00:00:5e:00:53:10", WifiBand.GHZ_5, 36, "Main", -85, -30))),
        ),
        blocked = emptyList(),
    )
    val bytes = RegistryJsonWriter.write(reg, place).toByteArray(Charsets.UTF_8)
    val sig = Signature.getInstance("SHA256withECDSA").run { initSign(admin.private); update(RegistrySignature.DOMAIN + bytes); sign() }
    return JSONObject().put("place_id", place).put("registry_b64url", b64(bytes)).put("signature_b64url", b64(sig))
        .put("cert", card).toString()
}

/** The WAVE server's place endpoints, in memory. A place's answer can be a package or an error. */
class FakePlaceDirectory : PlaceDirectory {
    var index: List<PlaceSummary> = emptyList()
    var indexDown = false
    val packages = mutableMapOf<String, String>()
    val broken = mutableSetOf<String>()
    var indexCalls = 0
    val downloads = mutableListOf<String>()

    override suspend fun index(): List<PlaceSummary> {
        indexCalls++
        if (indexDown) throw IOException("server asleep")
        return index
    }

    override suspend fun download(placeId: String): String {
        downloads += placeId
        if (placeId in broken) throw IOException("HTTP 502")
        return packages[placeId] ?: throw IOException("HTTP 404")
    }
}

class PlaceListsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now: Instant = Instant.parse("2026-11-24T12:00:00Z")
    private val root = Ed25519Sign.KeyPair.newKeyPair()
    private val roots = mapOf("wave-root-test" to b64(root.publicKey))
    private val admin = newPair()
    private val server = FakePlaceDirectory()
    private val dir: File get() = File(tmp.root, "places")
    private var clockMs = 1_000_000L

    private fun repo() = PlaceListsRepository(server, PlaceListStore(dir), roots, now = { now }, clockMs = { clockMs })
    private fun pkg(place: String, version: Int, issued: Instant = now.minus(Duration.ofHours(1)), listDays: Long = 20, cardDays: Long = 30) =
        placePackage(place, version, root, admin, issued, listDays, cardDays)

    private fun serve(place: String, version: Int, text: String = pkg(place, version)) {
        server.index = server.index.filter { it.placeId != place } + PlaceSummary(place, "Place $place", version)
        server.packages[place] = text
    }

    @Test
    fun downloadsOnlyNewerPlaces() = runBlocking {
        PlaceListStore(dir).write("a", pkg("a", 1))
        serve("a", 2)
        serve("b", 1)
        val r = repo()
        r.loadLocal()
        assertEquals(1, r.lists.value.getValue("a").registry.version)
        val sync = r.sync(force = true)
        assertEquals(setOf("a", "b"), sync.updated.toSet())
        assertEquals(2, r.lists.value.getValue("a").registry.version)
        assertEquals("WAVE Test", r.lists.value.getValue("b").placeName)
        server.downloads.clear()
        assertTrue(r.sync(force = true).updated.isEmpty())
        assertTrue(server.downloads.isEmpty())
        // Saved: a new repository on the same folder sees both.
        assertEquals(setOf("a", "b"), repo().apply { loadLocal() }.lists.value.keys)
    }

    @Test
    fun olderVersionIsRefused() = runBlocking {
        PlaceListStore(dir).write("a", pkg("a", 3))
        serve("a", 5, text = pkg("a", 2)) // the index claims v5, the package is a genuine v2
        val r = repo().apply { loadLocal() }
        val sync = r.sync(force = true)
        assertTrue(sync.refused.containsKey("a"))
        assertEquals(3, r.lists.value.getValue("a").registry.version)
    }

    @Test
    fun packageForAnotherPlaceIsRefused() = runBlocking {
        serve("sm-mall", 1, text = pkg("batstateu", 1))
        val r = repo()
        val sync = r.sync(force = true)
        assertTrue(sync.refused.containsKey("sm-mall"))
        assertTrue(r.lists.value.isEmpty())
    }

    @Test
    fun oneBadPlaceDoesNotStopOthers() = runBlocking {
        serve("down", 1)
        server.broken += "down"
        val other = newPair()
        serve("forged", 1, text = placePackage("forged", 1, root, admin, now.minus(Duration.ofHours(1))).let { good ->
            // Same package, signature from another key.
            JSONObject(good).put("signature_b64url", JSONObject(placePackage("forged", 1, root, other, now.minus(Duration.ofHours(1)))).getString("signature_b64url")).toString()
        })
        serve("good", 1)
        val r = repo()
        val sync = r.sync(force = true)
        assertEquals(listOf("good"), sync.updated)
        assertEquals(setOf("down", "forged"), sync.refused.keys)
        assertEquals(setOf("good"), r.lists.value.keys)
    }

    @Test
    fun damagedSavedCopyIsIgnored() = runBlocking {
        val store = PlaceListStore(dir)
        val edited = JSONObject(pkg("a", 1)).let { p ->
            val bytes = String(Base64.getUrlDecoder().decode(p.getString("registry_b64url")), Charsets.UTF_8).replace("NET-a", "NET-b")
            p.put("registry_b64url", b64(bytes.toByteArray(Charsets.UTF_8))).toString()
        }
        store.write("a", edited) // the list was changed after signing
        store.write("j", "{ not json")
        val r = repo().apply { loadLocal() }
        assertTrue(r.lists.value.isEmpty())
        serve("a", 1)
        assertEquals(listOf("a"), r.sync(force = true).updated)
        assertNotNull(r.lists.value["a"])
    }

    @Test
    fun expiredListsAreNotUsed() = runBlocking {
        serve("old", 1, text = pkg("old", 1, issued = now.minus(Duration.ofDays(60)), listDays = 20, cardDays = 90))
        val r = repo()
        assertEquals(listOf("old"), r.sync(force = true).updated) // genuine: kept on the phone
        assertNull(r.lists.value["old"])                         // but past expires_at: not used
    }

    @Test
    fun indexDownIsFailedAndKeepsLists() = runBlocking {
        PlaceListStore(dir).write("a", pkg("a", 1))
        server.indexDown = true
        val r = repo().apply { loadLocal() }
        assertNotNull(r.sync(force = true).failed)
        assertEquals(setOf("a"), r.lists.value.keys)
    }

    @Test
    fun placeMissingFromIndexIsKept() = runBlocking {
        PlaceListStore(dir).write("a", pkg("a", 1))
        server.index = emptyList()
        val r = repo().apply { loadLocal() }
        r.sync(force = true)
        assertEquals(setOf("a"), r.lists.value.keys)
    }

    @Test
    fun automaticSyncAtMostOncePerMinute() = runBlocking {
        val r = repo()
        r.sync()
        r.sync()
        assertEquals(1, server.indexCalls)
        clockMs += 61_000
        r.sync()
        assertEquals(2, server.indexCalls)
        r.sync(force = true)
        assertEquals(3, server.indexCalls)
    }

    @Test
    fun indexParsing() {
        val body = """{"places":[{"place_id":"batstateu","place_name":"Batangas State University","version":4,"updated_at":"2026-11-24T08:00:00Z"}]}"""
        assertEquals(listOf(PlaceSummary("batstateu", "Batangas State University", 4)), PlaceServerJson.index(body))
        for (junk in listOf("nope", """{"places":{}}""", """{"places":[{"place_id":"Bad Id","place_name":"x","version":1}]}""")) {
            assertThrows(junk, Exception::class.java) { PlaceServerJson.index(junk) }
        }
    }
}
