package com.checky.app.data.work

import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.FakeAuthHealthStore
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.SequenceCheckInProvider
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.Reward
import com.checky.app.domain.testProviderMeta
import com.checky.app.data.preferences.UserPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.work.Data
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AutoCheckInWorkerTest {
    @Test
    fun onlySchedulerInputEnablesDailySelfReschedule() {
        assertEquals(true, isDailyScheduled(Data.Builder().putBoolean("daily_scheduled", true).build()))
        assertEquals(false, isDailyScheduled(Data.EMPTY))
    }

    @Test
    fun runningWorkDefersReplacementButPendingWorkMayBeReplaced() {
        assertEquals(true, shouldDeferScheduleReplacement(listOf(androidx.work.WorkInfo.State.RUNNING)))
        assertEquals(false, shouldDeferScheduleReplacement(listOf(androidx.work.WorkInfo.State.ENQUEUED)))
        assertEquals(false, shouldDeferScheduleReplacement(emptyList()))
    }
    @Test
    fun nextRunUsesLocalWallClockAndRollsToTomorrow() {
        val zone = ZoneId.of("Pacific/Auckland")
        val before = ZonedDateTime.of(LocalDateTime.of(2026, 9, 6, 7, 59), zone)
        val after = ZonedDateTime.of(LocalDateTime.of(2026, 9, 6, 8, 1), zone)

        assertEquals(60_000, AutoCheckInWorker.nextRunDelayMillis(8, 0, before))
        assertEquals(
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 7, 8, 0), zone),
            AutoCheckInWorker.nextScheduledDateTime(8, 0, after)
        )
    }

    @Test
    fun nextRunUsesDstAwareDuration() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(LocalDateTime.of(2026, 3, 8, 1, 0), zone)
        val next = AutoCheckInWorker.nextScheduledDateTime(8, 0, now)

        assertEquals(LocalDateTime.of(2026, 3, 8, 8, 0), next.toLocalDateTime())
        assertEquals("-04:00", next.offset.toString())
        assertEquals(6 * 60 * 60 * 1000L, AutoCheckInWorker.nextRunDelayMillis(8, 0, now))
    }

    @Test
    fun disabledOptInStopsQueuedWorkerBeforeProviderSelection() {
        assertEquals(false, shouldRunAutoCheckIn(UserPreferences(autoCheckInEnabled = false)))
        assertEquals(true, shouldRunAutoCheckIn(UserPreferences(autoCheckInEnabled = true)))
    }

    @Test
    fun selectionRequiresEnabledAndConnectedProvider() = runTest {
        val auth = SequenceCheckInProvider(
            testProviderMeta("auth").copy(credentialType = CredentialType.SESSION_TOKEN),
            CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val selectedFree = SequenceCheckInProvider(
            testProviderMeta("free").copy(credentialType = CredentialType.NONE),
            CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val unselectedAuth = SequenceCheckInProvider(
            testProviderMeta("unselected").copy(credentialType = CredentialType.SESSION_TOKEN),
            CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val credentials = FakeCredentialStore()
        val health = FakeAuthHealthStore()
        val providers = listOf(auth, selectedFree, unselectedAuth)

        assertEquals(
            listOf("free"),
            selectExecutableProviders(setOf("auth", "free"), providers, credentials, health).map { it.meta.id }
        )

        credentials.save("auth", "synthetic-test-secret")
        health.set("auth", AuthHealth.VALID)
        assertEquals(
            listOf("auth", "free"),
            selectExecutableProviders(setOf("auth", "free"), providers, credentials, health).map { it.meta.id }
        )
        assertEquals(
            listOf("auth"),
            selectExecutableProviders(setOf("auth"), providers, credentials, health).map { it.meta.id }
        )
    }
}
