package com.checky.app.ui.screens.home

import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.ProviderMeta
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

internal fun projectHomeStatusForConnection(
    service: ServiceSnapshot,
    today: LocalDate,
    zone: ZoneId,
    connected: Boolean
): HomeStatusProjection {
    val projection = projectHomeStatus(service, today, zone)
    return if (connected && projection.hasCurrentDayResult && projection.status == CheckInStatus.LOGIN_EXPIRED) {
        HomeStatusProjection(CheckInStatus.PENDING, hasCurrentDayResult = false)
    } else {
        projection
    }
}

internal fun projectHomeStatusForConnection(
    service: ServiceSnapshot,
    meta: com.checky.app.domain.model.ProviderMeta,
    now: Instant,
    connected: Boolean
): HomeStatusProjection = projectHomeStatusForConnection(
    service,
    now.atZone(meta.businessZone).toLocalDate(),
    meta.businessZone,
    connected
)

internal fun isTimestampOnBusinessDate(timestamp: Long?, now: Instant, zone: ZoneId): Boolean =
    timestamp != null && Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate()

internal fun isLiveStateForDate(state: ServiceCheckInState, today: LocalDate, zone: ZoneId, runDate: LocalDate?): Boolean =
    isTimestampOnDate(state.timestamp, today, zone) ||
        (state.status == CheckInStatus.RUNNING && state.timestamp == null && runDate == today)

internal fun isLiveStateForBusinessDate(state: ServiceCheckInState, now: Instant, runStartedAt: Instant?): Boolean =
    isTimestampOnBusinessDate(state.timestamp, now, state.meta.businessZone) ||
        (state.status == CheckInStatus.RUNNING && state.timestamp == null &&
            runStartedAt?.atZone(state.meta.businessZone)?.toLocalDate() == now.atZone(state.meta.businessZone).toLocalDate())

internal fun countCurrentDoneServices(
    eligibleServices: List<ServiceSnapshot>,
    metaById: Map<String, ProviderMeta>,
    liveStates: Map<String, ServiceCheckInState>?,
    now: Instant,
    runStartedAt: Instant?
): Int {
    val eligibleIds = eligibleServices.mapTo(mutableSetOf()) { it.serviceId }
    val doneIds = eligibleServices.mapNotNullTo(mutableSetOf()) { service ->
        val meta = metaById[service.serviceId] ?: return@mapNotNullTo null
        service.serviceId.takeIf {
            projectHomeStatusForConnection(service, meta, now, connected = true).status.isTerminal
        }
    }
    liveStates.orEmpty().forEach { (id, state) ->
        if (id in eligibleIds &&
            isLiveStateForBusinessDate(state, now, runStartedAt) &&
            state.status.isTerminal
        ) {
            doneIds += id
        }
    }
    return doneIds.size
}

internal fun hasCurrentLiveProgress(progress: CheckInAllProgress?, today: LocalDate, zone: ZoneId, runDate: LocalDate?): Boolean =
    progress != null && (runDate == today || progress.states.values.any { isTimestampOnDate(it.timestamp, today, zone) })

internal fun hasCurrentLiveBusinessProgress(progress: CheckInAllProgress?, now: Instant, runStartedAt: Instant?): Boolean =
    progress != null && progress.states.values.any { isLiveStateForBusinessDate(it, now, runStartedAt) }
