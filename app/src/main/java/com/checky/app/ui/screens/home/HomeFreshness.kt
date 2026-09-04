package com.checky.app.ui.screens.home

import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.ServiceCheckInState
import com.checky.app.domain.model.CheckInAllProgress
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Projects persisted service state onto the day the Home screen represents.
 * A status without a timestamp from that day is historical and must not be
 * presented as today's check-in state.
 */
internal data class HomeStatusProjection(
    val status: CheckInStatus,
    val hasCurrentDayResult: Boolean
)

internal fun isTimestampOnDate(
    timestamp: Long?,
    date: LocalDate,
    zone: ZoneId
): Boolean = timestamp != null &&
    Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate() == date

internal fun projectHomeStatus(
    service: ServiceSnapshot,
    today: LocalDate,
    zone: ZoneId
): HomeStatusProjection {
    val hasCurrentDayResult = service.lastStatus != null &&
        isTimestampOnDate(service.lastTimestamp, today, zone)
    return HomeStatusProjection(
        status = if (hasCurrentDayResult) service.lastStatus!! else CheckInStatus.PENDING,
        hasCurrentDayResult = hasCurrentDayResult
    )
}

internal fun isLiveStateForDate(
    state: ServiceCheckInState,
    today: LocalDate,
    zone: ZoneId,
    runDate: LocalDate?
): Boolean = isTimestampOnDate(state.timestamp, today, zone) ||
    (state.status == CheckInStatus.RUNNING && state.timestamp == null && runDate == today)

internal fun hasCurrentLiveProgress(
    progress: CheckInAllProgress?,
    today: LocalDate,
    zone: ZoneId,
    runDate: LocalDate?
): Boolean = progress != null && (runDate == today || progress.states.values.any {
    isTimestampOnDate(it.timestamp, today, zone)
})
