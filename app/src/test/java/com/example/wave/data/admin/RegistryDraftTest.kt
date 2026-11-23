package com.example.wave.data.admin

import com.example.wave.model.BlockedAp
import com.example.wave.model.CampusAsn
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class RegistryDraftTest {

    private val now = Instant.parse("2026-11-23T08:00:00Z")

    /** A list made on the radar while the phone had registry v1: the library radio approved, one rogue blocked. */
    private val v1 = RegistryDraft(
        baseVersion = 1,
        campusAsns = listOf(CampusAsn(0, "TBD")),
        approved = listOf(DraftAp("00:1a:2b:00:00:01", "BATSTATEU-ICT", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_5, 36, "Library", -80, -30, DraftSource.SURVEYED)),
        blocked = listOf(BlockedAp("6a:3f:00:11:22:9c", "BSU Free WiFi", "Rogue hotspot")),
    )

    private fun surveyed(
        bssid: String,
        ssid: String = "BATSTATEU-ICT",
        security: SecurityType = SecurityType.WPA2_PERSONAL,
        building: String = "Gym",
    ) = DraftAp(bssid, ssid, security, WifiBand.GHZ_2_4, 6, building, -85, -30, DraftSource.SURVEYED)

    @Test
    fun emptyListStartsAtTheGivenVersion() {
        val d = RegistryDraft.empty(3)
        assertEquals(3, d.baseVersion)
        assertTrue(d.approved.isEmpty() && d.blocked.isEmpty() && d.campusAsns.isEmpty())
    }

    @Test
    fun publishKeepsEverythingApprovedAndBlocked() {
        val r = v1.approve(listOf(surveyed("00:1a:2b:00:00:99"))).toRegistry(now)
        assertEquals(2, r.version)
        assertEquals(setOf("00:1a:2b:00:00:01", "00:1a:2b:00:00:99"), r.networks.flatMap { it.aps }.map { it.bssid }.toSet())
        assertEquals(listOf("6a:3f:00:11:22:9c"), r.blocked.map { it.bssid })
    }

    @Test
    fun approveRemovesFromBlocked() {
        val d = v1.approve(listOf(surveyed("6a:3f:00:11:22:9c")))
        assertTrue(d.blocked.isEmpty())
        assertTrue(d.approved.any { it.bssid == "6a:3f:00:11:22:9c" })
    }

    @Test
    fun blockRemovesFromApproved() {
        val d = v1.block("00:1a:2b:00:00:01", "BATSTATEU-ICT", "Not ours")
        assertTrue(d.approved.isEmpty())
        assertEquals(BlockedAp("00:1a:2b:00:00:01", "BATSTATEU-ICT", "Not ours"), d.blocked.last())
    }

    @Test
    fun approveSameBssidReplaces() {
        val d = v1.approve(listOf(surveyed("00:1a:2b:00:00:01", building = "Library 2F")))
        assertEquals(1, d.approved.size)
        assertEquals("Library 2F", d.approved.single().building)
    }

    @Test
    fun removeDeletesFromEitherList() {
        val d = v1.remove("00:1a:2b:00:00:01").remove("6a:3f:00:11:22:9c")
        assertTrue(d.approved.isEmpty() && d.blocked.isEmpty())
    }

    @Test
    fun groupsNetworksBySsidAndSecurity() {
        val r = RegistryDraft.empty(0).approve(
            listOf(
                surveyed("00:00:00:00:00:02", ssid = "B-NET"),
                surveyed("00:00:00:00:00:01", ssid = "A-NET"),
                surveyed("00:00:00:00:00:03", ssid = "A-NET"),
                surveyed("00:00:00:00:00:04", ssid = "A-NET", security = SecurityType.OPEN),
            ),
        ).toRegistry(now)
        assertEquals(listOf("A-NET" to SecurityType.WPA2_PERSONAL, "A-NET" to SecurityType.OPEN, "B-NET" to SecurityType.WPA2_PERSONAL).sortedBy { it.first }.map { it.first }, r.networks.map { it.ssid })
        val aNetWpa2 = r.networks.first { it.ssid == "A-NET" && it.security == SecurityType.WPA2_PERSONAL }
        assertEquals(listOf("00:00:00:00:00:01", "00:00:00:00:00:03"), aNetWpa2.aps.map { it.bssid })
        assertEquals(3, r.networks.size)
    }

    @Test
    fun noRegistryStartsAtVersion1() {
        val r = RegistryDraft.empty(0).approve(listOf(surveyed("00:00:00:00:00:01"))).toRegistry(now)
        assertEquals(1, r.version)
        assertEquals("wave-registry/1", r.schema)
        assertEquals("wave-2026-a", r.keyId)
    }

    @Test
    fun expiryIs180Days() {
        val r = v1.toRegistry(now)
        assertEquals(now, r.issuedAt)
        assertEquals(now.plus(Duration.ofDays(180)), r.expiresAt)
    }

    @Test
    fun unsupportedSecurityCantBeApproved() {
        val d = RegistryDraft.empty(0).approve(listOf(surveyed("00:00:00:00:00:01", security = SecurityType.WEP)))
        assertTrue(d.approved.isEmpty())
    }

    @Test
    fun singleScanRangeIsWidened() {
        assertEquals(-85..-30, surveyedRange(-60, -60))
    }

    @Test
    fun wideScanRangeIsKept() {
        assertEquals(-100..-15, surveyedRange(-95, -25))
    }

    @Test
    fun campusAsnReplacesPlaceholder() {
        val d = v1.withCampusAsn(CampusAsn(9299, "PLDT"))
        assertEquals(listOf(CampusAsn(9299, "PLDT")), d.campusAsns)
        val twice = d.withCampusAsn(CampusAsn(4775, "Globe"))
        assertEquals(listOf(CampusAsn(9299, "PLDT"), CampusAsn(4775, "Globe")), twice.campusAsns)
        assertEquals(twice, twice.withCampusAsn(CampusAsn(4775, "Globe")))
    }

    @Test
    fun signalTrackerKeepsMinMax() {
        val t = SignalTracker()
        assertNull(t.range("00:00:00:00:00:01"))
        listOf(-60, -72, -55).forEach { t.record("00:00:00:00:00:01", it) }
        assertEquals(-72..-55, t.range("00:00:00:00:00:01"))
    }

    // ---- edits on a whole network (all its radios) ----
    private val twoRadios = RegistryDraft.empty(1).approve(
        listOf(surveyed("52:14:c1:c3:f8:c0", building = "Home"), surveyed("52:14:c1:c3:f8:c4", building = "Home")),
    )

    @Test
    fun removeAllTakesEveryRadioOff() {
        val d = twoRadios.removeAll(listOf("52:14:c1:c3:f8:c0", "52:14:c1:c3:f8:c4"))
        assertTrue(d.approved.isEmpty())
    }

    @Test
    fun withBuildingRenamesEveryRadio() {
        val d = twoRadios.withBuilding(listOf("52:14:c1:c3:f8:c0", "52:14:c1:c3:f8:c4"), "  Library 2F ")
        assertEquals(listOf("Library 2F", "Library 2F"), d.approved.map { it.building })
    }

    @Test
    fun blockAllMovesEveryRadioToBlocked() {
        val d = twoRadios.blockAll(listOf("52:14:c1:c3:f8:c0", "52:14:c1:c3:f8:c4"), "BATSTATEU-ICT", "Not ours")
        assertTrue(d.approved.isEmpty())
        assertEquals(listOf("Not ours", "Not ours"), d.blocked.map { it.reason })
    }
}
