package com.checky.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.checky.app.di.AppModule
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckyDatabaseMigrationTest {
    @Test
    fun productionMigrationFromV1AndV2PreservesHistoryAndSupportsCopyText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (version in listOf(1, 2)) {
            context.deleteDatabase(CheckyDatabase.DATABASE_NAME)
            val schema = JSONObject(File("schemas/${CheckyDatabase::class.java.name}/$version.json").readText())
                .getJSONObject("database")
            val path = context.getDatabasePath(CheckyDatabase.DATABASE_NAME)
            path.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
                val entities = schema.getJSONArray("entities")
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    legacy.execSQL(entity.getString("createSql")
                        .replace("\${TABLE_NAME}", entity.getString("tableName")))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (index in 0 until setup.length()) legacy.execSQL(setup.getString(index))
                val extraColumns = if (version == 2) ", diagnostic_code, duration_ms" else ""
                val extraValues = if (version == 2) ", 'SUCCESS', 12" else ""
                legacy.execSQL("INSERT INTO check_in_records " +
                    "(id, service_id, service_name, status, reward_type, reward_amount, message, timestamp$extraColumns) " +
                    "VALUES ('legacy', 'taptap_game_sign', 'TapTap', 'SUCCESS', 'NONE', 0, 'done', 43$extraValues)")
                legacy.execSQL("INSERT INTO services (service_id, display_name, is_enabled, last_reward_amount) " +
                    "VALUES ('taptap_game_sign', 'TapTap', 1, 0)")
                legacy.version = version
            }

            val database = AppModule.provideDatabase(context)
            try {
                val dao = database.dao()
                val old = dao.getRecords().single()
                assertEquals("legacy", old.id)
                assertEquals("done", old.message)
                assertEquals(43L, old.timestamp)
                assertEquals(if (version == 2) "SUCCESS" else "", old.diagnosticCode)
                assertEquals(if (version == 2) 12L else 0L, old.durationMs)
                assertNull(old.localCopyText)
                assertEquals(true, dao.getService("taptap_game_sign")!!.isEnabled)
                dao.insertRecord(old.copy(id = "with-copy", localCopyText = "FAKE-MIGRATED-CODE"))
                assertEquals("FAKE-MIGRATED-CODE", dao.getRecords().single { it.id == "with-copy" }.localCopyText)
            } finally {
                database.close()
                context.deleteDatabase(CheckyDatabase.DATABASE_NAME)
            }
        }
    }
}
