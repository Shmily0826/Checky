package com.checky.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted per-service state: enable flag plus a cache of the last check-in result.
 * The list of available services itself is defined in code (ProviderMeta); this
 * table only stores what can change at runtime.
 */
@Entity(tableName = "services")
data class ServiceEntity(
    @PrimaryKey @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean,
    @ColumnInfo(name = "last_status") val lastStatus: String?,
    @ColumnInfo(name = "last_reward_type") val lastRewardType: String?,
    @ColumnInfo(name = "last_reward_amount") val lastRewardAmount: Int,
    @ColumnInfo(name = "last_message") val lastMessage: String?,
    @ColumnInfo(name = "last_timestamp") val lastTimestamp: Long?
)
