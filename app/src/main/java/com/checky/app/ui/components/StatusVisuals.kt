package com.checky.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
            StatusVisual("Success", scheme.tertiary)
        CheckInStatus.ALREADY_CHECKED_IN ->
            StatusVisual("Already done", scheme.primary)
        CheckInStatus.LOGIN_EXPIRED ->
            StatusVisual("Login expired", Color(0xFFE0A000))
        CheckInStatus.FAILED ->
            StatusVisual("Failed", scheme.error)
        CheckInStatus.USER_ACTION_REQUIRED ->
            StatusVisual("Action needed", Color(0xFFE0730C))
        CheckInStatus.RUNNING ->
            StatusVisual("Running…", scheme.primary)
        CheckInStatus.PENDING ->
            StatusVisual("Pending", scheme.outline)
    }
}
