package com.example.wave.data.admin

import com.example.wave.model.CampusAsn
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import com.google.crypto.tink.subtle.Ed25519Sign
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** The server, in memory: it remembers uploads and answers like the real one for the happy path. */
class FakePlaceServer : PlaceServer {
    var version = 4
    var down = false
    var answer: ((Int) -> UploadAnswer)? = null
    val uploads = mutableListOf<Pair<String, String>>()
    var asked = 0

    override suspend fun latestVersion(placeId: String): Int {
        asked++
        if (down) throw IOException("no route")
        return version
    }

    override suspend fun upload(placeId: String, packageJson: String): UploadAnswer {
        uploads += placeId to packageJson
        val reg = JSONObject(String(b64d(JSONObject(packageJson).getString("registry_b64url")), Charsets.UTF_8))
        val v = reg.getInt("version")
        return answer?.invoke(v) ?: UploadAnswer("STORED", v)
    }
}

private fun b64d(s: String): ByteArray = Base64.getUrlDecoder().decode(s)
private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

class PlacePublisherTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day1: Instant = Instant.parse("2026-11-24T00:00:00Z")
    private val now: Instant = day1.plus(Duration.ofHours(1))
    private val root = Ed25519Sign.KeyPair.newKeyPair()
    private val roots = mapOf("wave-root-test" to b64(root.publicKey))
    private val keys = FakeAdminKeys()
    private val server = FakePlaceServer()
    private lateinit var enrollment: AdminEnrollment
    private lateinit var cardText: String

    private val draft = RegistryDraft(
        baseVersion = 0,
        campusAsns = listOf(CampusAsn(64500, "Example ISP")),
        approved = listOf(
            DraftAp("00:1a:2b:00:00:01", "WAVE-TEST-WIFI", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_5, 36, "Main", -80, -30, DraftSource.SURVEYED),
        ),
        blocked = emptyList(),
    )

    private fun enrol(days: Long = 365) {
        enrollment = AdminEnrollment(File(tmp.root, "enrolment-$days.json"), keys, roots) { now }
        enrollment.request("Test Admin", "wave-test")
        cardText = certify(keys.spki!!, root, day1, days = days)
        assertEquals(InstallResult.INSTALLED, enrollment.install(cardText))
    }

    private fun publisher() = PlacePublisher(enrollment, keys, server) { now }

    @Before fun setUp() = enrol()

    @Test
    fun publishesASignedPlacePackage() = runBlocking {
        val outcome = publisher().publish(draft)
        assertEquals(PublishOutcome.Answered(UploadAnswer("STORED", 5), 5), outcome)
        assertEquals(1, server.uploads.size)
        val (placeId, json) = server.uploads.single()
        assertEquals("wave-test", placeId)
        val pkg = JSONObject(json)
        assertEquals(setOf("place_id", "registry_b64url", "signature_b64url", "cert"), pkg.keys().asSequence().toSet())
        assertEquals("wave-test", pkg.getString("place_id"))
        assertEquals(cardText, pkg.getString("cert"))
        val bytes = b64d(pkg.getString("registry_b64url"))
        val reg = JSONObject(String(bytes, Charsets.UTF_8))
        assertEquals("wave-test", reg.getString("place_id"))
        assertEquals("0123456789abcdef", reg.getString("key_id"))
        assertEquals(5, reg.getInt("version"))
        assertEquals(1, reg.getJSONArray("networks").length())
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keys.spki!!))
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(pub)
            update("WAVE-REGISTRY-v1\n".toByteArray(Charsets.UTF_8) + bytes)
            verify(b64d(pkg.getString("signature_b64url")))
        }
        assertTrue("signature verifies over the exact bytes", ok)
    }

    @Test
    fun notListedStartsAtVersion1() = runBlocking {
        server.version = 0
        assertEquals(PublishOutcome.Answered(UploadAnswer("STORED", 1), 1), publisher().publish(draft))
    }

    private fun uploadedRegistry(): JSONObject =
        JSONObject(String(b64d(JSONObject(server.uploads.last().second).getString("registry_b64url")), Charsets.UTF_8))

    @Test
    fun expiryCappedAtCard() = runBlocking {
        enrol(days = 30)
        publisher().publish(draft)
        assertEquals(day1.plus(Duration.ofDays(30)).toString(), uploadedRegistry().getString("expires_at"))
        enrol(days = 400)
        publisher().publish(draft)
        assertEquals(now.plus(Duration.ofDays(180)).toString(), uploadedRegistry().getString("expires_at"))
    }

    @Test
    fun serverDownBeforeSigning() = runBlocking {
        server.down = true
        assertEquals(PublishOutcome.ServerUnreachable, publisher().publish(draft))
        assertEquals(0, keys.signed)
        assertTrue(server.uploads.isEmpty())
    }

    @Test
    fun staleVersionPassesThrough() = runBlocking {
        server.answer = { UploadAnswer("STALE_VERSION", null) }
        assertEquals(PublishOutcome.Answered(UploadAnswer("STALE_VERSION", null), 5), publisher().publish(draft))
    }

    @Test
    fun signerFailures() = runBlocking {
        keys.signFailure = KeyNeedsUnlock()
        assertEquals(PublishOutcome.NeedsUnlock, publisher().publish(draft))
        keys.signFailure = KeyGone()
        assertEquals(PublishOutcome.KeyGone, publisher().publish(draft))
        assertTrue(server.uploads.isEmpty())
    }

    @Test
    fun emptyListIsRefused() = runBlocking {
        assertEquals(PublishOutcome.NothingToPublish, publisher().publish(draft.copy(approved = emptyList())))
        assertEquals(0, server.asked)
    }

    @Test
    fun notEnrolled() = runBlocking {
        enrollment.remove()
        assertEquals(PublishOutcome.NotAdmin, publisher().publish(draft))
        assertEquals(0, server.asked)
    }

    @Test
    fun writerPutsPlaceIdAfterKeyId() {
        val reg = draft.toRegistry(now)
        val text = RegistryJsonWriter.write(reg, placeId = "wave-test")
        assertTrue(text, text.contains("\"key_id\": \"${reg.keyId}\",\n  \"place_id\": \"wave-test\",\n"))
        assertFalse(RegistryJsonWriter.write(reg).contains("place_id"))
    }
}
