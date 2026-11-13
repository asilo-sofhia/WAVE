package com.example.wave.domain

import com.example.wave.data.NetworkClassifier
import com.example.wave.model.CheckKind
import com.example.wave.model.CheckOutcome
import com.example.wave.model.NetworkMetrics
import com.example.wave.model.NetworkUi
import com.example.wave.model.Registry
import com.example.wave.model.RegistryNetwork
import com.example.wave.model.ScanObservation
import com.example.wave.model.Verdict
import com.example.wave.model.VerdictCheck
import com.example.wave.model.isLocallyAdministered

/**
 * Decides Verified / Unknown / Warning for one access point by comparing it with the school registry.
 * Pure Kotlin (no Android), so every rule is unit-tested.
 *
 * Rules (docs/INTEGRATION_PLAN.md §1.2):
 * - No registry, or the name isn't in it -> UNKNOWN (nothing to compare with).
 * - Name is in the registry -> each check below can FAIL; any failure -> WARNING with that reason.
 * - All checks pass -> VERIFIED.
 */
class RegistryClassifier(
    /** Allowed distance (dB) outside a registered AP's surveyed range before the signal counts as unusual. */
    private val rssiMarginDb: Int = RSSI_MARGIN_DB,
) : NetworkClassifier {

    override fun classify(observation: ScanObservation, registry: Registry?): NetworkUi {
        val entry = registry?.networks?.firstOrNull { it.ssid == observation.ssid && observation.ssid.isNotEmpty() }
        val checks = if (entry == null) unregisteredChecks(observation, registryLoaded = registry != null)
        else registeredChecks(observation, entry)

        val failed = checks.filter { it.outcome == CheckOutcome.FAILED }
        val verdict = when {
            failed.isNotEmpty() -> Verdict.WARNING
            checks.any { it.outcome == CheckOutcome.UNKNOWN } -> Verdict.UNKNOWN
            else -> Verdict.VERIFIED
        }
        return NetworkUi(
            ssid = observation.ssid.ifEmpty { HIDDEN_NAME },
            bssid = observation.bssid,
            security = observation.security,
            band = observation.band,
            signal = observation.rssi,
            verdict = verdict,
            reasons = failed.mapNotNull { it.kind.reason },
            metrics = NetworkMetrics(
                frequencyMhz = observation.frequencyMhz,
                channel = observation.channel,
                channelWidthMhz = observation.channelWidthMhz,
                wifiStandard = observation.wifiStandard,
                capabilities = observation.capabilities,
                seenAtMillis = observation.timestampMillis,
            ),
            checks = checks,
        )
    }

    /** Not in the registry (or no registry at all): only the hardware address can be looked at. */
    private fun unregisteredChecks(o: ScanObservation, registryLoaded: Boolean): List<VerdictCheck> {
        val name = o.ssid.ifEmpty { "This hidden network" }
        val nameDetail = when {
            !registryLoaded -> "No school registry is loaded yet, so WAVE can't confirm any network."
            o.ssid.isEmpty() -> "Hidden networks don't share their name, so WAVE can't look them up."
            else -> "$name isn't on the school's registry, so WAVE has nothing to compare it with."
        }
        return listOf(
            VerdictCheck(CheckKind.NETWORK_NAME, CheckOutcome.UNKNOWN, nameDetail),
            VerdictCheck(CheckKind.ACCESS_POINT, CheckOutcome.SKIPPED, "No registered access points to compare with."),
            hardwareCheck(o, registered = false),
            VerdictCheck(CheckKind.SECURITY, CheckOutcome.SKIPPED, "Uses ${o.security.label}. No registry entry to compare with."),
            VerdictCheck(CheckKind.SIGNAL, CheckOutcome.SKIPPED, "${o.rssi} dBm. No normal range on record for this network."),
        )
    }

    private fun registeredChecks(o: ScanObservation, entry: RegistryNetwork): List<VerdictCheck> {
        val ap = entry.aps.firstOrNull { it.bssid == o.bssid }
        val accessPoint = if (ap != null) {
            VerdictCheck(CheckKind.ACCESS_POINT, CheckOutcome.PASSED, "Matches a registered access point (${ap.building}).")
        } else {
            VerdictCheck(
                CheckKind.ACCESS_POINT, CheckOutcome.FAILED,
                "${o.bssid} is not one of the ${entry.aps.size} access points registered for ${entry.ssid}.",
            )
        }

        val security = if (o.security == entry.security) {
            VerdictCheck(CheckKind.SECURITY, CheckOutcome.PASSED, "${o.security.label}, same as the registry.")
        } else {
            VerdictCheck(
                CheckKind.SECURITY, CheckOutcome.FAILED,
                "${o.security.label}, but the registry says ${entry.security.label}.",
            )
        }

        // Compare with this AP's surveyed range, or (for an unknown AP) the range of all of this network's APs.
        val ranges = if (ap != null) listOf(ap) else entry.aps
        val min = ranges.minOfOrNull { it.rssiMin }
        val max = ranges.maxOfOrNull { it.rssiMax }
        val signal = when {
            min == null || max == null -> VerdictCheck(CheckKind.SIGNAL, CheckOutcome.SKIPPED, "${o.rssi} dBm. No normal range on record.")
            o.rssi in (min - rssiMarginDb)..(max + rssiMarginDb) -> VerdictCheck(
                CheckKind.SIGNAL, CheckOutcome.PASSED, "${o.rssi} dBm, inside the normal range here ($min to $max dBm).",
            )
            else -> VerdictCheck(
                CheckKind.SIGNAL, CheckOutcome.FAILED, "${o.rssi} dBm. The real ${entry.ssid} reads $min to $max dBm here.",
            )
        }

        return listOf(
            VerdictCheck(CheckKind.NETWORK_NAME, CheckOutcome.PASSED, "${entry.ssid} is on the school's registry."),
            accessPoint,
            hardwareCheck(o, registered = true),
            security,
            signal,
        )
    }

    /**
     * A made-up (locally administered) address is a red flag for a registered name. For an unknown network it
     * is only a hint (phone hotspots use them too), so it stays UNKNOWN instead of FAILED.
     */
    private fun hardwareCheck(o: ScanObservation, registered: Boolean): VerdictCheck = when {
        !isLocallyAdministered(o.bssid) ->
            VerdictCheck(CheckKind.HARDWARE_ADDRESS, CheckOutcome.PASSED, "Manufacturer-assigned address, as real routers have.")
        registered -> VerdictCheck(
            CheckKind.HARDWARE_ADDRESS, CheckOutcome.FAILED,
            "The first byte (${o.bssid.take(2)}) marks a made-up address, typical of phone hotspots and fake routers.",
        )
        else -> VerdictCheck(
            CheckKind.HARDWARE_ADDRESS, CheckOutcome.UNKNOWN,
            "Made-up address (first byte ${o.bssid.take(2)}), as phone hotspots use. Not proof of a fake.",
        )
    }

    companion object {
        const val RSSI_MARGIN_DB = 5
        const val HIDDEN_NAME = "Hidden network"
    }
}
