package com.checky.app.domain.model

/**
 * UI-facing state for a single service during (and after) a check-in run.
 */
data class ServiceCheckInState(
    val meta: ProviderMeta,
    val status: CheckInStatus,
    /** 0f..1f */
    val progress: Float,
    val message: String,
    val reward: Reward?,
    val timestamp: Long?
) {
    val isEnabled: Boolean = true
}
