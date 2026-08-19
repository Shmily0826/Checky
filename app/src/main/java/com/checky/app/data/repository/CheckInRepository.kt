package com.checky.app.data.repository

import com.checky.app.data.model.CheckInRecord
import com.checky.app.data.model.ServiceSnapshot
import com.checky.app.domain.model.CheckInResult
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for persisted check-in data.
 * Implemented with Room + DataStore; faked in unit tests.
 */
interface CheckInRepository {
    fun observeRecords(): Flow<List<CheckInRecord>>
    fun observeServices(): Flow<List<ServiceSnapshot>>
    suspend fun getService(id: String): ServiceSnapshot?
    suspend fun setEnabled(serviceId: String, enabled: Boolean)
    suspend fun saveResult(result: CheckInResult)
    suspend fun clearHistory()

    /** Seed the 3 mock services + their demo results on first launch. */
    suspend fun ensureSeeded()

    /** Wipe history and re-apply the demo seed (used by Settings > Reset demo data). */
    suspend fun resetDemoData()
}
