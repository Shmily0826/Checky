package com.checky.app.ui.screens.history

import com.checky.app.data.model.CheckInRecord
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HistoryHeatmapTest {

    private val zone = ZoneId.systemDefault()
    private val today: LocalDate = LocalDate.of(2026, 8, 31)

    private fun timestamp(date: LocalDate, hour: Int = 12) =
        date.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    private fun record(
        date: LocalDate,
        status: CheckInStatus,
        serviceId: String = "svc",
        hour: Int = 12
    ) =
        CheckInRecord(
            id = "$serviceId-${date}-${status.name}",
            serviceId = serviceId,
            serviceName = serviceId,
            status = status,
            reward = Reward(RewardType.NONE, 0),
            message = "",
            diagnosticCode = "",
            durationMs = 0L,
            timestamp = timestamp(date, hour)
        )

    @Test
    fun lastTwentyEightDaysAreReturnedOldestFirst() {
        val cells = HistoryHeatmap.computeCells(emptyList(), today = today, days = 28)

        assertEquals(28, cells.size)
        assertEquals(today.minusDays(27), cells.first().date)
        assertEquals(today, cells.last().date)
        cells.forEach { assertEquals(DayQuality.EMPTY, it.quality) }
    }

    @Test
    fun dayWithOnlyPositiveResultsIsGood() {
        val records = listOf(
            record(today, CheckInStatus.SUCCESS),
            record(today, CheckInStatus.ALREADY_CHECKED_IN)
        )
        val cell = HistoryHeatmap.computeCells(records, today = today, days = 1).single()

        assertEquals(DayQuality.GOOD, cell.quality)
        assertEquals(2, cell.recordCount)
    }

    @Test
    fun dayWithAnyNegativeResultIsBad() {
        val negatives = listOf(
            CheckInStatus.FAILED,
            CheckInStatus.LOGIN_EXPIRED,
            CheckInStatus.USER_ACTION_REQUIRED
        )
        negatives.forEach { status ->
            val records = listOf(record(today, CheckInStatus.SUCCESS), record(today, status, "other"))
            val cell = HistoryHeatmap.computeCells(records, today = today, days = 1).single()
            assertEquals("status $status must mark the day bad", DayQuality.BAD, cell.quality)
        }
    }

    @Test
    fun laterSuccessForSameServiceMakesDayGood() {
        val records = listOf(
            record(today, CheckInStatus.FAILED, hour = 9),
            record(today, CheckInStatus.SUCCESS, hour = 10)
        )

        val cell = HistoryHeatmap.computeCells(records, today = today, days = 1).single()

        assertEquals(DayQuality.GOOD, cell.quality)
        assertEquals(2, cell.recordCount)
    }

    @Test
    fun latestFailureForAnotherServiceKeepsDayBad() {
        val records = listOf(
            record(today, CheckInStatus.FAILED, hour = 9),
            record(today, CheckInStatus.SUCCESS, hour = 10),
            record(today, CheckInStatus.FAILED, serviceId = "other", hour = 11)
        )

        val cell = HistoryHeatmap.computeCells(records, today = today, days = 1).single()

        assertEquals(DayQuality.BAD, cell.quality)
    }

    @Test
    fun recordsOutsideTheWindowAreIgnored() {
        val records = listOf(record(today.minusDays(40), CheckInStatus.SUCCESS))
        val cells = HistoryHeatmap.computeCells(records, today = today, days = 28)

        cells.forEach { assertEquals(DayQuality.EMPTY, it.quality) }
    }
}
