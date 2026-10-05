package com.checky.app.ui.screens.settings

import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoCheckInDiagnosticsFormatterTest {
    @Test
    fun rendersPersistedInstantWithOffsetAndZoneId() {
        val occurrence = ZonedDateTime.of(
            LocalDateTime.of(2026, 9, 6, 8, 0),
            ZoneId.of("Pacific/Auckland")
        )

        assertEquals(
            "2026-09-06 08:00:00 +12:00 Pacific/Auckland",
            AutoCheckInDiagnosticsFormatter.format(
                occurrence.toInstant().toEpochMilli(),
                occurrence.zone
            )
        )
    }

    @Test
    fun compactTimeUsesDeviceZoneAndPriorDayDoesNotMatchToday() {
        val zone = ZoneId.of("Pacific/Auckland")
        val start = ZonedDateTime.of(2026, 9, 29, 8, 57, 0, 0, zone)
        val runDay = LocalDate.of(2026, 9, 29)

        assertEquals("08:57", AutoCheckInDiagnosticsFormatter.formatTime(start.toInstant().toEpochMilli(), zone))
        assertEquals("2026-09-29", AutoCheckInDiagnosticsFormatter.formatDate(runDay))
        assertEquals(true, AutoCheckInDiagnosticsFormatter.isToday(runDay, runDay))
        assertEquals(false, AutoCheckInDiagnosticsFormatter.isToday(runDay, runDay.plusDays(1)))
    }
}
