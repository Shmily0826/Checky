package com.checky.app.data.work

import androidx.test.core.app.ApplicationProvider
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.FakeAuthHealthStore
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.SequenceCheckInProvider
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.CheckInSummary
import com.checky.app.domain.model.CredentialType
import com.checky.app.domain.model.Reward
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.data.preferences.AutoCheckInDiagnosticOutcome
import com.checky.app.data.preferences.AutoCheckInDiagnostics
import com.checky.app.data.preferences.AutoCheckInDiagnosticsStore
import com.checky.app.domain.testProviderMeta
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.domain.providers.TaygedoClient
import com.checky.app.domain.providers.TaygedoCommunityProvider
import com.checky.app.domain.providers.TaygedoNteProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.work.Data
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
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
    fun businessMidnightClampsAucklandOneAmInNzst() {
        val zone = ZoneId.of("Pacific/Auckland")
        val now = ZonedDateTime.of(2026, 6, 6, 0, 30, 0, 0, zone)
        val next = AutoCheckInWorker.nextScheduledDateTime(1, 0, now, setOf(ZoneId.of("Asia/Shanghai")))
        assertEquals(ZonedDateTime.of(2026, 6, 6, 4, 0, 0, 0, zone), next)
    }

    @Test
    fun businessMidnightClampsAucklandOneAmInNzdt() {
        val zone = ZoneId.of("Pacific/Auckland")
        val now = ZonedDateTime.of(2026, 1, 6, 0, 30, 0, 0, zone)
        val next = AutoCheckInWorker.nextScheduledDateTime(1, 0, now, setOf(ZoneId.of("Asia/Shanghai")))
        assertEquals(ZonedDateTime.of(2026, 1, 6, 5, 0, 0, 0, zone), next)
    }

    @Test
    fun eightAmAucklandRemainsEightAmWhenItStartsNewShanghaiDate() {
        val zone = ZoneId.of("Pacific/Auckland")
        val now = ZonedDateTime.of(2026, 6, 6, 7, 30, 0, 0, zone)
        val next = AutoCheckInWorker.nextScheduledDateTime(8, 0, now, setOf(ZoneId.of("Asia/Shanghai")))
        assertEquals(ZonedDateTime.of(2026, 6, 6, 8, 0, 0, 0, zone), next)
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

    @Test
    fun taygedoValidRevalidatesBeforeSelection() = runTest {
        val fixture = taygedoSelectionFixture()
        fixture.health.set(TaygedoClient.SESSION_KEY, AuthHealth.VALID)

        assertEquals(
            listOf(fixture.nte),
            selectExecutableProviders(
                setOf(fixture.nte.meta.id),
                listOf(fixture.nte),
                fixture.credentials,
                fixture.health
            )
        )
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun taygedoValidFailedPreflightIsExcluded() = runTest {
        val fixture = taygedoSelectionFixture(503 to "")
        fixture.health.set(TaygedoClient.SESSION_KEY, AuthHealth.VALID)

        assertEquals(
            emptyList<TaygedoNteProvider>(),
            selectExecutableProviders(
                setOf(fixture.nte.meta.id),
                listOf(fixture.nte),
                fixture.credentials,
                fixture.health
            )
        )
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun sharedTaygedoOwnerUsesOnePreflightDecisionForBothServices() = runTest {
        val success = taygedoSelectionFixture()
        success.health.set(TaygedoClient.SESSION_KEY, AuthHealth.VALID)
        assertEquals(
            listOf(success.nte, success.community),
            selectExecutableProviders(
                setOf(success.nte.meta.id, success.community.meta.id),
                listOf(success.nte, success.community),
                success.credentials,
                success.health
            )
        )
        assertEquals(1, success.requests.size)

        val failure = taygedoSelectionFixture(503 to "")
        failure.health.set(TaygedoClient.SESSION_KEY, AuthHealth.VALID)
        assertEquals(
            emptyList<TaygedoNteProvider>(),
            selectExecutableProviders(
                setOf(failure.nte.meta.id, failure.community.meta.id),
                listOf(failure.nte, failure.community),
                failure.credentials,
                failure.health
            )
        )
        assertEquals(1, failure.requests.size)
    }

    @Test
    fun expiredTaygedoRecoveryStillSelectsAfterRefresh() = runTest {
        val fixture = taygedoSelectionFixture(
            200 to "{\"code\":401}",
            200 to "{\"code\":0,\"data\":{\"accessToken\":\"rotated-access\",\"refreshToken\":\"rotated-refresh\"}}",
            200 to "{\"code\":0,\"data\":{},\"msg\":\"ok\",\"ok\":true}"
        )
        fixture.health.set(TaygedoClient.SESSION_KEY, AuthHealth.EXPIRED)

        assertEquals(
            listOf(fixture.nte),
            selectExecutableProviders(
                setOf(fixture.nte.meta.id),
                listOf(fixture.nte),
                fixture.credentials,
                fixture.health
            )
        )
        assertEquals(AuthHealth.VALID, fixture.health.get(TaygedoClient.SESSION_KEY))
        assertEquals(3, fixture.requests.size)
    }

    @Test
    fun dailyWorkerRecordsDisabledTerminalOutcome() = runTest {
        val store = RecordingDiagnosticsStore()

        val result = runWithDailyDiagnostics(true, store, nowMillis = { 100L }) {
            executeAutoCheckIn(
                preferences = { UserPreferences(autoCheckInEnabled = false) },
                services = { error("disabled runs must not inspect services") },
                providers = emptyList(),
                credentials = FakeCredentialStore(),
                authHealthStore = FakeAuthHealthStore(),
                runBatch = { error("disabled runs must not execute providers") }
            )
        }

        assertEquals(AutoCheckInExecutionResult.Disabled, result)
        assertEquals(100L, store.value.lastStartEpochMillis)
        assertEquals(AutoCheckInDiagnosticOutcome.SKIPPED_DISABLED, store.value.lastOutcome)
        assertEquals(100L, store.value.lastFinishEpochMillis)
    }

    @Test
    fun dailyWorkerRecordsNoEligibleProviderTerminalOutcome() = runTest {
        val store = RecordingDiagnosticsStore()

        val result = runWithDailyDiagnostics(true, store, nowMillis = { 200L }) {
            executeAutoCheckIn(
                preferences = { UserPreferences(autoCheckInEnabled = true) },
                services = { listOf(service("missing")) },
                providers = listOf(
                    SequenceCheckInProvider(
                        testProviderMeta("missing").copy(credentialType = CredentialType.SESSION_TOKEN),
                        CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
                    )
                ),
                credentials = FakeCredentialStore(),
                authHealthStore = FakeAuthHealthStore(),
                runBatch = { error("no eligible providers must not execute a batch") }
            )
        }

        assertEquals(AutoCheckInExecutionResult.NoEligibleProvider, result)
        assertEquals(AutoCheckInDiagnosticOutcome.SKIPPED_NO_ELIGIBLE_PROVIDER, store.value.lastOutcome)
        assertEquals(200L, store.value.lastFinishEpochMillis)
    }

    @Test
    fun dailyWorkerRecordsCompletedTerminalOutcomeFromTypedSummary() = runTest {
        val store = RecordingDiagnosticsStore()
        val provider = SequenceCheckInProvider(
            testProviderMeta("free"),
            CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val summary = CheckInSummary(
            total = 1,
            succeeded = 1,
            alreadyCheckedIn = 0,
            failed = 0,
            attention = 0,
            totalPoints = 0,
            totalXp = 0,
            durationMs = 4
        )

        val result = runWithDailyDiagnostics(true, store, nowMillis = { 300L }) {
            executeAutoCheckIn(
                preferences = { UserPreferences(autoCheckInEnabled = true) },
                services = { listOf(service("free")) },
                providers = listOf(provider),
                credentials = FakeCredentialStore(),
                authHealthStore = FakeAuthHealthStore(),
                runBatch = { selected ->
                    assertEquals(listOf(provider), selected)
                    CheckInAllProgress.Finished(emptyMap(), summary)
                }
            )
        }

        assertEquals(true, result is AutoCheckInExecutionResult.Completed)
        assertEquals(AutoCheckInDiagnosticOutcome.COMPLETED, store.value.lastOutcome)
        assertEquals(1, store.value.completedTotal)
        assertEquals(1, store.value.completedSucceeded)
    }

    @Test
    fun dailyWorkerRecordsOrdinaryInternalFailureWithoutRawException() = runTest {
        val store = RecordingDiagnosticsStore()

        val result = runWithDailyDiagnostics(true, store, nowMillis = { 400L }) {
            throw IllegalStateException("secret provider response must not persist")
        }

        assertEquals(AutoCheckInExecutionResult.FailedInternal, result)
        assertEquals(AutoCheckInDiagnosticOutcome.FAILED_INTERNAL, store.value.lastOutcome)
        assertEquals(400L, store.value.lastFinishEpochMillis)
    }

    @Test
    fun manualOrWidgetInputDoesNotUpdateDailyDiagnostics() = runTest {
        val store = RecordingDiagnosticsStore()
        val before = store.value

        runWithDailyDiagnostics(isDailyScheduled(Data.EMPTY), store, nowMillis = { 500L }) {
            AutoCheckInExecutionResult.Disabled
        }

        assertEquals(before, store.value)
    }

    @Test
    fun cancellationIsNotConvertedToInternalFailure() = runTest {
        val store = RecordingDiagnosticsStore()

        try {
            runWithDailyDiagnostics(true, store, nowMillis = { 600L }) {
                throw CancellationException("cancelled")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: the terminal outcome remains unknown.
        }

        assertNull(store.value.lastOutcome)
        assertNull(store.value.lastFinishEpochMillis)
    }

    private fun service(id: String) = ServiceSnapshot(
        serviceId = id,
        displayName = id,
        isEnabled = true,
        lastStatus = null,
        lastReward = null,
        lastMessage = null,
        lastTimestamp = null
    )
}

private data class TaygedoSelectionFixture(
    val credentials: FakeCredentialStore,
    val health: FakeAuthHealthStore,
    val requests: MutableList<String>,
    val nte: TaygedoNteProvider,
    val community: TaygedoCommunityProvider
)

private suspend fun taygedoSelectionFixture(
    vararg responses: Pair<Int, String>
): TaygedoSelectionFixture {
    val credentials = FakeCredentialStore().also {
        it.save(
            TaygedoClient.SESSION_KEY,
            "{\"accessToken\":\"synthetic-access\",\"refreshToken\":\"synthetic-refresh\",\"uid\":\"1\",\"deviceId\":\"synthetic-device\"}"
        )
    }
    val health = FakeAuthHealthStore()
    val requests = mutableListOf<String>()
    val responseList = responses.toList().ifEmpty {
        listOf(200 to "{\"code\":0,\"data\":{},\"msg\":\"ok\",\"ok\":true}")
    }
    var index = 0
    val interceptor = Interceptor { chain ->
        requests += chain.request().url.encodedPath
        val (code, body) = responseList[index.coerceAtMost(responseList.lastIndex)]
        index++
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("synthetic")
            .body(body.toResponseBody())
            .build()
    }
    val client = TaygedoClient(
        ApplicationProvider.getApplicationContext(),
        credentials,
        OkHttpClient.Builder().addInterceptor(interceptor).build()
    )
    return TaygedoSelectionFixture(
        credentials,
        health,
        requests,
        TaygedoNteProvider(client),
        TaygedoCommunityProvider(client)
    )
}

private class RecordingDiagnosticsStore : AutoCheckInDiagnosticsStore {
    private val state = MutableStateFlow(AutoCheckInDiagnostics())
    val value: AutoCheckInDiagnostics get() = state.value
    override val diagnostics: Flow<AutoCheckInDiagnostics> = state

    override suspend fun recordPlannedNext(epochMillis: Long) {
        state.value = state.value.copy(plannedNextEpochMillis = epochMillis)
    }

    override suspend fun clearPlannedNext() {
        state.value = state.value.copy(plannedNextEpochMillis = null)
    }

    override suspend fun recordDailyStart(epochMillis: Long) {
        state.value = AutoCheckInDiagnostics(lastStartEpochMillis = epochMillis)
    }

    override suspend fun recordDailyTerminal(
        outcome: AutoCheckInDiagnosticOutcome,
        finishEpochMillis: Long,
        summary: CheckInSummary?
    ) {
        state.value = state.value.copy(
            lastOutcome = outcome,
            lastFinishEpochMillis = finishEpochMillis,
            completedTotal = summary?.total,
            completedSucceeded = summary?.succeeded,
            completedAlreadyCheckedIn = summary?.alreadyCheckedIn,
            completedFailed = summary?.failed,
            completedAttention = summary?.attention
        )
    }
}
