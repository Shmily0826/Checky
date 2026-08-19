package com.checky.app.data.model

import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.Reward

/**
 * UI-facing history entry mapped from the Room entity.
 */
data class CheckInRecord(
    val id: String,
    val serviceId: String,
    val serviceName: String,
    val status: CheckInStatus,
    val reward: Reward,
    val message: String,
    /** Non-sensitive error category (empty for success). */
    val diagnosticCode: String,
    /** Wall-clock duration of the attempt in ms. */
    val durationMs: Long,
    val timestamp: Long
)
