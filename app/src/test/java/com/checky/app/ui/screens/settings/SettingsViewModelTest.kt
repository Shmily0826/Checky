package com.checky.app.ui.screens.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.checky.app.data.model.CheckInRecord
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.data.preferences.RunMode
import com.checky.app.data.preferences.ThemeMode
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.data.preferences.AutoCheckInDiagnosticsStore
import com.checky.app.data.preferences.DataStoreAutoCheckInDiagnosticsStore
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.data.work.AutoCheckInWorker
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.background.BackgroundReliabilityReader
import com.checky.app.domain.model.CheckInResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Robolectric coverage of the settings screen. Runs on the JVM with the real
 * Android framework classes.
 *
 * Timing model: Main is replaced with an eager unconfined test dispatcher, so
 * ViewModel actions run to their first suspension synchronously. DataStore
 * performs its file IO on real Dispatchers.IO threads, which virtual-time
 * scheduling cannot drive — therefore DataStore-backed assertions poll with a
 * real-time deadline instead of relying on advanceUntilIdle(). The DataStore
 * scope is NOT cancelled between tests to avoid killing a mid-write actor
 * whose exception would leak into later test classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var repo: TrackingRepository
    private lateinit var credentials: FakeCredentialStore
    private lateinit var prefsRepository: UserPreferencesRepository
    private lateinit var diagnosticsStore: AutoCheckInDiagnosticsStore
    private lateinit var context: Context

    private val dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().build()
        )
        repo = TrackingRepository()
        credentials = FakeCredentialStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildVm(): SettingsViewModel {
        val file = File(context.cacheDir, "settings_test_${UUID.randomUUID()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { file }
        )
        prefsRepository = UserPreferencesRepository(dataStore)
        diagnosticsStore = DataStoreAutoCheckInDiagnosticsStore(dataStore)
        return SettingsViewModel(
            userPreferencesRepository = prefsRepository,
            autoCheckInDiagnosticsStore = diagnosticsStore,
            repository = repo,
            credentialStore = credentials,
            backgroundReliabilityReader = BackgroundReliabilityReader(context),
            providerMetas = emptyList(),
            providers = emptyList(),
            context = context
        )
    }

    /** Reads preferences with a real-time deadline so DataStore IO can land. */
    private fun awaitPref(
        timeoutMs: Long = 10_000,
        predicate: (UserPreferences) -> Boolean = { true }
    ): UserPreferences {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val prefs = runBlocking { prefsRepository.preferences.first() }
            if (predicate(prefs) || System.currentTimeMillis() >= deadline) return prefs
            Thread.sleep(25)
        }
    }

    /** Polls WorkManager with a real-time deadline until the unique work appears. */
    private fun awaitWork(uniqueName: String) {
        val deadline = System.currentTimeMillis() + 10_000
        var infos = emptyList<androidx.work.WorkInfo>()
        while (infos.isEmpty()) {
            infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(uniqueName).get(10, TimeUnit.SECONDS)
            if (infos.isNotEmpty() || System.currentTimeMillis() >= deadline) return
            Thread.sleep(25)
        }
    }

    private fun currentWork(uniqueName: String): androidx.work.WorkInfo =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(uniqueName)
            .get(10, TimeUnit.SECONDS)
            .single()

    private fun awaitReplacement(uniqueName: String, previousId: UUID): androidx.work.WorkInfo {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val work = currentWork(uniqueName)
            if (work.id != previousId) return work
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out waiting for replacement: $uniqueName")
            }
            Thread.sleep(25)
        }
    }

    private fun awaitWorkState(
        uniqueName: String,
        state: androidx.work.WorkInfo.State
    ): androidx.work.WorkInfo {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            val work = currentWork(uniqueName)
            if (work.state == state) return work
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Timed out waiting for $uniqueName to become $state")
            }
            Thread.sleep(25)
        }
    }

    @Test
    fun setThemeModePersists() {
        val vm = buildVm()

        vm.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, awaitPref { it.themeMode == ThemeMode.DARK }.themeMode)
    }

    @Test
    fun setRunModePersists() {
        val vm = buildVm()

        vm.setRunMode(RunMode.SEQUENTIAL)

        assertEquals(RunMode.SEQUENTIAL, awaitPref { it.runMode == RunMode.SEQUENTIAL }.runMode)
    }

    @Test
    fun setTapTapTestUrlPersistsSeparatelyFromNormalUrl() {
        val vm = buildVm()

        vm.setTapTapEventUrl("https://www.taptap.cn/events/game-sign/normal")
        vm.setTapTapTestEventUrl("https://www.taptap.cn/events/game-sign/test")

        val prefs = awaitPref { it.tapTapTestEventUrl != null }
        assertEquals("https://www.taptap.cn/events/game-sign/normal", prefs.tapTapEventUrl)
        assertEquals("https://www.taptap.cn/events/game-sign/test", prefs.tapTapTestEventUrl)
    }

    @Test
    fun setReminderEnabledSchedulesWorkAndPersists() {
        val vm = buildVm()

        vm.setReminderEnabled(true)

        assertTrue(awaitPref { it.reminderEnabled }.reminderEnabled)
        awaitWork("checky_daily_reminder")

        vm.setReminderEnabled(false)
        assertFalse(awaitPref { !it.reminderEnabled }.reminderEnabled)
        assertEquals(
            androidx.work.WorkInfo.State.CANCELLED,
            awaitWorkState("checky_daily_reminder", androidx.work.WorkInfo.State.CANCELLED).state
        )
    }

    @Test
    fun setReminderTimePersistsAndReschedulesWhenEnabled() {
        val vm = buildVm()

        vm.setReminderEnabled(true)
        awaitWork("checky_daily_reminder")
        val first = currentWork("checky_daily_reminder")

        vm.setReminderTime(7, 45)

        val prefs = awaitPref { it.reminderHour == 7 && it.reminderMinute == 45 }
        assertEquals(7, prefs.reminderHour)
        assertEquals(45, prefs.reminderMinute)
        val second = awaitReplacement("checky_daily_reminder", first.id)
        assertTrue(first.id != second.id)
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, second.state)
    }

    @Test
    fun setAutoCheckInTogglesPersistAndSchedule() {
        val vm = buildVm()

        vm.setAutoCheckInTime(5, 30)
        vm.setAutoCheckInEnabled(true)

        val prefs = awaitPref { it.autoCheckInEnabled && it.autoCheckInHour == 5 }
        assertTrue(prefs.autoCheckInEnabled)
        assertEquals(5, prefs.autoCheckInHour)
        assertEquals(30, prefs.autoCheckInMinute)
        awaitWork("checky_auto_checkin")
    }

    @Test
    fun changingEnabledAutoCheckInTimeReplacesPeriodicRequest() {
        val vm = buildVm()

        vm.setAutoCheckInEnabled(true)
        awaitWork("checky_auto_checkin")
        val first = currentWork("checky_auto_checkin")

        vm.setAutoCheckInTime(5, 30)
        val second = awaitReplacement("checky_auto_checkin", first.id)

        assertTrue(first.id != second.id)
        assertEquals(androidx.work.WorkInfo.State.ENQUEUED, second.state)

        vm.setAutoCheckInEnabled(false)
        assertEquals(
            androidx.work.WorkInfo.State.CANCELLED,
            awaitWorkState("checky_auto_checkin", androidx.work.WorkInfo.State.CANCELLED).state
        )
    }

    @Test
    fun successfulDailyEnqueuePersistsTheRequestPlannedTargetAndCancelClearsIt() {
        buildVm()
        val now = ZonedDateTime.of(
            LocalDateTime.of(2026, 9, 6, 7, 30),
            ZoneId.of("Pacific/Auckland")
        )
        val expected = AutoCheckInWorker.nextScheduledDateTime(8, 0, now)

        runBlocking {
            AutoCheckInWorker.cancel(context, diagnosticsStore)
            AutoCheckInWorker.schedule(
                context,
                8,
                0,
                now = now,
                diagnostics = diagnosticsStore
            )
            assertEquals(
                expected.toInstant().toEpochMilli(),
                diagnosticsStore.diagnostics.first().plannedNextEpochMillis
            )

            AutoCheckInWorker.cancel(context, diagnosticsStore)
            assertEquals(null, diagnosticsStore.diagnostics.first().plannedNextEpochMillis)
        }
    }

    @Test
    fun clearHistoryDelegatesToRepository() {
        val vm = buildVm()

        vm.clearHistory()

        assertEquals(1, repo.clearHistoryCalls)
    }

    @Test
    fun deleteAllCredentialsClearsTheVault() {
        runBlocking { credentials.save("gamepass", "secret") }
        val vm = buildVm()

        vm.deleteAllCredentials()

        runBlocking {
            assertFalse(credentials.has("gamepass"))
        }
    }

    @Test
    fun showOnboardingAgainResetsTheFlag() {
        val vm = buildVm()
        runBlocking { prefsRepository.setOnboardingComplete(true) }

        vm.showOnboardingAgain()

        assertFalse(awaitPref { !it.onboardingComplete }.onboardingComplete)
    }
}

/** Repository fake tracking the mutating calls SettingsViewModel makes. */
private class TrackingRepository : CheckInRepository {
    var clearHistoryCalls = 0
    val records = MutableStateFlow<List<CheckInRecord>>(emptyList())
    val services = MutableStateFlow<List<ServiceSnapshot>>(emptyList())

    override fun observeRecords(): Flow<List<CheckInRecord>> = records
    override fun observeServices(): Flow<List<ServiceSnapshot>> = services
    override suspend fun getService(id: String): ServiceSnapshot? =
        services.value.firstOrNull { it.serviceId == id }

    override suspend fun setEnabled(serviceId: String, enabled: Boolean) {}
    override suspend fun saveResult(result: CheckInResult) {}
    override suspend fun clearHistory() {
        clearHistoryCalls++
    }
}
