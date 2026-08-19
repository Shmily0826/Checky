package com.checky.app.ui.screens.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.domain.CheckInAllUseCase
import com.checky.app.domain.FakeCheckInRepository
import com.checky.app.domain.FakeMockScenarioStore
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.providers.GamePassDailyProvider
import com.checky.app.domain.providers.StudyClubProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-level coverage of the "Check in all" UI state transitions
 * (Running -> Finished), single retry, and cancellation.
 *
 * Main is bound to the runTest scheduler so viewModelScope coroutines are
 * driven deterministically. Main is reset in @After (after runTest fully
 * drains) because the DataStore actor may emit during finalization.
 */
class HomeViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun checkInAllTransitionsThroughRunningThenFinished() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeCheckInRepository()
        val scenarios = FakeMockScenarioStore()
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(
            GamePassDailyProvider(scenarios),
            StudyClubProvider(scenarios)
        )
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = useCase,
            providers = providers,
            metas = providers.map { it.meta }
        )

        val events = mutableListOf<CheckInAllProgress>()
        val collector = backgroundScope.launch {
            vm.progress.collect { it?.let(events::add) }
        }

        vm.checkInAll()
        advanceUntilIdle()

        // Transitions: at least one Running snapshot, then a Finished summary.
        assertTrue(events.any { it is CheckInAllProgress.Running })
        val finished = events.last() as CheckInAllProgress.Finished
        assertEquals(2, finished.summary.total)
        assertEquals(1, finished.summary.succeeded)
        assertEquals(1, finished.summary.alreadyCheckedIn)
        assertEquals(20, finished.summary.totalPoints)
        assertEquals(5, finished.summary.totalXp)
        assertEquals(2, repo.saved.size)
        collector.cancel()
    }

    @Test
    fun retrySingleProviderRunsOnlyThatProvider() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeCheckInRepository()
        val scenarios = FakeMockScenarioStore()
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(
            GamePassDailyProvider(scenarios),
            StudyClubProvider(scenarios)
        )
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = useCase,
            providers = providers,
            metas = providers.map { it.meta }
        )

        vm.retry("gamepass")
        advanceUntilIdle()

        val finished = vm.progress.value as CheckInAllProgress.Finished
        assertEquals(1, finished.summary.total)
        assertEquals(1, finished.summary.succeeded)
        assertEquals(1, repo.saved.size)
    }

    @Test
    fun cancelCheckInAllClearsState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeCheckInRepository()
        val scenarios = FakeMockScenarioStore()
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(
            GamePassDailyProvider(scenarios),
            StudyClubProvider(scenarios)
        )
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = useCase,
            providers = providers,
            metas = providers.map { it.meta }
        )

        val events = mutableListOf<CheckInAllProgress>()
        val collector = backgroundScope.launch {
            vm.progress.collect { it?.let(events::add) }
        }

        vm.checkInAll()
        // Let the run start (providers are mid-flight, nothing finished yet).
        advanceTimeBy(600)
        assertTrue(events.any { it is CheckInAllProgress.Running })

        vm.cancelCheckInAll()
        advanceUntilIdle()

        assertNull(vm.progress.value)
        assertTrue("cancelled run must not persist results", repo.saved.isEmpty())
        collector.cancel()
    }

    private fun prefsRepo(testScope: TestScope): UserPreferencesRepository {
        val file = File.createTempFile("checky_prefs", ".preferences_pb").apply { deleteOnExit() }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { file }
        )
        return UserPreferencesRepository(dataStore)
    }
}
