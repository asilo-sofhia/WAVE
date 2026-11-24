package com.example.wave.ui.screens.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wave.BuildConfig
import com.example.wave.R
import com.example.wave.data.admin.AdminStatus
import com.example.wave.data.admin.InstallResult
import com.example.wave.data.admin.NoScreenLock
import com.example.wave.di.AppContainer
import com.example.wave.ui.components.PrimaryButton
import com.example.wave.ui.components.SecondaryButton
import com.example.wave.ui.components.WaveBackground
import com.example.wave.ui.components.WaveCard
import com.example.wave.ui.components.WaveTextButton
import com.example.wave.ui.components.WaveTopBar
import com.example.wave.ui.theme.Danger
import com.example.wave.ui.theme.DangerBg
import com.example.wave.ui.theme.Navy900
import com.example.wave.ui.theme.NeutralBg
import com.example.wave.ui.theme.Slate600
import com.example.wave.ui.theme.Spacing
import com.example.wave.ui.theme.UnknownBg
import com.example.wave.ui.theme.UnknownFg
import com.example.wave.ui.theme.VerifiedBg
import com.example.wave.ui.theme.VerifiedFg
import com.example.wave.ui.theme.WAVETheme
import com.example.wave.verify.AdminCertificate
import com.example.wave.verify.AdminRequest
import com.example.wave.verify.CertResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A one-line result shown on the card after an action. */
data class AdminMessage(val text: String, val isError: Boolean)

/** What each ID card install result tells the admin. */
fun installMessage(result: InstallResult): AdminMessage = when (result) {
    InstallResult.INSTALLED -> AdminMessage("ID card installed.", isError = false)
    InstallResult.NOT_THIS_PHONE -> AdminMessage("This ID card was made for another phone.", true)
    InstallResult.UNKNOWN_KEY, InstallResult.BAD_SIGNATURE -> AdminMessage("This ID card wasn't made by the WAVE owner.", true)
    InstallResult.EXPIRED -> AdminMessage("This ID card has expired.", true)
    InstallResult.NOT_YET_VALID -> AdminMessage("This ID card isn't valid yet. Check the phone's date.", true)
    InstallResult.MALFORMED -> AdminMessage("This isn't a WAVE ID card.", true)
    InstallResult.NO_KEY -> AdminMessage("Create a request first.", true)
}

val NO_SCREEN_LOCK = AdminMessage("Set a screen lock (PIN, pattern or fingerprint) first, then try again.", true)

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())

/**
 * Runs an admin action; any failure becomes a message on the card instead of a crash (Keystore errors differ
 * between phones, and saving can fail when storage is full).
 */
fun adminAction(block: () -> AdminMessage?): AdminMessage? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: NoScreenLock) {
    NO_SCREEN_LOCK
} catch (e: IllegalArgumentException) {
    AdminMessage(e.message ?: "Check the name and place ID.", true)
} catch (_: Exception) {
    AdminMessage("Something went wrong on this phone. Try again.", true)
}

/** Manage registry opens only with a valid ID card (every way in); debug builds may skip it for testing. */
fun canOpenManageRegistry(status: AdminStatus, debug: Boolean = BuildConfig.DEBUG): Boolean =
    debug || status.adminLabel() != null

/** "Admin · <place>" when this phone holds a valid ID card, else null. */
fun AdminStatus.adminLabel(): String? =
    (this as? AdminStatus.Enrolled)?.takeIf { it.validity == CertResult.VALID }?.let { "Admin · ${it.cert.placeName}" }

/**
 * The "Manage registry" gate: this phone becomes a place admin (CONTRACT §6). It makes its own key, shares the
 * request, and installs the owner's ID card ([certText], when one was just scanned). Only a valid ID card opens
 * Manage registry; debug builds also keep "Continue without ID card" for testing.
 */
