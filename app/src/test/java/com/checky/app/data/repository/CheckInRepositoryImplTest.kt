package com.checky.app.data.repository

import com.checky.app.data.local.CheckyDao
import com.checky.app.data.local.entity.CheckInRecordEntity
import com.checky.app.data.local.entity.ServiceEntity
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInOutcome
import com.checky.app.domain.model.CheckInResult
import com.checky.app.domain.model.CheckInStatus
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.Reward
import com.checky.app.domain.model.RewardType
import com.checky.app.domain.providers.MiyousheCommunityProvider
import com.checky.app.domain.providers.MiyousheProvider
import com.checky.app.domain.providers.TaygedoCommunityProvider
import com.checky.app.domain.providers.TaygedoNteProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of the Room-backed repository through an in-memory fake DAO:
 * persistence of results, enable flags, and fail-safe mapping of malformed
 * persisted values — against the real provider catalog.
 */
class CheckInRepositoryImplTest {

    private val metas = listOf(
        MiyousheProvider.META,
        MiyousheCommunityProvider.META,
        TaygedoNteProvider.META,
        TaygedoCommunityProvider.META
    )

    private fun repoWith(dao: FakeCheckyDao = FakeCheckyDao()) =
        Pair(CheckInRepositoryImpl(dao, metas), dao)

    private fun successResult(serviceId: String) = CheckInResult(
        serviceId = serviceId,
        serviceName = "Miyoushe Genshin",
        outcome = CheckInOutcome.Success(
            "Done", "SUCCESS", Reward(RewardType.POINTS, 30)
        ),
        timestamp = 123_456L,
        durationMs = 42L
    )

    @Test
    fun saveResultPersistsRecordAndServiceLastResult() = runTest {
        val (repo, dao) = repoWith()
        repo.saveResult(successResult("miyoushe_genshin_experimental"))

        val record = dao.observeRecords().first().single()
        assertEquals("miyoushe_genshin_experimental", record.serviceId)
        assertEquals(CheckInStatus.SUCCESS.name, record.status)
        assertEquals(RewardType.POINTS.name, record.rewardType)
        assertEquals(30, record.rewardAmount)
        assertEquals("SUCCESS", record.diagnosticCode)
        assertEquals(42L, record.durationMs)

        val service = dao.getService("miyoushe_genshin_experimental")
        assertNotNull(service)
        assertEquals(CheckInStatus.SUCCESS.name, service!!.lastStatus)
        assertEquals(30, service.lastRewardAmount)
        assertEquals("Done", service.lastMessage)
        assertEquals(123_456L, service.lastTimestamp)
    }

    @Test
    fun saveResultKeepsExistingEnabledFlag() = runTest {
        val (repo, _) = repoWith()
        repo.setEnabled("miyoushe_genshin_experimental", false)
        repo.saveResult(successResult("miyoushe_genshin_experimental"))

        val snapshot = repo.getService("miyoushe_genshin_experimental")
        assertNotNull(snapshot)
        assertFalse(snapshot!!.isEnabled)
        assertEquals(CheckInStatus.SUCCESS, snapshot.lastStatus)
    }

    @Test
    fun saveResultDoesNotSelectProviderWhenNoServiceRowExists() = runTest {
        val (repo, _) = repoWith()
        // The community provider defaults to disabled and has no row yet.
        repo.saveResult(successResult("miyoushe_community_signin"))

        val snapshot = repo.getService("miyoushe_community_signin")
        assertNotNull(snapshot)
        assertFalse(snapshot!!.isEnabled)
    }

    @Test
    fun setEnabledPersistsFlagAndIgnoresUnknownServices() = runTest {
        val (repo, dao) = repoWith()
        repo.setEnabled("taygedo_nte", false)
        repo.setEnabled("unknown-id", true)

        assertFalse(dao.getService("taygedo_nte")!!.isEnabled)
        assertNull(dao.getService("unknown-id"))
    }

    @Test
    fun getServiceReturnsNullWhenNothingPersisted() = runTest {
        val (repo, _) = repoWith()
        val snapshot = repo.getService("taygedo_nte")

        assertNull(snapshot)
    }

    @Test
    fun freshInstallHasNoConnectedServices() = runTest {
        val (repo, _) = repoWith()

        assertTrue(repo.observeServices().first().isEmpty())
    }

