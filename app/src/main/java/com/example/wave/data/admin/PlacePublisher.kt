package com.example.wave.data.admin

import com.example.wave.model.Registry
import com.example.wave.verify.AdminCertificate
import com.example.wave.verify.RegistrySignature
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

/** What happened when the admin tapped Publish. */
sealed interface PublishOutcome {
    /** The server answered (STORED, STALE_VERSION, EXPIRED, ...) for the list with this [version]. */
    data class Answered(val answer: UploadAnswer, val version: Int) : PublishOutcome
    data object NotAdmin : PublishOutcome
    data object NothingToPublish : PublishOutcome
    data object ServerUnreachable : PublishOutcome
    data object NeedsUnlock : PublishOutcome
    data object KeyGone : PublishOutcome
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
 * Publishes the place's Wi-Fi list from this phone: asks the server for the current version, signs the next one
 * with the phone's admin key (ECDSA over "WAVE-REGISTRY-v1\n" + the exact bytes) and uploads the package.
 * The key must have been unlocked (fingerprint / screen lock) just before. Never throws except on cancel.
 */
class PlacePublisher(
    private val enrollment: AdminEnrollment,
    private val signer: AdminSigner,
    private val server: PlaceServer,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun publish(draft: RegistryDraft): PublishOutcome {
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
        val signature = try {
            signer.sign(RegistrySignature.DOMAIN + bytes)
        } catch (_: KeyNeedsUnlock) {
            return PublishOutcome.NeedsUnlock
        } catch (_: KeyGone) {
            return PublishOutcome.KeyGone
        }
        val pkg = JSONObject()
            .put("place_id", cert.placeId)
            .put("registry_b64url", b64(bytes))
            .put("signature_b64url", b64(signature))
            .put("cert", cardText)
            .toString()
        return try {
            PublishOutcome.Answered(server.upload(cert.placeId, pkg), version)
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            PublishOutcome.ServerUnreachable
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
