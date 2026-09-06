package com.checky.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.AuthHealthStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/** Durable local auth metadata stored beside user preferences, never beside secrets. */
@Singleton
class DataStoreAuthHealthStore @Inject constructor(
    @param:Named("userPrefs") private val dataStore: DataStore<Preferences>
) : AuthHealthStore {
    override suspend fun get(ownerId: String): AuthHealth = runCatching {
        dataStore.data.first()[key(ownerId)]?.let { raw ->
            runCatching { AuthHealth.valueOf(raw) }.getOrDefault(AuthHealth.UNVERIFIED)
        } ?: AuthHealth.UNVERIFIED
    }.getOrDefault(AuthHealth.UNVERIFIED)

    override suspend fun hasRecord(ownerId: String): Boolean = runCatching {
        dataStore.data.first().contains(key(ownerId))
    }.getOrDefault(false)

    override suspend fun set(ownerId: String, health: AuthHealth) {
        dataStore.edit { it[key(ownerId)] = health.name }
    }

    override suspend fun clear(ownerId: String) {
        dataStore.edit { it.remove(key(ownerId)) }
    }

    private fun key(ownerId: String) = stringPreferencesKey("auth_health_$ownerId")
}
