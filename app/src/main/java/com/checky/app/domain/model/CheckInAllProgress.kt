package com.checky.app.domain.model

sealed interface CheckInAllProgress {
    val states: Map<String, ServiceCheckInState>

    data class Running(override val states: Map<String, ServiceCheckInState>) : CheckInAllProgress
    data class Finished(
        override val states: Map<String, ServiceCheckInState>,
        val summary: CheckInSummary
    ) : CheckInAllProgress
}

data class CheckInSummary(
    val total: Int,
    val succeeded: Int,
    val alreadyCheckedIn: Int,
    val failed: Int,
    /** Login expired / user action required — needs attention. */
    val attention: Int,
    val totalPoints: Int,
    val totalXp: Int,
    val totalDays: Int = 0,
    val durationMs: Long
) {
    val handled: Int
        get() = succeeded + alreadyCheckedIn + failed + attention
}
