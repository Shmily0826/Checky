package com.checky.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "check_in_records")
data class CheckInRecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "service_name") val serviceName: String,
    @ColumnInfo(name = "status") val status: String,
    @ColumnInfo(name = "reward_type") val rewardType: String,
    @ColumnInfo(name = "reward_amount") val rewardAmount: Int,
    @ColumnInfo(name = "message") val message: String,
    /** Non-sensitive error category, e.g. "AUTH_EXPIRED". */
    @ColumnInfo(name = "diagnostic_code") val diagnosticCode: String,
    /** Wall-clock duration of the attempt in ms. */
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "timestamp") val timestamp: Long
)
