package com.checky.app.domain.model

/**
 * Lifecycle status of a single check-in attempt.
 *
 * These mirror the statuses required by the product brief:
 * Pending, Running, Success, Already checked in, Login expired, Failed, User action required.
 */
enum class CheckInStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    ALREADY_CHECKED_IN,
    LOGIN_EXPIRED,
    FAILED,
    USER_ACTION_REQUIRED;

    val isTerminal: Boolean
        get() = this != PENDING && this != RUNNING

    val requiresUserAction: Boolean
        get() = this == LOGIN_EXPIRED || this == USER_ACTION_REQUIRED || this == FAILED

    val isPositive: Boolean
        get() = this == SUCCESS || this == ALREADY_CHECKED_IN
}
