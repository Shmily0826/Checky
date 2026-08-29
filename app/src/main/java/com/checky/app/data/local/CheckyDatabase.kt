package com.checky.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.checky.app.data.local.entity.CheckInRecordEntity
import com.checky.app.data.local.entity.ServiceEntity

@Database(
    entities = [CheckInRecordEntity::class, ServiceEntity::class],
    version = 2,
    exportSchema = true
)
abstract class CheckyDatabase : RoomDatabase() {
    abstract fun dao(): CheckyDao

    companion object {
        const val DATABASE_NAME = "checky.db"

        /** v1 -> v2: records gain a safe diagnostic category and duration. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE check_in_records ADD COLUMN diagnostic_code TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE check_in_records ADD COLUMN duration_ms INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
