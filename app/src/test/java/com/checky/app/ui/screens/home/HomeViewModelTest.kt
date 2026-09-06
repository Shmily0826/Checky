package com.checky.app.ui.screens.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.FakeAuthHealthStore
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.CheckInAllUseCase
import com.checky.app.domain.FakeCheckInRepository
import com.checky.app.domain.ScriptedCheckInProvider
import com.checky.app.domain.SequenceCheckInProvider
import com.checky.app.domain.testProviderMeta
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertFalse
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
        val providers = listOf(
            ScriptedCheckInProvider(
                testProviderMeta("alpha"),
                status = CheckInStatus.SUCCESS,
                reward = Reward(RewardType.POINTS, 20)
            ),
            ScriptedCheckInProvider(
                testProviderMeta("beta"),
                status = CheckInStatus.ALREADY_CHECKED_IN,
                reward = Reward(RewardType.EXPERIENCE, 5)
            )
        )
        val repo = FakeCheckInRepository(providers.map { service(it.meta.id) })
        val useCase = CheckInAllUseCase(repo)
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = useCase,
            credentialStore = FakeCredentialStore(),
            providers = providers,
            metas = providers.map { it.meta }
        )
        backgroundScope.launch { vm.services.collect {} }
        advanceUntilIdle()

        val events = mutableListOf<CheckInAllProgress>()
        val collector = backgroundScope.launch {
            vm.progress.collect { it?.let(events::add) }
        }
        val finishedProgress = async { vm.progress.first { it is CheckInAllProgress.Finished } }

        vm.checkInAll()
        advanceUntilIdle()

        // Transitions: at least one Running snapshot, then a Finished summary.
        assertTrue(events.any { it is CheckInAllProgress.Running })
        val finished = finishedProgress.await() as CheckInAllProgress.Finished
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
        val providers = listOf(
            ScriptedCheckInProvider(
                testProviderMeta("alpha"),
                status = CheckInStatus.SUCCESS,
                reward = Reward(RewardType.POINTS, 20)
            ),
            ScriptedCheckInProvider(
                testProviderMeta("beta"),
                status = CheckInStatus.ALREADY_CHECKED_IN,
                reward = Reward(RewardType.EXPERIENCE, 5)
            )
        )
        val repo = FakeCheckInRepository(providers.map { service(it.meta.id) })
        val useCase = CheckInAllUseCase(repo)
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = useCase,
            credentialStore = FakeCredentialStore(),
            providers = providers,
            metas = providers.map { it.meta }
        )
        backgroundScope.launch { vm.services.collect {} }
        advanceUntilIdle()

        vm.retry(providers.first().meta.id)
        advanceUntilIdle()

        val finished = vm.progress.value as CheckInAllProgress.Finished
        assertEquals(1, finished.summary.total)
        assertEquals(1, finished.summary.succeeded)
        assertEquals(1, repo.saved.size)
    }

    @Test
    fun cancelCheckInAllClearsState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val providers = listOf(
            ScriptedCheckInProvider(
                testProviderMeta("alpha"),
                status = CheckInStatus.SUCCESS,
                delayMs = 1_000
            ),
            ScriptedCheckInProvider(
                testProviderMeta("beta"),
                status = CheckInStatus.ALREADY_CHECKED_IN,
                delayMs = 1_000
            )
        )
        val repo = FakeCheckInRepository(providers.map { service(it.meta.id) })
        val useCase = CheckInAllUseCase(repo)
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = useCase,
            credentialStore = FakeCredentialStore(),
            providers = providers,
            metas = providers.map { it.meta }
        )
        backgroundScope.launch { vm.services.collect {} }
        advanceUntilIdle()

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

    @Test
    fun checkInAllDoesNotRunProvidersBeforeServicesLoad() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = FakeCheckInRepository()
        val provider = com.checky.app.domain.SequenceCheckInProvider(
            meta = testProviderMeta("not-loaded"),
            com.checky.app.domain.model.CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = CheckInAllUseCase(repo),
            credentialStore = FakeCredentialStore(),
            providers = listOf(provider),
            metas = listOf(provider.meta)
        )

        vm.checkInAll()
        advanceUntilIdle()

        assertEquals(0, provider.attempts)
        assertNull(vm.progress.value)
    }

    @Test
    fun selectedCredentialProviderWithoutConnectionIsNotExecutable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = SequenceCheckInProvider(
            meta = testProviderMeta("auth-only").copy(
                credentialType = com.checky.app.domain.model.CredentialType.SESSION_TOKEN
            ),
            com.checky.app.domain.model.CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val repo = FakeCheckInRepository(listOf(service(provider.meta.id)))
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = CheckInAllUseCase(repo),
            credentialStore = FakeCredentialStore(),
            providers = listOf(provider),
            metas = listOf(provider.meta)
        )
        backgroundScope.launch { vm.services.collect {} }
        advanceUntilIdle()

        vm.checkInAll()
        advanceUntilIdle()

        assertEquals(0, provider.attempts)
        assertNull(vm.progress.value)
    }

    @Test
    fun credentialedSelectedProviderRemainsExecutable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = SequenceCheckInProvider(
            meta = testProviderMeta("credentialed").copy(
                credentialType = com.checky.app.domain.model.CredentialType.SESSION_TOKEN
            ),
            com.checky.app.domain.model.CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val repo = FakeCheckInRepository(listOf(service(provider.meta.id)))
        val credentials = FakeCredentialStore()
        credentials.save(provider.meta.id, "synthetic-test-secret")
        val health = FakeAuthHealthStore()
        health.set(provider.meta.id, AuthHealth.VALID)
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = CheckInAllUseCase(repo, credentials, health),
            credentialStore = credentials,
            providers = listOf(provider),
            metas = listOf(provider.meta),
            authHealthStore = health
        )
        backgroundScope.launch { vm.services.collect {} }
        advanceUntilIdle()

        vm.checkInAll()
        advanceUntilIdle()

        assertEquals(1, provider.attempts)
    }

    @Test
    fun enabledDisconnectedServiceRemainsVisibleButCannotBeRetried() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = SequenceCheckInProvider(
            meta = testProviderMeta("disconnected").copy(
                credentialType = com.checky.app.domain.model.CredentialType.SESSION_TOKEN
            ),
            com.checky.app.domain.model.CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val repo = FakeCheckInRepository(
            listOf(
                service(provider.meta.id).copy(
                    lastStatus = CheckInStatus.SUCCESS,
                    lastReward = Reward(RewardType.POINTS, 99)
                )
            )
        )
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = CheckInAllUseCase(repo),
            credentialStore = FakeCredentialStore(),
            providers = listOf(provider),
            metas = listOf(provider.meta)
        )

        val visible = async { vm.homeServices.first { it.isNotEmpty() } }
        val state = visible.await().single()
        assertFalse(state.isConnected)
        assertEquals(CheckInStatus.SUCCESS, state.service.lastStatus)
        assertEquals(99, state.service.lastReward?.amount)
        assertTrue(currentSummaryServices(listOf(state.service), mapOf(provider.meta.id to false)).isEmpty())

        vm.retry(provider.meta.id)
        advanceUntilIdle()

        assertEquals(0, provider.attempts)
    }

    @Test
    fun disabledServiceIsNotIncludedOnHome() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = SequenceCheckInProvider(
            meta = testProviderMeta("disabled").copy(
                credentialType = com.checky.app.domain.model.CredentialType.SESSION_TOKEN
            ),
            com.checky.app.domain.model.CheckInOutcome.Success("ok", "SUCCESS", Reward.empty())
        )
        val repo = FakeCheckInRepository(listOf(service(provider.meta.id).copy(isEnabled = false)))
        val credentials = FakeCredentialStore().also { it.save(provider.meta.id, "synthetic-test-secret") }
        val vm = HomeViewModel(
            repository = repo,
            userPreferencesRepository = prefsRepo(this),
            checkInAllUseCase = CheckInAllUseCase(repo),
            credentialStore = credentials,
            providers = listOf(provider),
            metas = listOf(provider.meta)
        )
        backgroundScope.launch { vm.homeServices.collect {} }
        advanceUntilIdle()

        assertTrue(vm.homeServices.value.isEmpty())
    }

    private fun prefsRepo(testScope: TestScope): UserPreferencesRepository {
        val file = File.createTempFile("checky_prefs", ".preferences_pb").apply { deleteOnExit() }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { file }
        )
        return UserPreferencesRepository(dataStore)
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
