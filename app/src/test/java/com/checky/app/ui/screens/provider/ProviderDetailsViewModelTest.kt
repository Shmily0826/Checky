package com.checky.app.ui.screens.provider

import androidx.lifecycle.SavedStateHandle
import com.checky.app.data.model.CheckInRecord
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.providers.GamePassDailyProvider
import com.checky.app.domain.providers.StudyClubProvider
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
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM coverage of the provider-details screen state: meta resolution from the
 * navigation id, service snapshot streaming and the enable toggle.
 */
class ProviderDetailsViewModelTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun metaResolvedFromSavedServiceId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ProviderDetailsViewModel(
            repository = DetailsFakeRepository(),
            metas = listOf(GamePassDailyProvider.META, StudyClubProvider.META),
            savedStateHandle = SavedStateHandle(mapOf("serviceId" to "gamepass"))
        )
        assertEquals("gamepass", vm.meta?.id)
    }

    @Test
    fun unknownServiceIdLeavesMetaNull() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ProviderDetailsViewModel(
            repository = DetailsFakeRepository(),
            metas = listOf(GamePassDailyProvider.META),
            savedStateHandle = SavedStateHandle(mapOf("serviceId" to "nope"))
        )
        assertNull(vm.meta)
    }

    @Test
    fun serviceStreamExposesOnlyTheSelectedService() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = DetailsFakeRepository()
        val vm = ProviderDetailsViewModel(
            repository = repo,
            metas = listOf(GamePassDailyProvider.META, StudyClubProvider.META),
            savedStateHandle = SavedStateHandle(mapOf("serviceId" to "studyclub"))
        )

        // Foreground collector: advanceUntilIdle() only drains foreground tasks.
        val latest = async { vm.service.first { it != null } }
        repo.services.value = listOf(
            snapshot("gamepass", enabled = true),
            snapshot("studyclub", enabled = false)
        )
        advanceUntilIdle()

        val service = latest.await()
        assertEquals("studyclub", service?.serviceId)
        assertEquals(false, service?.isEnabled)
    }

    @Test
    fun setEnabledDelegatesToRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = DetailsFakeRepository()
        val vm = ProviderDetailsViewModel(
            repository = repo,
            metas = listOf(GamePassDailyProvider.META),
            savedStateHandle = SavedStateHandle(mapOf("serviceId" to "gamepass"))
        )
        vm.setEnabled(false)
        advanceUntilIdle()
        assertEquals(listOf("gamepass" to false), repo.enabledCalls)
    }

    private fun snapshot(id: String, enabled: Boolean) = ServiceSnapshot(
        serviceId = id,
        displayName = id,
        isEnabled = enabled,
        lastStatus = null,
        lastReward = null,
        lastMessage = null,
        lastTimestamp = null
    )
}

private class DetailsFakeRepository : CheckInRepository {
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
