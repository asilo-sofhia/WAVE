package com.example.wave.data.admin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.wave.data.registry.RegistryJson
import com.example.wave.model.BlockedAp
import com.example.wave.model.CampusAsn
import com.example.wave.model.Registry
import com.example.wave.model.RegistryAp
import com.example.wave.model.RegistryNetwork
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Runs on a device: the draft file and the export use Android's org.json. */
@RunWith(AndroidJUnit4::class)
class RegistryDraftStoreTest {

    private val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "draft-test")
    private val file = File(dir, "draft.json")

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private val v1 = Registry(
        schema = "wave-registry/1", version = 1, keyId = "wave-2026-a",
        issuedAt = Instant.parse("2026-11-10T00:00:00Z"), expiresAt = Instant.parse("2027-01-10T00:00:00Z"),
        campusAsns = listOf(CampusAsn(0, "TBD")),
        networks = listOf(
            RegistryNetwork("BATSTATEU-ICT", SecurityType.WPA2_PERSONAL, listOf(RegistryAp("00:1a:2b:00:00:01", WifiBand.GHZ_5, 36, "Library", -80, -30))),
        ),
        blocked = listOf(BlockedAp("6a:3f:00:11:22:9c", "BSU Free WiFi", "Rogue hotspot")),
    )

    @Test
    fun firstLoadStartsEmptyAtTheCurrentVersionAndSavesIt() = runBlocking {
        val d = RegistryDraftStore(file).load(current = v1)
        assertEquals(RegistryDraft.empty(1), d)
        assertEquals(d, RegistryDraftStore(file).load(current = null)) // read back from the file
    }

    @Test
    fun entriesCopiedFromARegistryByOlderVersionsAreDropped() = runBlocking {
        val old = RegistryDraft(
            baseVersion = 1,
            campusAsns = emptyList(),
            approved = listOf(
                DraftAp("00:1a:2b:00:00:01", "BATSTATEU-ICT", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_5, 36, "Library", -80, -30, DraftSource.FROM_REGISTRY),
                DraftAp("00:1a:2b:00:00:99", "BATSTATEU-ICT", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_2_4, 6, "Gym", -85, -30, DraftSource.SURVEYED),
            ),
            blocked = emptyList(),
        )
        dir.mkdirs()
        file.writeText(RegistryDraftStore.encode(old))
        assertEquals(listOf("00:1a:2b:00:00:99"), RegistryDraftStore(file).load(current = null).approved.map { it.bssid })
    }

    @Test
    fun updatesSurviveARestart() = runBlocking {
        val store = RegistryDraftStore(file)
        store.load(current = v1)
        store.update {
            it.approve(listOf(DraftAp("00:1a:2b:00:00:99", "BATSTATEU-ICT", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_2_4, 6, "Gym \"A\"", -85, -30, DraftSource.SURVEYED)))
                .withCampusAsn(CampusAsn(9299, "PLDT"))
        }
        val reloaded = RegistryDraftStore(file).load(current = null)
        assertEquals(store.draft.value, reloaded)
        assertEquals(1, reloaded.approved.size)
    }

    @Test
    fun clearEmptiesTheList() = runBlocking {
        val store = RegistryDraftStore(file)
        store.load(current = v1)
        store.update { it.block("6a:3f:00:11:22:9c", "x", "r") }
        store.clear(current = v1)
        assertEquals(RegistryDraft.empty(1), RegistryDraftStore(file).load(current = null))
    }

    @Test
    fun exportedJsonParsesBackToTheSameRegistry() {
        val exported = RegistryDraft.empty(1)
            .approve(listOf(DraftAp("00:1a:2b:00:00:99", "BATSTATEU-ICT", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_2_4, 6, "Gym", -85, -30, DraftSource.SURVEYED)))
            .toRegistry(Instant.parse("2026-11-23T08:00:00Z"))
        assertEquals(exported, RegistryJson.parse(RegistryJsonWriter.write(exported).toByteArray(Charsets.UTF_8)))
    }
}
