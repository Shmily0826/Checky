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
import com.checky.app.domain.providers.CloudBoxProvider
import com.checky.app.domain.providers.GamePassDailyProvider
import com.checky.app.domain.providers.StudyClubProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM coverage of the Room-backed repository through an in-memory fake DAO:
 * persistence of results, enable flags, seeding, and fail-safe mapping of
 * malformed persisted values.
 */
class CheckInRepositoryImplTest {

    private val metas = listOf(
        GamePassDailyProvider.META,
        CloudBoxProvider.META,
        StudyClubProvider.META,
        ProviderMeta(
            id = "extra",
            displayName = "Extra Service",
            description = "",
            category = "Other",
            iconKey = "star",
            accentColor = 0xFF000000,
            isEnabledByDefault = false
        )
    )

    private fun repoWith(dao: FakeCheckyDao = FakeCheckyDao()) =
        Pair(CheckInRepositoryImpl(dao, metas), dao)

    private fun successResult(serviceId: String = "gamepass") = CheckInResult(
        serviceId = serviceId,
        serviceName = "GamePass Daily",
        outcome = CheckInOutcome.Success(
            "Done", "SUCCESS", Reward(RewardType.POINTS, 20)
        ),
        timestamp = 123_456L,
        durationMs = 42L
    )

    @Test
    fun saveResultPersistsRecordAndServiceLastResult() = runTest {
        val (repo, dao) = repoWith()
        repo.saveResult(successResult())

        val record = dao.observeRecords().first().single()
        assertEquals("gamepass", record.serviceId)
        assertEquals(CheckInStatus.SUCCESS.name, record.status)
        assertEquals(RewardType.POINTS.name, record.rewardType)
        assertEquals(20, record.rewardAmount)
        assertEquals("SUCCESS", record.diagnosticCode)
        assertEquals(42L, record.durationMs)

        val service = dao.getService("gamepass")
        assertNotNull(service)
        assertEquals(CheckInStatus.SUCCESS.name, service!!.lastStatus)
        assertEquals(20, service.lastRewardAmount)
        assertEquals("Done", service.lastMessage)
        assertEquals(123_456L, service.lastTimestamp)
    }

    @Test
    fun saveResultKeepsExistingEnabledFlag() = runTest {
        val (repo, _) = repoWith()
        repo.setEnabled("gamepass", false)
        repo.saveResult(successResult())

        val snapshot = repo.getService("gamepass")
        assertNotNull(snapshot)
        assertFalse(snapshot!!.isEnabled)
        assertEquals(CheckInStatus.SUCCESS, snapshot.lastStatus)
    }

    @Test
    fun saveResultDefaultsEnabledFromMetaWhenNoServiceRowExists() = runTest {
        val (repo, _) = repoWith()
        // "extra" meta defaults to disabled and has no row yet.
        repo.saveResult(successResult(serviceId = "extra"))

        val snapshot = repo.getService("extra")
        assertNotNull(snapshot)
        assertFalse(snapshot!!.isEnabled)
    }

    @Test
    fun setEnabledPersistsFlagAndIgnoresUnknownServices() = runTest {
        val (repo, dao) = repoWith()
        repo.setEnabled("studyclub", false)
        repo.setEnabled("unknown-id", true)

        assertFalse(dao.getService("studyclub")!!.isEnabled)
        assertNull(dao.getService("unknown-id"))
    }

    @Test
    fun getServiceReturnsMetaDefaultsWhenNothingPersisted() = runTest {
        val (repo, _) = repoWith()
        val snapshot = repo.getService("gamepass")

        assertNotNull(snapshot)
        assertEquals("gamepass", snapshot!!.serviceId)
        assertEquals(GamePassDailyProvider.META.isEnabledByDefault, snapshot.isEnabled)
        assertNull(snapshot.lastStatus)
        assertNull(snapshot.lastReward)
        assertNull(snapshot.lastMessage)
    }