@Composable
fun AdminAccessRoute(
    container: AppContainer,
    certText: String?,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onScanCard: () -> Unit,
) {
    val enrolment = container.adminEnrollment
    val status by enrolment.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<AdminMessage?>(null) }
    var working by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { enrolment.refresh() }
    LaunchedEffect(certText) {
        if (!certText.isNullOrBlank()) {
            message = withContext(Dispatchers.IO) { adminAction { installMessage(enrolment.install(certText)) } }
        }
    }

    AdminAccessScreen(
        status = status,
        message = message,
        working = working,
        showDebugBypass = BuildConfig.DEBUG,
        onBack = onBack,
        onCreateRequest = { name, place ->
            working = true
            message = null
            scope.launch {
                message = withContext(Dispatchers.IO) { adminAction { enrolment.request(name, place); null } }
                working = false
            }
        },
        onShare = { text ->
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "Send your request to the WAVE owner"))
        },
        onCopy = { text ->
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("WAVE request", text))
            message = AdminMessage("Request copied.", isError = false)
        },
        onScanCard = onScanCard,
        onPasteCard = {
            val pasted = context.clipboardText()
            if (pasted.isNullOrBlank()) {
                message = AdminMessage("Copy the ID card text first.", true)
            } else {
                scope.launch { message = withContext(Dispatchers.IO) { adminAction { installMessage(enrolment.install(pasted)) } } }
            }
        },
        onStartOver = {
            scope.launch { message = withContext(Dispatchers.IO) { adminAction { enrolment.remove(); null } } }
        },
        onContinue = onContinue,
    )
}

private fun Context.clipboardText(): String? =
    getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

@Composable
fun AdminAccessScreen(
    status: AdminStatus,
    message: AdminMessage?,
    onBack: () -> Unit,
    onCreateRequest: (name: String, placeId: String) -> Unit,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onScanCard: () -> Unit,
    onPasteCard: () -> Unit,
    onStartOver: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    working: Boolean = false,
    showDebugBypass: Boolean = false,
) {
    var confirmStartOver by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize()) {
        WaveTopBar(title = null, onBack = onBack)
        WaveCard(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = Spacing.gutter, end = Spacing.gutter, bottom = Spacing.l),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                when (status) {
                    AdminStatus.NotStarted -> NotStartedContent(working, onCreateRequest)
                    is AdminStatus.Requested -> RequestedContent(status, onShare, onCopy, onScanCard, onPasteCard)
                    is AdminStatus.Enrolled -> EnrolledContent(status, onContinue)
                }
                if (message != null) MessageLine(message)
                if (status != AdminStatus.NotStarted) {
                    val label = if (status is AdminStatus.Enrolled && status.validity == CertResult.VALID) "Remove admin from this phone" else "Start over"
                    WaveTextButton(label, onClick = { confirmStartOver = true }, color = Danger)
                }
                if (showDebugBypass && status.adminLabel() == null) {
                    WaveTextButton("Continue without ID card (debug)", onClick = onContinue, color = Slate600)
                }
            }
        }
    }
    if (confirmStartOver) {
        AlertDialog(
            onDismissRequest = { confirmStartOver = false },
            title = { Text("Remove admin from this phone?") },
            text = { Text("This deletes this phone's admin key and ID card. You'll need a new ID card from the WAVE owner.") },
            confirmButton = { WaveTextButton("Remove", onClick = { confirmStartOver = false; onStartOver() }, color = Danger) },
            dismissButton = { WaveTextButton("Cancel", onClick = { confirmStartOver = false }) },
        )
    }
}

@Composable
private fun Title(text: String) = Text(
    text,
    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
    color = Navy900,
    modifier = Modifier.semantics { heading() },
)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = Slate600)

@Composable
private fun NotStartedContent(working: Boolean, onCreateRequest: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var place by rememberSaveable { mutableStateOf("") }
    val problem = AdminRequest.problem(name, place)
    Title("Become a place admin")
    Body("Ask the WAVE owner for an admin ID card. Your phone makes its own key; it never leaves this phone.")
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Your name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = place,
        onValueChange = { place = it.trim().lowercase() },
        label = { Text("Place ID (e.g. batstateu)") },
        singleLine = true,
        isError = place.isNotEmpty() && problem?.startsWith("Place") == true,
        supportingText = { if (name.isNotEmpty() && place.isNotEmpty() && problem != null) Text(problem) },
        modifier = Modifier.fillMaxWidth(),
    )
    PrimaryButton("Create request", onClick = { onCreateRequest(name, place) }, enabled = problem == null, loading = working)
}

@Composable
private fun RequestedContent(
    status: AdminStatus.Requested,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onScanCard: () -> Unit,
    onPasteCard: () -> Unit,
) {
    Title("Send your request")
    Body("Send this to the WAVE owner. They'll send back an ID card QR for ${status.placeId}. It only works on this phone.")
    SelectionContainer {
        Text(
            status.request,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Navy900,
            maxLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .background(NeutralBg, RoundedCornerShape(12.dp))
                .padding(Spacing.m),
        )
    }
    PrimaryButton("Share request", onClick = { onShare(status.request) })
    SecondaryButton("Copy request", onClick = { onCopy(status.request) })
    Spacer(Modifier.height(Spacing.s))
    Body("Got the ID card?")
    PrimaryButton("Scan ID card", onClick = onScanCard, icon = R.drawable.ic_grid)
    SecondaryButton("Paste ID card text", onClick = onPasteCard)
}

