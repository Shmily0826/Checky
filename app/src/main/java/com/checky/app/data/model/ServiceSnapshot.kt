package com.checky.app.data.model

import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.Reward

/**
 * UI-facing snapshot of a service, combining static provider metadata with
 * persisted runtime state (enabled flag + last check-in result).
 */
data class ServiceSnapshot(
    val serviceId: String,
    val displayName: String,
    val isEnabled: Boolean,
    val lastStatus: CheckInStatus?,
    val lastReward: Reward?,
    val lastMessage: String?,
    val lastTimestamp: Long?
)
