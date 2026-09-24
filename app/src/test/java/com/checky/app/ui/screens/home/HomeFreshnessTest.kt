package com.checky.app.ui.screens.home

import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.CheckInSummary
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.ServiceCheckInState
import com.checky.app.domain.AuthHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

class HomeFreshnessTest {

    private val zone = ZoneId.of("Pacific/Auckland")
    private val yesterday = LocalDate.of(2026, 9, 3)
    private val today = yesterday.plusDays(1)
    private val shanghai = ZoneId.of("Asia/Shanghai")

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
    fun connectedSameDayLoginExpiredProjectsToPendingWithoutCurrentResult() {
        val projection = projectHomeStatusForConnection(
            service(CheckInStatus.LOGIN_EXPIRED, today), today, zone, connected = true
        )

        assertEquals(CheckInStatus.PENDING, projection.status)
        assertFalse(projection.hasCurrentDayResult)
    }

    @Test
    fun disconnectedSameDayLoginExpiredRemainsExpired() {
        val projection = projectHomeStatusForConnection(
            service(CheckInStatus.LOGIN_EXPIRED, today), today, zone, connected = false
        )

        assertEquals(CheckInStatus.LOGIN_EXPIRED, projection.status)
        assertTrue(projection.hasCurrentDayResult)
    }