    @Test
    fun getServiceReturnsNullForUnknownServiceId() = runTest {
        val (repo, _) = repoWith()
        assertNull(repo.getService("does-not-exist"))
    }

    @Test
    fun observeServicesMergesCatalogWithPersistedState() = runTest {
        val (repo, _) = repoWith()
        repo.setEnabled("taygedo_nte", false)
        repo.saveResult(successResult("miyoushe_genshin_experimental"))

        val snapshots = repo.observeServices().first()

        // Only explicitly persisted services are connected services.
        assertEquals(listOf("taygedo_nte", "miyoushe_genshin_experimental"), snapshots.map { it.serviceId })
        val nte = snapshots.first { it.serviceId == "taygedo_nte" }
        assertFalse(nte.isEnabled)
        val genshin = snapshots.first { it.serviceId == "miyoushe_genshin_experimental" }
        assertEquals(CheckInStatus.SUCCESS, genshin.lastStatus)
        assertEquals(MiyousheProvider.META.displayName, genshin.displayName)
    }

    @Test
    fun malformedPersistedValuesMapFailSafe() = runTest {
        val (repo, dao) = repoWith()
        dao.upsertService(
            ServiceEntity(
                serviceId = "taygedo_nte",
                displayName = "异环游戏签到",
                isEnabled = true,
                lastStatus = "NOT_A_STATUS",
                lastRewardType = "NOT_A_REWARD",
                lastRewardAmount = 7,
                lastMessage = "stale",
                lastTimestamp = 1L
            )
        )
        dao.insertRecord(
            CheckInRecordEntity(
                id = "r1",
                serviceId = "taygedo_nte",
                serviceName = "异环游戏签到",
                status = "NOT_A_STATUS",
                rewardType = "NOT_A_REWARD",
                rewardAmount = 7,
                message = "stale",
                diagnosticCode = "X",
                durationMs = 0L,
                timestamp = 1L
            )
        )

        val snapshot = repo.observeServices().first().first { it.serviceId == "taygedo_nte" }
        assertNull(snapshot.lastStatus)
        assertNull(snapshot.lastReward)

        val record = repo.observeRecords().first().single()
        assertEquals(CheckInStatus.FAILED, record.status)
        assertEquals(Reward(RewardType.NONE, 0), record.reward)
    }

    @Test
    fun clearHistoryRemovesRecordsButKeepsServices() = runTest {
        val (repo, dao) = repoWith()
        repo.setEnabled("taygedo_nte", true)
        repo.saveResult(successResult("taygedo_nte"))
        repo.clearHistory()

        assertEquals(0, dao.countRecords())
        assertTrue(dao.countServices() > 0)
    }
}

/** In-memory CheckyDao fake with StateFlow-backed observable queries. */
private class FakeCheckyDao : CheckyDao {
    private val records = MutableStateFlow<List<CheckInRecordEntity>>(emptyList())
    private val services = MutableStateFlow<List<ServiceEntity>>(emptyList())

    override fun observeRecords(): Flow<List<CheckInRecordEntity>> = records

    override suspend fun getRecords(): List<CheckInRecordEntity> = records.value

    override suspend fun insertRecord(entity: CheckInRecordEntity) {
        records.value = records.value + entity
    }

    override suspend fun clearRecords() {
        records.value = emptyList()
    }

    override suspend fun countRecords(): Int = records.value.size

    override suspend fun countServices(): Int = services.value.size

    override suspend fun clearServices() {
        services.value = emptyList()
    }

    override fun observeServices(): Flow<List<ServiceEntity>> = services

    override suspend fun getService(id: String): ServiceEntity? =
        services.value.firstOrNull { it.serviceId == id }

    override suspend fun upsertService(entity: ServiceEntity) {
        services.value = services.value.filterNot { it.serviceId == entity.serviceId } + entity
    }

    override suspend fun updateLastResult(
        id: String,
        status: String,
        rewardType: String?,
        rewardAmount: Int,
        message: String?,
        timestamp: Long
    ) {
        val current = getService(id) ?: return
        upsertService(
            current.copy(
                lastStatus = status,
                lastRewardType = rewardType,
                lastRewardAmount = rewardAmount,
                lastMessage = message,
                lastTimestamp = timestamp
            )
        )
    }
}
