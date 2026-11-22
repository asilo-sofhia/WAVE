package com.example.wave.ui

import com.example.wave.data.admin.DraftAp
import com.example.wave.data.admin.DraftSource
import com.example.wave.data.admin.RegistryDraft
import com.example.wave.data.fake.FakeFixtures
import com.example.wave.domain.RegistryClassifier
import com.example.wave.model.BlockedAp
import com.example.wave.model.CampusAsn
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import com.example.wave.ui.screens.tools.DraftStatus
import com.example.wave.ui.screens.tools.approvalsFor
import com.example.wave.ui.screens.tools.canApprove
import com.example.wave.ui.screens.tools.draftStatus
import com.example.wave.ui.screens.tools.reviewAps
import com.example.wave.ui.screens.tools.summarize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManageRegistryStateTest {

    private fun ap(bssid: String, source: DraftSource) =
        DraftAp(bssid, "BATSTATEU-ICT", SecurityType.WPA2_PERSONAL, WifiBand.GHZ_5, 36, "Library", -85, -30, source)

    private val draft = RegistryDraft(
        baseVersion = 1,
        campusAsns = listOf(CampusAsn(0, "TBD")),
        approved = listOf(ap("00:00:00:00:00:01", DraftSource.FROM_REGISTRY), ap("00:00:00:00:00:02", DraftSource.SURVEYED)),
        blocked = listOf(BlockedAp("6a:00:00:00:00:09", "BSU Free WiFi", "Rogue")),
    )

    @Test
    fun statusChipsFollowTheDraft() {
        assertEquals(DraftStatus.NEW, draftStatus(draft, listOf("00:00:00:00:00:77")))
        assertEquals(DraftStatus.APPROVED, draftStatus(draft, listOf("00:00:00:00:00:01")))
        assertEquals(DraftStatus.APPROVED, draftStatus(draft, listOf("00:00:00:00:00:02")))
        assertEquals(DraftStatus.APPROVED, draftStatus(draft, listOf("00:00:00:00:00:01", "00:00:00:00:00:02")))
        assertEquals(DraftStatus.BLOCKED, draftStatus(draft, listOf("6a:00:00:00:00:09", "00:00:00:00:00:01")))
        assertEquals(DraftStatus.NEW, draftStatus(null, listOf("00:00:00:00:00:01")))
    }

    @Test
    fun reviewPreselectsEveryRadioExceptMadeUpAddresses() {
        val real = FakeFixtures.campus.copy(bssid = "8C:3B:AD:72:10:4F", signal = -50)
        val madeUp = FakeFixtures.campus.copy(bssid = "6a:3f:00:11:22:9c", signal = -40)
        val rows = reviewAps(listOf(real, madeUp)) { bssid -> if (bssid == "8c:3b:ad:72:10:4f") -62..-48 else null }
        assertEquals(listOf("6a:3f:00:11:22:9c", "8c:3b:ad:72:10:4f"), rows.map { it.bssid }) // strongest first, lowercase
        assertFalse(rows[0].preselected)
        assertTrue(rows[0].madeUp)
        assertTrue(rows[1].preselected)
        assertEquals(-62..-48, rows[1].observed)
    }

    @Test
    fun approvalsUseObservedRangeBuildingAndLowercase() {
        val n = FakeFixtures.campus.copy(bssid = "8C:3B:AD:72:10:4F", signal = -50)
        val rows = reviewAps(listOf(n)) { -62..-48 }
        val out = approvalsFor(n, rows, selected = setOf("8c:3b:ad:72:10:4f"), building = "  CICS 2F  ")
        assertEquals(1, out.size)
        with(out.single()) {
            assertEquals("8c:3b:ad:72:10:4f", bssid)
            assertEquals("BATSTATEU-ICT", ssid)
            assertEquals("CICS 2F", building)
            assertEquals(36, channel)
            assertEquals(-85, rssiMin) // surveyedRange(-62, -48)
            assertEquals(-30, rssiMax)
            assertEquals(DraftSource.SURVEYED, source)
        }
        assertTrue(approvalsFor(n, rows, selected = emptySet(), building = "x").isEmpty())
    }

    @Test
    fun hiddenAndUnsupportedNetworksCantBeApproved() {
        assertTrue(canApprove(FakeFixtures.campus))
        assertFalse(canApprove(FakeFixtures.campus.copy(ssid = RegistryClassifier.HIDDEN_NAME)))
        assertFalse(canApprove(FakeFixtures.campus.copy(security = SecurityType.WEP)))
    }

    @Test
    fun summaryCountsAndExport() {
        val s = summarize(draft)
        assertEquals(2, s.approvedCount)
        assertEquals(1, s.blockedCount)
        assertEquals(1, s.networkCount)
        assertEquals(2, s.nextVersion)
        assertTrue(s.canExport)
        assertTrue(s.campusAsnMissing)

        val empty = summarize(RegistryDraft(0, emptyList(), emptyList(), emptyList()))
        assertFalse(empty.canExport)
        assertTrue(empty.isEmpty)
        assertEquals(1, empty.nextVersion)
    }
}
