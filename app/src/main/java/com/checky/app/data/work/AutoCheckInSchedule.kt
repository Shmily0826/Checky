package com.checky.app.data.work

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.LocalDate

/** Shared local-wall-clock calculation used by WorkManager and Settings. */
internal object AutoCheckInSchedule {
    fun scheduledDateTime(
        date: LocalDate,
        hour: Int,
        minute: Int,
        now: ZonedDateTime,
        businessZones: Set<ZoneId> = emptySet()
    ): ZonedDateTime {
        require(hour in 0..23 && minute in 0..59)
        val scheduled = ZonedDateTime.of(LocalDateTime.of(date, LocalTime.of(hour, minute)), now.zone)
        return businessZones.fold(scheduled) { current, zone ->
            maxOf(current, date.atStartOfDay(zone).withZoneSameInstant(now.zone))
        }
    }

    fun nextRunDelayMillis(hour: Int, minute: Int, now: ZonedDateTime, businessZones: Set<ZoneId> = emptySet()): Long =
        Duration.between(now, nextScheduledDateTime(hour, minute, now, businessZones))
            .toMillis()
            .coerceAtLeast(0)

    fun nextScheduledDateTime(hour: Int, minute: Int, now: ZonedDateTime, businessZones: Set<ZoneId> = emptySet()): ZonedDateTime {
        require(hour in 0..23 && minute in 0..59)
        val today = scheduledDateTime(now.toLocalDate(), hour, minute, now, businessZones)
        val preferred = if (today.isAfter(now)) today else
            scheduledDateTime(now.toLocalDate().plusDays(1), hour, minute, now, businessZones)
        return preferred
    }
}
