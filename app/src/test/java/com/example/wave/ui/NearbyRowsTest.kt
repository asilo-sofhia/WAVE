package com.example.wave.ui

import com.example.wave.data.fake.FakeFixtures
import com.example.wave.domain.RegistryClassifier
import com.example.wave.model.SecurityType
import com.example.wave.model.WifiBand
import com.example.wave.ui.components.accessPointsLabel
import com.example.wave.ui.screens.nearby.groupRows
import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyRowsTest {

    /** The router's other radio: same name, verdict and security as [FakeFixtures.campus]. */
    private val campus24 = FakeFixtures.campus.copy(bssid = "8c:3b:ad:72:10:4e", band = WifiBand.GHZ_2_4, signal = -62)

    @Test
    fun aRoutersRadiosBecomeOneRowLedByTheStrongest() {
        val rows = groupRows(listOf(campus24, FakeFixtures.campus, FakeFixtures.lab))
        assertEquals(2, rows.size)
        assertEquals(FakeFixtures.campus, rows[0].network) // -50 dBm beats -62 dBm
        assertEquals(listOf(FakeFixtures.campus, campus24), rows[0].accessPoints)
        assertEquals("2 access points · 2.4 + 5 GHz", accessPointsLabel(rows[0].accessPoints))
    }

    @Test
    fun anEvilTwinAlwaysKeepsItsOwnRow() {
        val rows = groupRows(listOf(FakeFixtures.campus, campus24, FakeFixtures.twin, FakeFixtures.library))
        assertEquals(listOf(FakeFixtures.campus, FakeFixtures.twin, FakeFixtures.library), rows.map { it.network })
        assertEquals(listOf(FakeFixtures.twin), rows[1].accessPoints)
    }

    @Test
    fun sameNameWithDifferentSecurityIsNotMerged() {
        val openCopy = FakeFixtures.library.copy(bssid = "a4:2b:b0:11:5e:7d", security = SecurityType.OPEN)
        assertEquals(2, groupRows(listOf(FakeFixtures.library, openCopy)).size)
    }

    @Test
    fun hiddenNetworksAreNeverMerged() {
        val h1 = FakeFixtures.cafe.copy(ssid = RegistryClassifier.HIDDEN_NAME, bssid = "f0:9f:c2:4a:01:b4")
        val h2 = h1.copy(bssid = "f0:9f:c2:4a:01:b5")
        assertEquals(2, groupRows(listOf(h1, h2)).size)
    }

    @Test
    fun everyScenarioStillShowsTheTwinSeparately() {
        // Fake data has one access point per name+verdict, so the demo lists look exactly as before.
        for (scenario in com.example.wave.data.fake.Scenario.entries) {
            assertEquals(scenario.label, scenario.nearby, groupRows(scenario.nearby).map { it.network })
        }
    }
}
