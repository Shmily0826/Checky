package com.checky.app.data.work

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime

/** Shared local-wall-clock calculation used by WorkManager and Settings. */
internal object AutoCheckInSchedule {
    fun nextRunDelayMillis(hour: Int, minute: Int, now: ZonedDateTime): Long =
        Duration.between(now, nextScheduledDateTime(hour, minute, now))
            .toMillis()
            .coerceAtLeast(0)

    fun nextScheduledDateTime(hour: Int, minute: Int, now: ZonedDateTime): ZonedDateTime {
        require(hour in 0..23 && minute in 0..59)
        val target = LocalTime.of(hour, minute)
        val today = ZonedDateTime.of(LocalDateTime.of(now.toLocalDate(), target), now.zone)
        return if (today.isAfter(now)) today else
            ZonedDateTime.of(LocalDateTime.of(now.toLocalDate().plusDays(1), target), now.zone)
    }
}
