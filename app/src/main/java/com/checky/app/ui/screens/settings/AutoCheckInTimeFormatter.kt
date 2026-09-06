package com.checky.app.ui.screens.settings

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal object AutoCheckInTimeFormatter {
    fun format(hour: Int, minute: Int, now: ZonedDateTime = ZonedDateTime.now()): ScheduledTimeLabel {
        val zone = ZoneId.systemDefault()
        val localNow = now.withZoneSameInstant(zone)
        val target = LocalTime.of(hour, minute)
        val today = ZonedDateTime.of(LocalDateTime.of(localNow.toLocalDate(), target), zone)
        val occurrence = if (today.isAfter(localNow)) today else
            ZonedDateTime.of(LocalDateTime.of(localNow.toLocalDate().plusDays(1), target), zone)
        val totalMinutes = occurrence.offset.totalSeconds / 60
        val sign = if (totalMinutes < 0) "-" else "+"
        val absoluteMinutes = kotlin.math.abs(totalMinutes)
        val offset = if (absoluteMinutes % 60 == 0) {
            "$sign%02d".format(absoluteMinutes / 60)
        } else {
            "$sign%02d:%02d".format(absoluteMinutes / 60, absoluteMinutes % 60)
        }
        return ScheduledTimeLabel(
            wallClock = "%02d:%02d".format(hour, minute),
            utcOffset = offset,
            zoneId = zone.id
        )
    }
}

internal data class ScheduledTimeLabel(
    val wallClock: String,
    val utcOffset: String,
    val zoneId: String
)
