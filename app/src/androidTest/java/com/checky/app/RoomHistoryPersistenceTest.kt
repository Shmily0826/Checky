package com.checky.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.checky.app.data.local.CheckyDao
import com.checky.app.data.local.CheckyDatabase
import com.checky.app.data.local.entity.CheckInRecordEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room persistence coverage — instrumented because Room requires Android.
 * Run with: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class RoomHistoryPersistenceTest {

    private lateinit var db: CheckyDatabase
    private lateinit var dao: CheckyDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CheckyDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.dao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun savesAndReadsRecordIncludingSafeFields() = runBlocking {
        dao.insertRecord(record(id = "r1", diagnosticCode = "SUCCESS", durationMs = 1234L))

        val records = dao.getRecords()
        assertEquals(1, records.size)
        assertEquals("SUCCESS", records[0].diagnosticCode)
        assertEquals(1234L, records[0].durationMs)
        assertEquals("gamepass", records[0].serviceId)
    }

    @Test
    fun historyOrdersNewestFirst() = runBlocking {
        dao.insertRecord(record(id = "old", timestamp = 100L))
        dao.insertRecord(record(id = "new", timestamp = 200L))

        val records = dao.getRecords()
        assertEquals(listOf("new", "old"), records.map { it.id })
    }

    @Test
    fun clearHistoryRemovesAllRecordsButKeepsServices() = runBlocking {
        dao.insertRecord(record(id = "r1"))
        dao.insertRecord(record(id = "r2"))
        dao.upsertService(
            com.checky.app.data.local.entity.ServiceEntity(
                serviceId = "gamepass", displayName = "GamePass Daily", isEnabled = true,
                lastStatus = null, lastRewardType = null, lastRewardAmount = 0,
                lastMessage = null, lastTimestamp = null
            )
        )

        dao.clearRecords()

        assertEquals(0, dao.countRecords())
        assertEquals(1, dao.countServices())
    }

    @Test
    fun migrationFromV1KeepsExistingHistory() {
        val helper = MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            CheckyDatabase::class.java
        )
        helper.createDatabase(TEST_DB_NAME, 1).apply {
            execSQL(
                "INSERT INTO check_in_records (id, service_id, service_name, status, reward_type, reward_amount, message, timestamp) " +
                    "VALUES ('legacy', 'studyclub', 'StudyClub', 'ALREADY_CHECKED_IN', 'EXPERIENCE', 5, 'already', 42)"
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TEST_DB_NAME,
            2,
            true,
            CheckyDatabase.MIGRATION_1_2
        )
        migrated.query("SELECT * FROM check_in_records WHERE id = 'legacy'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("legacy", cursor.getString(cursor.getColumnIndexOrThrow("id")))
            // New columns defaulted safely.
            assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("diagnostic_code")))
            assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow("duration_ms")))
        }
        migrated.close()
    }

    private fun record(
        id: String,
        diagnosticCode: String = "SUCCESS",
        durationMs: Long = 500L,
        timestamp: Long = 1L
    ) = CheckInRecordEntity(
        id = id,
        serviceId = "gamepass",
        serviceName = "GamePass Daily",
        status = "SUCCESS",
        rewardType = "POINTS",
        rewardAmount = 20,
        message = "claimed",
        diagnosticCode = diagnosticCode,
        durationMs = durationMs,
        timestamp = timestamp
    )

    private companion object {
        const val TEST_DB_NAME = "migration-test.db"
    }
}
