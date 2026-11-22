package com.example.wave.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color
import com.example.wave.R
import com.example.wave.model.CheckOutcome
import com.example.wave.model.ExitPathStatus
import com.example.wave.model.Verdict
import com.example.wave.ui.theme.NeutralBg
import com.example.wave.ui.theme.NeutralFg
import com.example.wave.ui.theme.UnknownBg
import com.example.wave.ui.theme.UnknownFg
import com.example.wave.ui.theme.VerifiedBg
import com.example.wave.ui.theme.VerifiedFg
import com.example.wave.ui.theme.WarningBg
import com.example.wave.ui.theme.WarningFg

/** Color + icon + word for a status. Every status uses all three, never color alone. */
data class StatusStyle(
    val fg: Color,
    val bg: Color,
    @param:DrawableRes val icon: Int,
    val label: String,
)

fun Verdict.style(): StatusStyle = when (this) {
    Verdict.VERIFIED -> StatusStyle(VerifiedFg, VerifiedBg, R.drawable.ic_check, "Verified")
    Verdict.WARNING -> StatusStyle(WarningFg, WarningBg, R.drawable.ic_warning, "Warning")
    Verdict.UNKNOWN -> StatusStyle(UnknownFg, UnknownBg, R.drawable.ic_help, "Unknown")
}

fun ExitPathStatus.style(): StatusStyle = when (this) {
    ExitPathStatus.PENDING -> StatusStyle(UnknownFg, UnknownBg, R.drawable.ic_schedule, "Pending")
    ExitPathStatus.PASSED -> StatusStyle(VerifiedFg, VerifiedBg, R.drawable.ic_check, "Passed")
    ExitPathStatus.FAILED -> StatusStyle(WarningFg, WarningBg, R.drawable.ic_warning, "Failed")
}

fun CheckOutcome.style(): StatusStyle = when (this) {
    CheckOutcome.PASSED -> StatusStyle(VerifiedFg, VerifiedBg, R.drawable.ic_check, "Passed")
    CheckOutcome.FAILED -> StatusStyle(WarningFg, WarningBg, R.drawable.ic_warning, "Failed")
    CheckOutcome.UNKNOWN -> StatusStyle(UnknownFg, UnknownBg, R.drawable.ic_help, "Unknown")
    CheckOutcome.SKIPPED -> StatusStyle(NeutralFg, NeutralBg, R.drawable.ic_remove, "Skipped")
}

val NotCheckedStyle =StatusStyle(NeutralFg, NeutralBg, R.drawable.ic_help, "Not checked")
