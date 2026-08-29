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
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.FakeMockScenarioStore
import com.checky.app.domain.MockScenario
import com.checky.app.domain.model.CheckInResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
class SettingsViewModelTest {

    private lateinit var repo: TrackingRepository
    private lateinit var credentials: FakeCredentialStore
    private lateinit var scenarios: FakeMockScenarioStore
    private lateinit var prefsRepository: UserPreferencesRepository
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
        scenarios = FakeMockScenarioStore()
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
        return SettingsViewModel(
            userPreferencesRepository = prefsRepository,
            repository = repo,
            credentialStore = credentials,
            mockScenarioStore = scenarios,
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
    fun setMockScenarioPersists() {
        val vm = buildVm()

        vm.setMockScenario(MockScenario.NETWORK_FAILURE)

        // The scenario store is an in-memory fake — synchronous on the eager Main.
        runBlocking {
            assertEquals(MockScenario.NETWORK_FAILURE, scenarios.scenario().first())
        }
    }

    @Test
    fun setReminderEnabledSchedulesWorkAndPersists() {
        val vm = buildVm()

        vm.setReminderEnabled(true)

        assertTrue(awaitPref { it.reminderEnabled }.reminderEnabled)
        awaitWork("checky_daily_reminder")

        vm.setReminderEnabled(false)
        assertFalse(awaitPref { !it.reminderEnabled }.reminderEnabled)
    }

    @Test
    fun setReminderTimePersistsAndReschedulesWhenEnabled() {
        val vm = buildVm()

        vm.setReminderTime(7, 45)

        val prefs = awaitPref { it.reminderHour == 7 && it.reminderMinute == 45 }
        assertEquals(7, prefs.reminderHour)
        assertEquals(45, prefs.reminderMinute)
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
    fun clearHistoryDelegatesToRepository() {
        val vm = buildVm()

        vm.clearHistory()

        assertEquals(1, repo.clearHistoryCalls)
    }

    @Test
    fun resetDemoDataDelegatesToRepository() {
        val vm = buildVm()

        vm.resetDemoData()

        assertEquals(1, repo.resetDemoDataCalls)
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
    var resetDemoDataCalls = 0
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

    override suspend fun ensureSeeded() {}
    override suspend fun resetDemoData() {
        resetDemoDataCalls++
    }
}
