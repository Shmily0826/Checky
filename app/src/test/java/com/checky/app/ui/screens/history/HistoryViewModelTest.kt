package com.checky.app.ui.screens.history

import com.checky.app.data.model.CheckInRecord
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM coverage of the history screen state: records stream from the
 * repository and the clear action is delegated.
 *
 * Main is bound to the runTest scheduler so viewModelScope coroutines are
 * driven deterministically (same pattern as HomeViewModelTest). Note that
 * advanceUntilIdle() only drains foreground tasks, so subscriptions are made
 * with foreground `async { first { ... } }` collectors instead of
 * backgroundScope collectors.
 */
class HistoryViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recordsReflectRepositoryEmissions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MutableFakeRepository()
        val vm = HistoryViewModel(repo)

        val latest = async { vm.records.first { it.isNotEmpty() } }
        repo.records.value = listOf(record("r1"), record("r2"))
        advanceUntilIdle()

        val records = latest.await()
        assertEquals(2, records.size)
        assertEquals("r1", records.first().id)
        assertEquals("r2", records.last().id)
    }

    @Test
    fun clearHistoryDelegatesToRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = MutableFakeRepository()
        val vm = HistoryViewModel(repo)
        vm.clearHistory()
        advanceUntilIdle()
        assertEquals(1, repo.clearHistoryCalls)
    }

    private fun record(id: String) = CheckInRecord(
        id = id,
        serviceId = "gamepass",
        serviceName = "GamePass Daily",
        status = CheckInStatus.SUCCESS,
        reward = Reward(RewardType.POINTS, 20),
        message = "ok",
        diagnosticCode = "SUCCESS",
        durationMs = 10L,
        timestamp = 1L
    )
}

/** Configurable repository fake with call tracking. */
private class MutableFakeRepository : CheckInRepository {
    val records = MutableStateFlow<List<CheckInRecord>>(emptyList())
    val services = MutableStateFlow<List<ServiceSnapshot>>(emptyList())
    var clearHistoryCalls = 0
    val enabledCalls = mutableListOf<Pair<String, Boolean>>()

    override fun observeRecords(): Flow<List<CheckInRecord>> = records
    override fun observeServices(): Flow<List<ServiceSnapshot>> = services
    override suspend fun getService(id: String): ServiceSnapshot? =
        services.value.firstOrNull { it.serviceId == id }

    override suspend fun setEnabled(serviceId: String, enabled: Boolean) {
        enabledCalls += serviceId to enabled
    }
    override suspend fun saveResult(result: CheckInResult) {}
    override suspend fun clearHistory() {
        clearHistoryCalls++
    }
    override suspend fun ensureSeeded() {}
    override suspend fun resetDemoData() {}
}