@Composable
private fun EnrolledContent(status: AdminStatus.Enrolled, onContinue: () -> Unit) {
    val cert = status.cert
    val valid = status.validity == CertResult.VALID
    StatusBadge(
        icon = if (valid) R.drawable.ic_shield_check else R.drawable.ic_shield_question,
        text = if (valid) "Admin · ${cert.placeName}" else "ID card not valid",
        fg = if (valid) VerifiedFg else UnknownFg,
        bg = if (valid) VerifiedBg else UnknownBg,
    )
    Title(cert.adminName)
    Body(
        when (status.validity) {
            CertResult.EXPIRED -> "ID card expired on ${DATE.format(cert.expiresAt)}. Ask the WAVE owner for a new one."
            CertResult.NOT_YET_VALID -> "ID card not valid until ${DATE.format(cert.issuedAt)}. Check the phone's date."
            else -> "${cert.placeName} (${cert.placeId}). ID card valid until ${DATE.format(cert.expiresAt)}."
        },
    )
    if (valid) PrimaryButton("Open Manage registry", onClick = onContinue)
}

@Composable
private fun StatusBadge(@DrawableRes icon: Int, text: String, fg: Color, bg: Color) {
    Row(
        Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = Spacing.m, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun MessageLine(message: AdminMessage) {
    val fg = if (message.isError) Danger else VerifiedFg
    val bg = if (message.isError) DangerBg else VerifiedBg
    Row(
        Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(12.dp))
            .padding(Spacing.m)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(
            painterResource(if (message.isError) R.drawable.ic_warning_outline else R.drawable.ic_check),
            contentDescription = if (message.isError) "Problem" else "Done",
            tint = fg,
            modifier = Modifier.size(20.dp),
        )
        Text(message.text, style = MaterialTheme.typography.bodyMedium, color = fg)
    }
}

// ---- Previews ----

private val previewCert = AdminCertificate(
    certId = "3f9a1c0d2b7e4a51", placeId = "batstateu", placeName = "Batangas State University", adminName = "Ana Cruz",
    adminPublicKey = "", issuedAt = Instant.parse("2026-11-24T00:00:00Z"), expiresAt = Instant.parse("2027-11-24T00:00:00Z"),
    rootKeyId = "wave-root-2026",
)
private val previewRequest = AdminStatus.Requested("Ana Cruz", "batstateu", "WAVEREQ:1:eyJhZG1pbl9rZXlfYWxnIjoiRVMyNTYiLCJhZG1pbl9uYW1lIjoiQW5hIENydXoiLCJwbGFjZV9pZCI6ImJhdHN0YXRldSJ9")

@Composable
private fun Preview(status: AdminStatus, message: AdminMessage? = null) = WAVETheme {
    WaveBackground {
        AdminAccessScreen(status, message, {}, { _, _ -> }, {}, {}, {}, {}, {}, {}, showDebugBypass = true)
    }
}

@Preview(showSystemUi = true, name = "Not started")
@Composable
private fun NotStartedPreview() = Preview(AdminStatus.NotStarted)

@Preview(showSystemUi = true, name = "No screen lock")
@Composable
private fun NoScreenLockPreview() = Preview(AdminStatus.NotStarted, NO_SCREEN_LOCK)

@Preview(showSystemUi = true, name = "Requested")
@Composable
private fun RequestedPreview() = Preview(previewRequest)

@Preview(showSystemUi = true, name = "Requested - card for another phone")
@Composable
private fun OtherPhonePreview() = Preview(previewRequest, installMessage(InstallResult.NOT_THIS_PHONE))

@Preview(showSystemUi = true, name = "Enrolled (verified)")
@Composable
private fun EnrolledPreview() = Preview(AdminStatus.Enrolled(previewCert, CertResult.VALID), installMessage(InstallResult.INSTALLED))

@Preview(showSystemUi = true, name = "Expired")
@Composable
private fun ExpiredPreview() = Preview(AdminStatus.Enrolled(previewCert, CertResult.EXPIRED))
