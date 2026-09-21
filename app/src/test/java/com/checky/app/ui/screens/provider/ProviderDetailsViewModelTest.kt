package com.checky.app.ui.screens.provider

import androidx.lifecycle.SavedStateHandle
import com.checky.app.data.model.CheckInRecord
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.FakeCredentialStore
import com.checky.app.domain.FakeAuthHealthStore
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.SequenceCheckInProvider
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import com.checky.app.domain.providers.TaygedoCommunityProvider
import com.checky.app.domain.providers.TaygedoNteProvider
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            credentialStore = FakeCredentialStore(),
            providers = emptyList(),
            metas = listOf(TaygedoNteProvider.META, TaygedoCommunityProvider.META),
            savedStateHandle = SavedStateHandle(mapOf("serviceId" to "taygedo_nte"))
        )
        assertEquals("taygedo_nte", vm.meta?.id)
    }

    @Test
    fun unknownServiceIdLeavesMetaNull() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ProviderDetailsViewModel(
            repository = DetailsFakeRepository(),
            credentialStore = FakeCredentialStore(),
            providers = emptyList(),
            metas = listOf(TaygedoNteProvider.META),
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
            credentialStore = FakeCredentialStore(),
            providers = emptyList(),
            metas = listOf(TaygedoNteProvider.META, TaygedoCommunityProvider.META),
            savedStateHandle = SavedStateHandle(mapOf("serviceId" to "miyoushe_community_signin"))
        )

        // Foreground collector: advanceUntilIdle() only drains foreground tasks.
        val latest = async { vm.service.first { it != null } }
        repo.services.value = listOf(
            snapshot("taygedo_nte", enabled = true),
            snapshot("miyoushe_community_signin", enabled = false)
        )
        advanceUntilIdle()

        val service = latest.await()
        assertEquals("miyoushe_community_signin", service?.serviceId)
        assertEquals(false, service?.isEnabled)
    }

    @Test
    fun setEnabledDelegatesToRepository() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = DetailsFakeRepository()
        val vm = ProviderDetailsViewModel(
            repository = repo,
            credentialStore = FakeCredentialStore(),
            providers = emptyList(),
            metas = listOf(TaygedoNteProvider.META),
            savedStateHandle = SavedStateHandle(mapOf("serviceId" to "taygedo_nte"))
        )
        vm.setEnabled(false)
        advanceUntilIdle()
        assertEquals(listOf("taygedo_nte" to false), repo.enabledCalls)
    }

    @Test
    fun historicalSuccessWithMissingCredentialIsDisconnectedAndReconnectable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = SequenceCheckInProvider(
            meta = TaygedoNteProvider.META,
            CheckInOutcome.Success("historical", "SUCCESS", Reward(RewardType.POINTS, 10))
        )
        val repo = DetailsFakeRepository().also {
            it.services.value = listOf(
                snapshot("taygedo_nte", enabled = true).copy(lastStatus = CheckInStatus.SUCCESS)
            )
        }
        val credentials = FakeCredentialStore()
        val health = FakeAuthHealthStore()
        val vm = ProviderDetailsViewModel(
            repository = repo,
            credentialStore = credentials,
            providers = listOf(provider),
            metas = listOf(TaygedoNteProvider.META),
            savedStateHandle = SavedStateHandle(mapOf("serviceId" to "taygedo_nte")),
            authHealthStore = health
        )

        val details = async { vm.service.first { it != null } }
        val disconnected = async { vm.isConnected.first { !it } }
        advanceUntilIdle()

        assertEquals(CheckInStatus.SUCCESS, details.await()?.lastStatus)
        assertFalse(disconnected.await())
        assertEquals(ConnectionAction.RECONNECT, connectionAction(vm.isConnected.value))
        assertEquals(0, provider.attempts)

        credentials.save(provider.meta.id, "synthetic-test-secret")
        health.set(provider.credentialOwnerId, AuthHealth.VALID)
        vm.refreshConnection()
        assertEquals(true, vm.isConnected.first { it })
        assertEquals(ConnectionAction.MANAGE_CONNECTION, connectionAction(vm.isConnected.value))
    }

    @Test
    fun storedValidHealthWinsOverExpiredSnapshotWithoutDeletingCredential() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val provider = SequenceCheckInProvider(
            meta = TaygedoNteProvider.META,
            CheckInOutcome.Success("historical", "SUCCESS", Reward.empty())
        )
        val repo = DetailsFakeRepository().also {
            it.services.value = listOf(
                snapshot("taygedo_nte", enabled = true).copy(lastStatus = CheckInStatus.LOGIN_EXPIRED)
            )
        }
        val credentials = FakeCredentialStore().also {
            it.save(provider.meta.id, "synthetic-test-secret")
        }
        val health = FakeAuthHealthStore().also {
            it.set(provider.credentialOwnerId, AuthHealth.VALID)
        }
        val vm = ProviderDetailsViewModel(
            repository = repo,
            credentialStore = credentials,
            providers = listOf(provider),
            metas = listOf(TaygedoNteProvider.META),
            savedStateHandle = SavedStateHandle(mapOf("serviceId" to "taygedo_nte")),
            authHealthStore = health
        )

        val detailsHealth = async { vm.authHealth.first { it == AuthHealth.VALID } }
        val connected = async { vm.isConnected.first { it } }
        advanceUntilIdle()

        assertEquals(AuthHealth.VALID, detailsHealth.await())
        assertTrue(connected.await())
        assertEquals(AuthHealth.VALID, health.get(provider.credentialOwnerId))
        assertEquals("synthetic-test-secret", credentials.get(provider.meta.id))
    }

    @Test
    fun validHealthWithExpiredLastResultOffersConnectionCheck() {
        assertEquals(
            ConnectionAction.CHECK_CONNECTION,
            connectionAction(AuthHealth.VALID, CheckInStatus.LOGIN_EXPIRED)
        )
        assertEquals(
            ConnectionAction.MANAGE_CONNECTION,
            connectionAction(AuthHealth.VALID, CheckInStatus.SUCCESS)
        )
        assertEquals(
            ConnectionAction.RECONNECT,
            connectionAction(AuthHealth.EXPIRED, CheckInStatus.SUCCESS)
        )
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
}
