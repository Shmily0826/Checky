package com.checky.app.ui.screens.home

import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.ServiceCheckInState
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class HomeStatusProjection(
    val status: CheckInStatus,
    val hasCurrentDayResult: Boolean
)

internal fun isTimestampOnDate(timestamp: Long?, date: LocalDate, zone: ZoneId): Boolean =
    timestamp != null && Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate() == date

internal fun projectHomeStatus(service: ServiceSnapshot, today: LocalDate, zone: ZoneId): HomeStatusProjection {
    val current = service.lastStatus != null && isTimestampOnDate(service.lastTimestamp, today, zone)
    return HomeStatusProjection(if (current) service.lastStatus!! else CheckInStatus.PENDING, current)
}

internal fun projectHomeStatus(
    service: ServiceSnapshot,
    meta: com.checky.app.domain.model.ProviderMeta,
    now: Instant
): HomeStatusProjection = projectHomeStatus(service, now.atZone(meta.businessZone).toLocalDate(), meta.businessZone)

internal fun isTimestampOnBusinessDate(timestamp: Long?, now: Instant, zone: ZoneId): Boolean =
    timestamp != null && Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()

internal fun isLiveStateForDate(state: ServiceCheckInState, today: LocalDate, zone: ZoneId, runDate: LocalDate?): Boolean =
    isTimestampOnDate(state.timestamp, today, zone) ||
        (state.status == CheckInStatus.RUNNING && state.timestamp == null && runDate == today)

internal fun isLiveStateForBusinessDate(state: ServiceCheckInState, now: Instant, runStartedAt: Instant?): Boolean =
    isTimestampOnBusinessDate(state.timestamp, now, state.meta.businessZone) ||
        (state.status == CheckInStatus.RUNNING && state.timestamp == null &&
            runStartedAt?.atZone(state.meta.businessZone)?.toLocalDate() == now.atZone(state.meta.businessZone).toLocalDate())

internal fun hasCurrentLiveProgress(progress: CheckInAllProgress?, today: LocalDate, zone: ZoneId, runDate: LocalDate?): Boolean =
    progress != null && (runDate == today || progress.states.values.any { isTimestampOnDate(it.timestamp, today, zone) })

internal fun hasCurrentLiveBusinessProgress(progress: CheckInAllProgress?, now: Instant, runStartedAt: Instant?): Boolean =
    progress != null && progress.states.values.any { isLiveStateForBusinessDate(it, now, runStartedAt) }
