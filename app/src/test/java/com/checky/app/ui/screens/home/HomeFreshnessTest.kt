package com.checky.app.ui.screens.home

import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.CheckInSummary
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class HomeFreshnessTest {

    private val zone = ZoneId.of("Pacific/Auckland")
    private val yesterday = LocalDate.of(2026, 9, 3)
    private val today = yesterday.plusDays(1)

    @Test
    fun yesterdayPositiveResultProjectsToTodayAsNeutral() {
        val projection = projectHomeStatus(
            service(CheckInStatus.SUCCESS, yesterday), today, zone
        )

        assertEquals(CheckInStatus.PENDING, projection.status)
        assertFalse(projection.hasCurrentDayResult)
    }

    @Test
    fun bothPositiveResultsRemainTodayScopedAcrossMidnight() {
        listOf(CheckInStatus.SUCCESS, CheckInStatus.ALREADY_CHECKED_IN).forEach { status ->
            val projection = projectHomeStatus(service(status, today), today, zone)
            assertEquals(status, projection.status)
            assertTrue(projection.hasCurrentDayResult)
        }
    }

    @Test
    fun runningWithoutTerminalTimestampIsCurrentOnlyForItsRunDate() {
        val running = com.checky.app.domain.model.ServiceCheckInState(
            meta = com.checky.app.domain.model.ProviderMeta(
                id = "id",
                displayName = "name",
                description = "",
                category = "",
                iconKey = "",
                accentColor = 0L,
                isEnabledByDefault = true
            ),
            status = CheckInStatus.RUNNING,
            progress = 0.5f,
            message = "running",
            reward = null,
            timestamp = null
        )

        assertTrue(isLiveStateForDate(running, today, zone, today))
        assertFalse(isLiveStateForDate(running, today, zone, yesterday))
    }

    @Test
    fun staleLiveProgressDoesNotReplacePersistedTodayProjection() {
        val state = com.checky.app.domain.model.ServiceCheckInState(
            meta = ProviderMeta(
                id = "id",
                displayName = "name",
                description = "",
                category = "",
                iconKey = "",
                accentColor = 0L,
                isEnabledByDefault = true
            ),
            status = CheckInStatus.SUCCESS,
            progress = 1f,
            message = "yesterday",
            reward = Reward.empty(),
            timestamp = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        val finished = CheckInAllProgress.Finished(
            states = mapOf("id" to state),
            summary = CheckInSummary(1, 1, 0, 0, 0, 0, 0, durationMs = 0L)
        )

        assertFalse(hasCurrentLiveProgress(finished, today, zone, yesterday))
        assertFalse(hasCurrentLiveProgress(finished, today, zone, null))
    }

    private fun service(status: CheckInStatus, date: LocalDate) = ServiceSnapshot(
        serviceId = "service",
        displayName = "service",
        isEnabled = true,
        lastStatus = status,
        lastReward = null,
        lastMessage = "result",
        lastTimestamp = date.atStartOfDay(zone).plusHours(12).toInstant().toEpochMilli()
    )
}
