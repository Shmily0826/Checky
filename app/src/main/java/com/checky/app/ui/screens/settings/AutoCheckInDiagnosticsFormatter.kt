package com.checky.app.ui.screens.settings

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Renders persisted instants with the current local offset and zone ID. */
internal object AutoCheckInDiagnosticsFormatter {
    private val formatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss xxx VV", Locale.ROOT)

    fun format(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        formatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))
}
