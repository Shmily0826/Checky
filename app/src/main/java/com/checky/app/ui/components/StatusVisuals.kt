package com.checky.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.checky.app.R
import androidx.compose.ui.graphics.Color
import com.checky.app.domain.model.CheckInStatus

/**
 * Maps a [CheckInStatus] to a human label + a semantic color that works in both
 * light and dark themes (it is derived from the active ColorScheme).
 */
data class StatusVisual(
    val label: String,
    val color: Color
)

internal enum class StatusColorRole { POSITIVE, ERROR, WARNING, ACTIVE, NEUTRAL }

internal fun statusColorRole(status: CheckInStatus): StatusColorRole = when (status) {
    CheckInStatus.SUCCESS, CheckInStatus.ALREADY_CHECKED_IN -> StatusColorRole.POSITIVE
    CheckInStatus.FAILED -> StatusColorRole.ERROR
    CheckInStatus.LOGIN_EXPIRED, CheckInStatus.USER_ACTION_REQUIRED -> StatusColorRole.WARNING
    CheckInStatus.RUNNING -> StatusColorRole.ACTIVE
    CheckInStatus.PENDING -> StatusColorRole.NEUTRAL
}

@Composable
fun statusVisual(status: CheckInStatus): StatusVisual {
    val scheme = MaterialTheme.colorScheme
    val color = when (statusColorRole(status)) {
        StatusColorRole.POSITIVE -> scheme.tertiary
        StatusColorRole.ERROR -> scheme.error
        StatusColorRole.WARNING -> if (status == CheckInStatus.LOGIN_EXPIRED) Color(0xFFE0A000) else Color(0xFFE0730C)
        StatusColorRole.ACTIVE -> scheme.primary
        StatusColorRole.NEUTRAL -> scheme.outline
    }
    val label = when (status) {
        CheckInStatus.SUCCESS -> stringResource(R.string.status_success)
        CheckInStatus.ALREADY_CHECKED_IN -> stringResource(R.string.status_already_done)
        CheckInStatus.LOGIN_EXPIRED -> stringResource(R.string.status_login_expired)
        CheckInStatus.FAILED -> stringResource(R.string.status_failed)
        CheckInStatus.USER_ACTION_REQUIRED -> stringResource(R.string.status_action_needed)
        CheckInStatus.RUNNING -> stringResource(R.string.status_running)
        CheckInStatus.PENDING -> stringResource(R.string.status_pending)
    }
    return StatusVisual(label, color)
}
