package com.example.wave.data.admin

import com.example.wave.model.Registry
import com.example.wave.verify.AdminCertificate
import com.example.wave.verify.RegistrySignature
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/** One step of publishing: either ready to sign ([PlacePublisher.Prepared]) or finished ([PublishOutcome]). */
sealed interface PublishStep

/** What happened when the admin tapped Publish. */
sealed interface PublishOutcome : PublishStep {
    /** The server answered (STORED, STALE_VERSION, EXPIRED, ...) for the list with this [version]. */
    data class Answered(val answer: UploadAnswer, val version: Int) : PublishOutcome
    data object NotAdmin : PublishOutcome
    data object NothingToPublish : PublishOutcome
    data object ServerUnreachable : PublishOutcome
    data object NeedsUnlock : PublishOutcome
    data object KeyGone : PublishOutcome

    /** Any other Keystore failure while signing (StrongBox, keystore service, ...). */
    data object CouldNotSign : PublishOutcome
}

/**
 * The place's registry from the admin's draft (CONTRACT §7): the next [version], signed under the ID card
 * ([AdminCertificate.certId] as key_id), and never valid longer than the card itself.
 */
fun RegistryDraft.toPlaceRegistry(now: Instant, version: Int, cert: AdminCertificate): Registry {
    val r = toRegistry(now)
    return r.copy(version = version, keyId = cert.certId, expiresAt = minOf(r.expiresAt, cert.expiresAt))
}

/**
 * Publishes the place's Wi-Fi list from this phone, in two steps so the unlock prompt sits between them:
 * [prepare] checks the card and asks the server for the current version (a sleeping server can take a minute,
 * longer than the key's 30 s unlock); [signAndUpload] signs the next version with the phone's admin key
 * (ECDSA over "WAVE-REGISTRY-v1\n" + the exact bytes) right after the unlock and uploads the package.
 * Neither throws except on cancel.
 */
class PlacePublisher(
    private val enrollment: AdminEnrollment,
    private val signer: AdminSigner,
    private val server: PlaceServer,
    private val clock: () -> Instant = Instant::now,
) {
    /** Everything needed to sign: the exact registry bytes for [version]. */
    data class Prepared(val placeId: String, val cardText: String, val version: Int, val registryBytes: ByteArray) : PublishStep {
        override fun equals(other: Any?) = other is Prepared && placeId == other.placeId && cardText == other.cardText &&
            version == other.version && registryBytes.contentEquals(other.registryBytes)
        override fun hashCode() = registryBytes.contentHashCode()
    }

    suspend fun prepare(draft: RegistryDraft): PublishStep {
        val (cert, cardText) = enrollment.validCard() ?: return PublishOutcome.NotAdmin
        if (draft.approved.isEmpty()) return PublishOutcome.NothingToPublish
        val version = try {
            server.latestVersion(cert.placeId) + 1
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            return PublishOutcome.ServerUnreachable
        }
        val bytes = RegistryJsonWriter.write(draft.toPlaceRegistry(clock(), version, cert), cert.placeId).toByteArray(Charsets.UTF_8)
        return Prepared(cert.placeId, cardText, version, bytes)
    }

    suspend fun signAndUpload(p: Prepared): PublishOutcome {
        val signature = try {
            signer.sign(RegistrySignature.DOMAIN + p.registryBytes)
        } catch (_: KeyNeedsUnlock) {
            return PublishOutcome.NeedsUnlock
        } catch (_: KeyGone) {
            return PublishOutcome.KeyGone
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { // ProviderException, GeneralSecurityException, ... differ between phones
            return PublishOutcome.CouldNotSign
        }
        val pkg = JSONObject()
            .put("place_id", p.placeId)
            .put("registry_b64url", b64(p.registryBytes))
            .put("signature_b64url", b64(signature))
            .put("cert", p.cardText)
            .toString()
        return try {
            PublishOutcome.Answered(server.upload(p.placeId, pkg), p.version)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            PublishOutcome.ServerUnreachable
        }
    }

    /** Both steps at once (no unlock in between): for tests and callers that already unlocked. */
    suspend fun publish(draft: RegistryDraft): PublishOutcome = when (val step = prepare(draft)) {
        is Prepared -> signAndUpload(step)
        is PublishOutcome -> step
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
