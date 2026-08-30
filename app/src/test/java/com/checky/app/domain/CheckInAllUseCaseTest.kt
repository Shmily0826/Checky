package com.checky.app.domain

import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.CheckInEvent
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
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

    private fun successProvider() = ScriptedCheckInProvider(
        meta = testProviderMeta("alpha"),
        status = CheckInStatus.SUCCESS,
        reward = Reward(RewardType.POINTS, 20)
    )

    private fun alreadyProvider() = ScriptedCheckInProvider(
        meta = testProviderMeta("beta"),
        status = CheckInStatus.ALREADY_CHECKED_IN,
        reward = Reward(RewardType.EXPERIENCE, 5)
    )

    private fun expiredProvider() = ScriptedCheckInProvider(
        meta = testProviderMeta("gamma"),
        status = CheckInStatus.LOGIN_EXPIRED
    )

    @Test
    fun runsAllProvidersAndProducesCorrectSummary() = runTest {
        val repo = FakeCheckInRepository()
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(successProvider(), alreadyProvider(), expiredProvider())

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
        // every terminal result was persisted exactly once
        assertEquals(3, repo.saved.size)
        assertEquals(
            setOf("alpha", "beta", "gamma"),
            repo.saved.map { it.serviceId }.toSet()
        )
    }

    @Test
    fun continuesOnFailureOfOneProvider() = runTest {
        val repo = FakeCheckInRepository()
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(successProvider(), FailingProvider())

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
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(successProvider(), alreadyProvider(), expiredProvider())

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
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(successProvider(), alreadyProvider())

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
        val useCase = CheckInAllUseCase(repo)
        val providers = listOf(successProvider(), alreadyProvider())

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
    override val meta = testProviderMeta("fail")

    override suspend fun validateCredentials(secret: String): CredentialValidation = CredentialValidation.Valid

    override fun checkIn(): Flow<CheckInEvent> = flow {
        emit(CheckInEvent.Progress(0.5f, "working"))
        throw RuntimeException("boom")
    }
}