    @Test
    fun connectedSameDaySuccessRemainsSuccess() {
        val projection = projectHomeStatusForConnection(
            service(CheckInStatus.SUCCESS, today), today, zone, connected = true
        )

        assertEquals(CheckInStatus.SUCCESS, projection.status)
        assertTrue(projection.hasCurrentDayResult)
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

    @Test
    fun singleServiceRunKeepsEarlierTodayCompletionsInTopProgress() {
        val now = today.atTime(12, 0).atZone(zone).toInstant()
        val ids = (1..5).map { "service-$it" }
        val services = ids.mapIndexed { index, id ->
            progressService(id, if (index < 4) CheckInStatus.SUCCESS else null, if (index < 4) today else null)
        }
        val metas = ids.associateWith(::progressMeta)
        val runStartedAt = now
        val live = mapOf(ids.last() to liveState(metas.getValue(ids.last()), CheckInStatus.RUNNING))

        assertEquals(5, services.size)
        assertEquals(4, countCurrentDoneServices(services, metas, live, now, runStartedAt))
    }

    @Test
    fun batchProgressUnionsPersistedAndLiveCompletionsWithoutDoubleCounting() {
        val now = today.atTime(12, 0).atZone(zone).toInstant()
        val ids = (1..5).map { "service-$it" }
        val services = ids.mapIndexed { index, id ->
            progressService(id, if (index < 4) CheckInStatus.SUCCESS else null, if (index < 4) today else null)
        }
        val metas = ids.associateWith(::progressMeta)
        val runningBatch = ids.associateWith { id ->
            liveState(metas.getValue(id), if (id == ids.last()) CheckInStatus.PENDING else CheckInStatus.RUNNING)
        }

        assertEquals(4, countCurrentDoneServices(services, metas, runningBatch, now, now))

        val finishedBatch = runningBatch + (ids.last() to liveState(
            metas.getValue(ids.last()), CheckInStatus.SUCCESS, now
        ))
        assertEquals(5, countCurrentDoneServices(services, metas, finishedBatch, now, now))
    }

    @Test
    fun progressStartsAtZeroAndCountsCurrentRunUpdates() {
        val now = today.atTime(12, 0).atZone(zone).toInstant()
        val ids = (1..5).map { "service-$it" }
        val services = ids.map { progressService(it, null, null) }
        val metas = ids.associateWith(::progressMeta)
        val initial = ids.associateWith { id -> liveState(metas.getValue(id), CheckInStatus.PENDING) }

        assertEquals(0, countCurrentDoneServices(services, metas, initial, now, now))
        val updated = initial + (ids.first() to liveState(metas.getValue(ids.first()), CheckInStatus.SUCCESS, now))
        assertEquals(1, countCurrentDoneServices(services, metas, updated, now, now))
        val final = ids.associateWith { id -> liveState(metas.getValue(id), CheckInStatus.SUCCESS, now) }
        assertEquals(5, countCurrentDoneServices(services, metas, final, now, now))
    }

    @Test
    fun previousBusinessDayCompletionsDoNotSeedTodayProgress() {
        val now = today.atStartOfDay(zone).plusMinutes(1).toInstant()
        val ids = (1..5).map { "service-$it" }
        val services = ids.mapIndexed { index, id ->
            progressService(id, if (index < 4) CheckInStatus.SUCCESS else null, if (index < 4) yesterday else null)
        }
        val metas = ids.associateWith(::progressMeta)
        val yesterdayStart = yesterday.atTime(23, 59).atZone(zone).toInstant()
        val staleLive = ids.take(4).associateWith { id ->
            liveState(metas.getValue(id), CheckInStatus.SUCCESS, yesterdayStart)
        } + (ids.last() to liveState(metas.getValue(ids.last()), CheckInStatus.RUNNING))

        assertEquals(0, countCurrentDoneServices(services, metas, staleLive, now, yesterdayStart))
    }

    @Test
    fun businessDateDoesNotUseAucklandNominalDate() {
        val now = Instant.parse("2026-09-06T12:30:00Z") // Auckland Sep 7 00:30, Shanghai Sep 6 20:30
        val meta = com.checky.app.domain.testProviderMeta("shanghai").copy(businessZone = shanghai)
        val timestamp = Instant.parse("2026-09-06T11:30:00Z").toEpochMilli()
        val service = ServiceSnapshot("shanghai", "service", true, CheckInStatus.SUCCESS, null, "ok", timestamp)

        val projection = projectHomeStatus(service, meta, now)

        assertTrue(projection.hasCurrentDayResult)
        assertEquals(CheckInStatus.SUCCESS, projection.status)
    }

    @Test
    fun previousShanghaiDayIsStaleImmediatelyAfterShanghaiMidnight() {
        val now = Instant.parse("2026-09-05T16:01:00Z")
        val meta = com.checky.app.domain.testProviderMeta("shanghai").copy(businessZone = shanghai)
        val previous = Instant.parse("2026-09-05T15:59:00Z").toEpochMilli()
        val current = Instant.parse("2026-09-05T16:00:30Z").toEpochMilli()

        assertFalse(projectHomeStatus(ServiceSnapshot("p", "p", true, CheckInStatus.SUCCESS, null, "", previous), meta, now).hasCurrentDayResult)
        assertTrue(projectHomeStatus(ServiceSnapshot("p", "p", true, CheckInStatus.SUCCESS, null, "", current), meta, now).hasCurrentDayResult)
    }

    @Test
    fun connectedPendingCardGetsCheckInActionButPositiveCardDoesNot() {
        assertTrue(isOrdinaryPendingCard(CheckInStatus.PENDING, connected = true, needsVerification = false))
        listOf(CheckInStatus.SUCCESS, CheckInStatus.ALREADY_CHECKED_IN).forEach { status ->
            assertFalse(isOrdinaryPendingCard(status, connected = true, needsVerification = false))
        }
    }

    @Test
    fun connectedValidStaleExpiredResultOffersSignInRetry() {
        assertTrue(
            staleLoginExpiredCanRetry(
                connected = true,
                authHealth = AuthHealth.VALID,
                lastStatus = CheckInStatus.LOGIN_EXPIRED
            )
        )
        listOf(
            Triple(false, AuthHealth.VALID, CheckInStatus.LOGIN_EXPIRED),
            Triple(true, AuthHealth.EXPIRED, CheckInStatus.LOGIN_EXPIRED),
            Triple(true, AuthHealth.UNVERIFIED, CheckInStatus.LOGIN_EXPIRED),
            Triple(true, null, CheckInStatus.LOGIN_EXPIRED),
            Triple(true, AuthHealth.VALID, CheckInStatus.SUCCESS),
            Triple(true, AuthHealth.VALID, CheckInStatus.PENDING)
        ).forEach { (connected, authHealth, lastStatus) ->
            assertFalse(staleLoginExpiredCanRetry(connected, authHealth, lastStatus))
        }
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

    private fun progressService(id: String, status: CheckInStatus?, date: LocalDate?) = ServiceSnapshot(
        serviceId = id,
        displayName = id,
        isEnabled = true,
        lastStatus = status,
        lastReward = null,
        lastMessage = null,
        lastTimestamp = date?.atStartOfDay(zone)?.plusHours(12)?.toInstant()?.toEpochMilli()
    )

    private fun progressMeta(id: String) = com.checky.app.domain.testProviderMeta(id).copy(businessZone = zone)

    private fun liveState(
        meta: ProviderMeta,
        status: CheckInStatus,
        timestamp: Instant? = null
    ) = ServiceCheckInState(
        meta = meta,
        status = status,
        progress = if (status == CheckInStatus.RUNNING) 0.5f else 1f,
        message = "state",
        reward = null,
        timestamp = timestamp?.toEpochMilli()
    )
}
