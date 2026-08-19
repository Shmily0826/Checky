package com.checky.app.domain

import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.CredentialValidation
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.RewardType
import com.checky.app.domain.providers.CloudBoxProvider
import com.checky.app.domain.providers.GamePassDailyProvider
import com.checky.app.domain.providers.StudyClubProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInAllUseCaseTest {

    @Test
    fun runsAllProvidersAndProducesCorrectSummary() = runTest {
        val repo = FakeCheckInRepository()
        val credentials = FakeCredentialStore()
        val scenarios = FakeMockScenarioStore()
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(
            GamePassDailyProvider(scenarios),
            CloudBoxProvider(credentials, scenarios),
            StudyClubProvider(scenarios)
        )

        val progress = mutableListOf<CheckInAllProgress>()
        val job = launch { useCase(providers).toList(progress) }
        advanceUntilIdle()
        job.join()

        val finished = progress.filterIsInstance<CheckInAllProgress.Finished>().single()
        assertEquals(3, finished.states.size)
        assertEquals(1, finished.summary.succeeded)
        assertEquals(1, finished.summary.alreadyCheckedIn)
        assertEquals(1, finished.summary.attention)
        assertEquals(0, finished.summary.failed)
        assertEquals(20, finished.summary.totalPoints)
        assertEquals(5, finished.summary.totalXp)
        // every terminal result was persisted, with a duration recorded
        assertEquals(3, repo.saved.size)
        assertTrue(repo.saved.all { it.durationMs > 0 })
    }

    @Test
    fun continuesOnFailureOfOneProvider() = runTest {
        val repo = FakeCheckInRepository()
        val scenarios = FakeMockScenarioStore()
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(
            GamePassDailyProvider(scenarios),
            FailingProvider()
        )

        val progress = mutableListOf<CheckInAllProgress>()
        val job = launch { useCase(providers).toList(progress) }
        advanceUntilIdle()
        job.join()

        val finished = progress.filterIsInstance<CheckInAllProgress.Finished>().single()
        assertEquals(1, finished.summary.succeeded)
        assertEquals(1, finished.summary.failed)
        assertEquals(2, repo.saved.size)
        assertTrue(repo.saved.any { it.status == CheckInStatus.FAILED })
        // failure message is user-safe, no raw exception text
        assertTrue(repo.saved.any { it.diagnosticCode == "TEMP_NETWORK" })
    }

    @Test
    fun sequentialModeProducesSameSummary() = runTest {
        val repo = FakeCheckInRepository()
        val credentials = FakeCredentialStore()
        val scenarios = FakeMockScenarioStore()
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(
            GamePassDailyProvider(scenarios),
            CloudBoxProvider(credentials, scenarios),
            StudyClubProvider(scenarios)
        )

        val progress = mutableListOf<CheckInAllProgress>()
        val job = launch { useCase(providers, parallel = false).toList(progress) }
        advanceUntilIdle()
        job.join()

        val finished = progress.filterIsInstance<CheckInAllProgress.Finished>().single()
        assertEquals(3, finished.summary.total)
        assertEquals(1, finished.summary.succeeded)
        assertEquals(1, finished.summary.attention)
        assertEquals(3, repo.saved.size)
    }

    @Test
    fun preventsDuplicateExecution() = runTest {
        val repo = FakeCheckInRepository()
        val scenarios = FakeMockScenarioStore()
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(GamePassDailyProvider(scenarios), StudyClubProvider(scenarios))

        val first = mutableListOf<CheckInAllProgress>()
        val job1 = launch { useCase(providers).toList(first) }
        // Let the first run set its running guard.
        advanceTimeBy(1)
        val second = mutableListOf<CheckInAllProgress>()
        val job2 = launch { useCase(providers).toList(second) }
        advanceUntilIdle()
        job1.join()
        job2.join()

        // Second invocation must be ignored while the first is still running.
        assertTrue("duplicate run should emit nothing", second.isEmpty())
    }

    @Test
    fun cancellationStopsRunWithoutSavingPartialResults() = runTest {
        val repo = FakeCheckInRepository()
        val scenarios = FakeMockScenarioStore()
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(GamePassDailyProvider(scenarios), StudyClubProvider(scenarios))

        val progress = mutableListOf<CheckInAllProgress>()
        val job = launch { useCase(providers).toList(progress) }
        // Let the run start, then cancel before any provider finishes.
        advanceTimeBy(50)
        job.cancel()
        advanceUntilIdle()

        assertTrue(progress.none { it is CheckInAllProgress.Finished })
        assertTrue("no results should be persisted after cancellation", repo.saved.isEmpty())
    }
}

/** Test-only provider that always fails mid-stream. */
private class FailingProvider : CheckInProvider {
    override val meta = ProviderMeta(
        id = "fail",
        displayName = "Fail",
        description = "",
        category = "X",
        iconKey = "star",
        accentColor = 0xFF000000,
        isEnabledByDefault = true
    )

    override suspend fun validateCredentials(secret: String): CredentialValidation = CredentialValidation.Valid

    override fun checkIn(): Flow<CheckInEvent> = flow {
        emit(CheckInEvent.Progress(0.5f, "working"))
        throw RuntimeException("boom")
    }
}
