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

@Composable
fun statusVisual(status: CheckInStatus): StatusVisual {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        CheckInStatus.SUCCESS ->
            StatusVisual(stringResource(R.string.status_success), scheme.tertiary)
        CheckInStatus.ALREADY_CHECKED_IN ->
            StatusVisual(stringResource(R.string.status_already_done), scheme.primary)
        CheckInStatus.LOGIN_EXPIRED ->
            StatusVisual(stringResource(R.string.status_login_expired), Color(0xFFE0A000))
        CheckInStatus.FAILED ->
            StatusVisual(stringResource(R.string.status_failed), scheme.error)
        CheckInStatus.USER_ACTION_REQUIRED ->
            StatusVisual(stringResource(R.string.status_action_needed), Color(0xFFE0730C))
        CheckInStatus.RUNNING ->
            StatusVisual(stringResource(R.string.status_running), scheme.primary)
        CheckInStatus.PENDING ->
            StatusVisual(stringResource(R.string.status_pending), scheme.outline)
    }
}
