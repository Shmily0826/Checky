package com.checky.app.ui.screens.history

import com.checky.app.data.model.CheckInRecord
import com.checky.app.domain.model.CheckInStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Derives the daily check-in heatmap shown on the history screen.
 * Pure logic, unit-testable without Android classes.
 */
enum class DayQuality { EMPTY, GOOD, BAD }

data class DayCell(val date: LocalDate, val quality: DayQuality, val recordCount: Int)

object HistoryHeatmap {

    /** Aggregates the last [days] days (oldest first) into per-day qualities. */
    fun computeCells(
        records: List<CheckInRecord>,
        today: LocalDate,
        days: Int = 28,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<DayCell> {
        val byDate = records.groupBy { record ->
            Instant.ofEpochMilli(record.timestamp).atZone(zone).toLocalDate()
        }
        val startDate = today.minusDays((days - 1).toLong())
        return (0 until days).map { offset ->
            val date = startDate.plusDays(offset.toLong())
            val dayRecords = byDate[date].orEmpty()
            val latestByService = dayRecords
                .groupBy { it.serviceId }
                .values
                .map { serviceRecords -> serviceRecords.maxBy { it.timestamp } }
            val quality = when {
                latestByService.isEmpty() -> DayQuality.EMPTY
                latestByService.any {
                    it.status != CheckInStatus.SUCCESS && it.status != CheckInStatus.ALREADY_CHECKED_IN
                } ->
                    DayQuality.BAD
                else -> DayQuality.GOOD
            }
            DayCell(date, quality, dayRecords.size)
        }
    }
}
