package com.checky.app.ui.screens.settings

import java.time.LocalDateTime
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
}
