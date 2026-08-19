package com.checky.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.checky.app.domain.MockScenario
import com.checky.app.domain.MockScenarioStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Developer-only mock outcome override, persisted as a non-sensitive
 * preference. Never stores anything sensitive.
 */
@Singleton
class MockScenarioStoreImpl @Inject constructor(
    @Named("userPrefs") private val dataStore: DataStore<Preferences>
) : MockScenarioStore {

    override fun scenario(): Flow<MockScenario> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            runCatching { MockScenario.valueOf(prefs[KEY_SCENARIO] ?: MockScenario.DEFAULT.name) }
                .getOrDefault(MockScenario.DEFAULT)
        }

    override suspend fun setScenario(scenario: MockScenario) {
        dataStore.edit { it[KEY_SCENARIO] = scenario.name }
    }

    private companion object {
        val KEY_SCENARIO = stringPreferencesKey("mock_scenario")
    }
}
