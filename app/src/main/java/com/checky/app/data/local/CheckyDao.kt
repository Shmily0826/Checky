package com.checky.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.checky.app.data.local.entity.CheckInRecordEntity
import com.checky.app.data.local.entity.ServiceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CheckyDao {

    @Query("SELECT * FROM check_in_records ORDER BY timestamp DESC")
    fun observeRecords(): Flow<List<CheckInRecordEntity>>

    @Query("SELECT * FROM check_in_records ORDER BY timestamp DESC")
    suspend fun getRecords(): List<CheckInRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(entity: CheckInRecordEntity)

    @Query("DELETE FROM check_in_records")
    suspend fun clearRecords()

    @Query("SELECT COUNT(*) FROM check_in_records")
    suspend fun countRecords(): Int

    @Query("SELECT COUNT(*) FROM services")
    suspend fun countServices(): Int

    @Query("DELETE FROM services")
    suspend fun clearServices()

    @Query("SELECT * FROM services")
    fun observeServices(): Flow<List<ServiceEntity>>

    @Query("SELECT * FROM services WHERE service_id = :id")
    suspend fun getService(id: String): ServiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertService(entity: ServiceEntity)

    @Query("UPDATE services SET last_status = :status, last_reward_type = :rewardType, last_reward_amount = :rewardAmount, last_message = :message, last_timestamp = :timestamp WHERE service_id = :id")
    suspend fun updateLastResult(
        id: String,
        status: String,
        rewardType: String?,
        rewardAmount: Int,
        message: String?,
        timestamp: Long
    )
}
