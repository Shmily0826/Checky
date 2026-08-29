package com.checky.app.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.checky.app.data.local.entity.CheckInRecordEntity
import com.checky.app.data.local.entity.ServiceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric coverage of the real Room DAO (in-memory database). The JVM
 * unit tests cover the repository against a fake DAO; this test validates
 * the SQL layer itself — upserts, ordering, counts and partial updates —
 * without needing a device or emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckyDaoTest {

    private lateinit var db: CheckyDatabase
    private lateinit var dao: CheckyDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Direct executors keep every DB access on the test thread. Room's
        // default IO executors spawn real arch_disk_io threads that Robolectric's
        // single-connection SQLite rejects ("Illegal connection pointer") and
        // whose exceptions leak into other test classes.
        val direct = java.util.concurrent.Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, CheckyDatabase::class.java)
            .setQueryExecutor(direct)
            .setTransactionExecutor(direct)
            .allowMainThreadQueries()
            .build()
        dao = db.dao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun record(
        id: String,
        timestamp: Long,
        status: String = "SUCCESS"
    ) = CheckInRecordEntity(
        id = id,
        serviceId = "gamepass",
        serviceName = "GamePass Daily",
        status = status,
        rewardType = "POINTS",
        rewardAmount = 20,
        message = "ok",
        diagnosticCode = "SUCCESS",
        durationMs = 100L,
        timestamp = timestamp
    )

    @Test
    fun upsertServiceReplacesByServiceId() = runBlocking {
        dao.upsertService(service("gamepass", isEnabled = true))
        dao.upsertService(service("gamepass", isEnabled = false))

        assertEquals(1, dao.countServices())
        assertFalse(dao.getService("gamepass")!!.isEnabled)
    }

    @Test
    fun getServiceReturnsNullForMissingRow() = runBlocking {
        assertNull(dao.getService("nope"))
    }

    @Test
    fun observeRecordsOrdersNewestFirst() = runBlocking {
        dao.insertRecord(record("old", timestamp = 1L))
        dao.insertRecord(record("new", timestamp = 999L))

        val records = dao.observeRecords().first()

        assertEquals(listOf("new", "old"), records.map { it.id })
    }

    @Test
    fun insertRecordReplacesSameId() = runBlocking {
        dao.insertRecord(record("r1", timestamp = 1L))
        dao.insertRecord(record("r1", timestamp = 2L, status = "FAILED"))

        assertEquals(1, dao.countRecords())
        assertEquals("FAILED", dao.getRecords().single().status)
    }

    @Test
    fun clearRecordsKeepsServices() = runBlocking {
        dao.insertRecord(record("r1", timestamp = 1L))
        dao.upsertService(service("gamepass", isEnabled = true))

        dao.clearRecords()

        assertEquals(0, dao.countRecords())
        assertEquals(1, dao.countServices())
    }

    @Test
    fun clearServicesEmptiesTheTable() = runBlocking {
        dao.upsertService(service("gamepass", isEnabled = true))
        dao.upsertService(service("cloudbox", isEnabled = false))

        dao.clearServices()

        assertEquals(0, dao.countServices())
    }

    @Test
    fun updateLastResultChangesOnlyResultColumns() = runBlocking {
        dao.upsertService(service("gamepass", isEnabled = false))

        dao.updateLastResult(
            id = "gamepass",
            status = "ALREADY_CHECKED_IN",
            rewardType = "EXPERIENCE",
            rewardAmount = 5,
            message = "already",
            timestamp = 42L
        )

        val updated = dao.getService("gamepass")!!
        // Enable flag must survive the partial update.
        assertFalse(updated.isEnabled)
        assertEquals("ALREADY_CHECKED_IN", updated.lastStatus)
        assertEquals("EXPERIENCE", updated.lastRewardType)
        assertEquals(5, updated.lastRewardAmount)
        assertEquals("already", updated.lastMessage)
        assertEquals(42L, updated.lastTimestamp)
    }

    @Test
    fun updateLastResultIsNoOpForMissingService() = runBlocking {
        dao.updateLastResult("ghost", "FAILED", null, 0, "x", 1L)
        assertNull(dao.getService("ghost"))
    }

    @Test
    fun persistedRowsRoundTripThroughRoomMapping() = runBlocking {
        dao.upsertService(
            ServiceEntity(
                serviceId = "cloudbox",
                displayName = "CloudBox",
                isEnabled = true,
                lastStatus = "LOGIN_EXPIRED",
                lastRewardType = null,
                lastRewardAmount = 0,
                lastMessage = null,
                lastTimestamp = null
            )
        )
        val loaded = dao.getService("cloudbox")!!

        assertEquals("CloudBox", loaded.displayName)
        assertTrue(loaded.isEnabled)
        assertEquals("LOGIN_EXPIRED", loaded.lastStatus)
        assertNull(loaded.lastRewardType)
        assertNull(loaded.lastMessage)
        assertNull(loaded.lastTimestamp)
    }

    private fun service(id: String, isEnabled: Boolean) = ServiceEntity(
        serviceId = id,
        displayName = id,
        isEnabled = isEnabled,
        lastStatus = null,
        lastRewardType = null,
        lastRewardAmount = 0,
        lastMessage = null,
        lastTimestamp = null
    )
}
