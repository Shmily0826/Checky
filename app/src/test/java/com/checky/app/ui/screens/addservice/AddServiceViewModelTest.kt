package com.checky.app.ui.screens.addservice

import com.checky.app.data.model.CheckInRecord
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.providers.CloudBoxProvider
import com.checky.app.domain.providers.GamePassDailyProvider
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
 * JVM coverage of the add-service catalog screen: services stream from the
 * repository and toggles are delegated.
 */
class AddServiceViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun servicesReflectRepositoryEmissions() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = AddServiceFakeRepository()
        val vm = AddServiceViewModel(repo, catalog())

        // Foreground collector: advanceUntilIdle() only drains foreground tasks.
        val latest = async { vm.services.first { it.isNotEmpty() } }
        repo.services.value = listOf(snapshot("gamepass"), snapshot("cloudbox"))
        advanceUntilIdle()

        assertEquals(
            listOf("gamepass", "cloudbox"),
            latest.await().map { it.serviceId }
        )
    }

    @Test
    fun catalogExposesProviderMetas() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val metas = catalog()
        val vm = AddServiceViewModel(AddServiceFakeRepository(), metas)
        assertEquals(metas, vm.catalog)
    }

    @Test
    fun setEnabledDelegatesToRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = AddServiceFakeRepository()
        val vm = AddServiceViewModel(repo, catalog())
        vm.setEnabled("cloudbox", true)
        advanceUntilIdle()
        assertEquals(listOf("cloudbox" to true), repo.enabledCalls)
    }

    private fun catalog() = listOf(GamePassDailyProvider.META, CloudBoxProvider.META)

    private fun snapshot(id: String) = ServiceSnapshot(
        serviceId = id,
        displayName = id,
        isEnabled = true,
        lastStatus = null,
        lastReward = null,
        lastMessage = null,
        lastTimestamp = null
    )
}

private class AddServiceFakeRepository : CheckInRepository {
    val services = MutableStateFlow<List<ServiceSnapshot>>(emptyList())
    val enabledCalls = mutableListOf<Pair<String, Boolean>>()

    override fun observeRecords(): Flow<List<CheckInRecord>> = MutableStateFlow(emptyList())
    override fun observeServices(): Flow<List<ServiceSnapshot>> = services
    override suspend fun getService(id: String): ServiceSnapshot? =
        services.value.firstOrNull { it.serviceId == id }

    override suspend fun setEnabled(serviceId: String, enabled: Boolean) {
        enabledCalls += serviceId to enabled
    }
    override suspend fun saveResult(result: CheckInResult) {}
    override suspend fun clearHistory() {}
    override suspend fun ensureSeeded() {}
    override suspend fun resetDemoData() {}
}
