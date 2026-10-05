package com.checky.app.ui.screens.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Renders persisted instants with the current local offset and zone ID. */
internal object AutoCheckInDiagnosticsFormatter {
    private val formatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss xxx VV", Locale.ROOT)
    private val compactTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val compactDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    fun format(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun formatTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        compactTimeFormatter.withLocale(Locale.getDefault()).format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun formatDate(date: java.time.LocalDate): String = compactDateFormatter.format(date)

    fun isToday(runDay: java.time.LocalDate, today: java.time.LocalDate): Boolean = runDay == today
}
