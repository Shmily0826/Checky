package com.checky.app.ui.screens.settings

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class AutoCheckInTimeFormatterTest {
    private lateinit var originalZone: TimeZone

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun formatsQuarterHourLocalOffset() {
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Chatham"))
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 1, 1, 7, 0), ZoneId.of("Pacific/Chatham"))

        assertEquals(
            ScheduledTimeLabel("08:00", "+13:45", "Pacific/Chatham"),
            AutoCheckInTimeFormatter.format(8, 0, now)
        )
    }

    @Test
    fun formatsScheduledOccurrenceOffsetAfterDstTransition() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 3, 7, 23, 0), ZoneId.of("America/New_York"))

        assertEquals(
            ScheduledTimeLabel("08:00", "-04", "America/New_York"),
            AutoCheckInTimeFormatter.format(8, 0, now)
        )
    }
}
