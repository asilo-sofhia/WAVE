package com.example.wave.ui.screens.tools

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.wave.data.admin.PublishOutcome
import com.example.wave.ui.components.WaveTextButton
import com.example.wave.ui.theme.WAVETheme
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** What the admin reads after tapping Publish. */
fun publishMessage(outcome: PublishOutcome, placeName: String): String = when (outcome) {
    is PublishOutcome.Answered -> when (outcome.answer.result) {
        "STORED" -> "Published version ${outcome.answer.version ?: outcome.version} to $placeName. Phones get it on their next update."
        "STALE_VERSION" -> "A newer list is already published. Tap Publish again."
        "EXPIRED" -> "Your ID card has expired. Ask the WAVE owner for a new one."
        "NOT_YET_VALID" -> "The server's clock and this phone's disagree. Check the phone's date."
        "UNKNOWN_KEY", "BAD_SIGNATURE", "WRONG_PLACE" -> "The server refused this list (${outcome.answer.result})."
        "MALFORMED", "TOO_LARGE" -> "The server couldn't read this list (${outcome.answer.result})."
        "UPLOADS_OFF", "STORAGE_UNAVAILABLE" -> "The server can't save lists right now. Try again later."
        else -> "The server answered ${outcome.answer.result}."
    }
    PublishOutcome.ServerUnreachable -> "Can't reach the WAVE server. Try again in a minute."
    PublishOutcome.NeedsUnlock -> "Not published: unlock was cancelled."
    PublishOutcome.KeyGone -> "This phone's admin key is gone. Remove admin and enrol again."
    PublishOutcome.CouldNotSign -> "Couldn't sign on this phone. Try again; if it keeps failing, remove admin and enrol again."
    PublishOutcome.NotAdmin -> "Install your admin ID card first."
    PublishOutcome.NothingToPublish -> "Approve at least one network first."
}

/**
 * The system unlock prompt (fingerprint, face or screen lock). True when the owner unlocked: the admin key can
 * then sign for 30 s. False when they cancelled or it failed. Android's own prompt: no extra library.
 */
suspend fun Context.confirmWithScreenLock(title: String, subtitle: String): Boolean = suspendCancellableCoroutine { cont ->
    val builder = BiometricPrompt.Builder(this).setTitle(title).setSubtitle(subtitle)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    val cancel = CancellationSignal()
    cont.invokeOnCancellation { cancel.cancel() }
    try {
        builder.build().authenticate(
            cancel,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (cont.isActive) cont.resume(false)
                }
                // onAuthenticationFailed = a finger that didn't match; the prompt stays open for another try.
            },
        )
    } catch (_: Exception) { // e.g. no screen lock any more
        if (cont.isActive) cont.resume(false)
    }
}

/** "Publish N networks and M blocked to <place>?" before the unlock prompt. */
@Composable
fun PublishConfirmDialog(placeName: String, networks: Int, blocked: Int, onPublish: () -> Unit, onDismiss: () -> Unit) {
    val what = (if (networks == 1) "1 network" else "$networks networks") + " and $blocked blocked"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Publish to $placeName?") },
        text = { Text("Publish $what to $placeName? Phones will use this list. You'll confirm with your fingerprint or screen lock.") },
        confirmButton = { WaveTextButton("Publish", onClick = onPublish) },
        dismissButton = { WaveTextButton("Cancel", onClick = onDismiss) },
    )
}

@Preview(name = "Publish confirm")
@Composable
private fun PublishConfirmPreview() = WAVETheme { PublishConfirmDialog("WAVE Test", 2, 1, {}, {}) }