    @Test
    fun getServiceReturnsNullForUnknownServiceId() = runTest {
        val (repo, _) = repoWith()
        assertNull(repo.getService("does-not-exist"))
    }

    @Test
    fun observeServicesMergesCatalogWithPersistedState() = runTest {
        val (repo, _) = repoWith()
        repo.setEnabled("gamepass", false)
        repo.saveResult(successResult())

        val snapshots = repo.observeServices().first()

        // Every catalog entry is present, ordered by the meta list.
        assertEquals(metas.map { it.id }, snapshots.map { it.serviceId })
        val gamepass = snapshots.first { it.serviceId == "gamepass" }
        assertFalse(gamepass.isEnabled)
        assertEquals(CheckInStatus.SUCCESS, gamepass.lastStatus)
        val extra = snapshots.first { it.serviceId == "extra" }
        assertEquals("Extra Service", extra.displayName)
        assertFalse(extra.isEnabled)
    }

    @Test
    fun malformedPersistedValuesMapFailSafe() = runTest {
        val (repo, dao) = repoWith()
        dao.upsertService(
            ServiceEntity(
                serviceId = "gamepass",
                displayName = "GamePass Daily",
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
                serviceId = "gamepass",
                serviceName = "GamePass Daily",
                status = "NOT_A_STATUS",
                rewardType = "NOT_A_REWARD",
                rewardAmount = 7,
                message = "stale",
                diagnosticCode = "X",
                durationMs = 0L,
                timestamp = 1L
            )
        )

        val snapshot = repo.observeServices().first().first { it.serviceId == "gamepass" }
        assertNull(snapshot.lastStatus)
        assertNull(snapshot.lastReward)

        val record = repo.observeRecords().first().single()
        assertEquals(CheckInStatus.FAILED, record.status)
        assertEquals(Reward(RewardType.NONE, 0), record.reward)
    }

    @Test
    fun clearHistoryRemovesRecordsButKeepsServices() = runTest {
        val (repo, dao) = repoWith()
        repo.ensureSeeded()
        repo.clearHistory()

        assertEquals(0, dao.countRecords())
        assertTrue(dao.countServices() > 0)
    }

    @Test
    fun ensureSeededSeedsMockDataExactlyOnce() = runTest {
        val (repo, dao) = repoWith()
        repo.ensureSeeded()
        val recordsAfterFirst = dao.countRecords()
        val servicesAfterFirst = dao.countServices()
        repo.ensureSeeded()

        assertEquals(3, recordsAfterFirst)
        assertEquals(3, servicesAfterFirst)
        assertEquals(recordsAfterFirst, dao.countRecords())
        assertEquals(servicesAfterFirst, dao.countServices())
    }

    @Test
    fun resetDemoDataWipesAndReseeds() = runTest {
        val (repo, dao) = repoWith()
        repo.ensureSeeded()
        repo.setEnabled("extra", true)
        repo.saveResult(successResult())
        assertTrue(dao.countRecords() > 3)
        assertTrue(dao.countServices() > 3)

        repo.resetDemoData()

        assertEquals(3, dao.countRecords())
        assertEquals(3, dao.countServices())
        // The re-seed restores the scripted cloudbox expired state.
        val cloudbox = repo.observeServices().first().first { it.serviceId == "cloudbox" }
        assertEquals(CheckInStatus.LOGIN_EXPIRED, cloudbox.lastStatus)
    }

    @Test
    fun seededSnapshotsExposeScriptedOutcomes() = runTest {
        val (repo, _) = repoWith()
        repo.ensureSeeded()

        val snapshots = repo.observeServices().first()
        val byId = snapshots.associateBy(ServiceSnapshot::serviceId)

        assertEquals(CheckInStatus.SUCCESS, byId.getValue("gamepass").lastStatus)
        assertEquals(CheckInStatus.LOGIN_EXPIRED, byId.getValue("cloudbox").lastStatus)
        assertEquals(CheckInStatus.ALREADY_CHECKED_IN, byId.getValue("studyclub").lastStatus)
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
