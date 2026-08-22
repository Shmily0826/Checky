package com.checky.app.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.checky.app.domain.MockScenario
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * JVM coverage of the DataStore-backed developer scenario override, including
 * the fail-safe fallback when the persisted value is corrupted.
 */
class MockScenarioStoreImplTest {

    @Test
    fun defaultScenarioWhenNothingStored() = runTest {
        val store = scenarioStore(this)
        assertEquals(MockScenario.DEFAULT, store.scenario().first())
    }

    @Test
    fun setScenarioRoundTrips() = runTest {
        val store = scenarioStore(this)
        store.setScenario(MockScenario.NETWORK_FAILURE)
        assertEquals(MockScenario.NETWORK_FAILURE, store.scenario().first())

        store.setScenario(MockScenario.ACTION_REQUIRED)
        assertEquals(MockScenario.ACTION_REQUIRED, store.scenario().first())

        store.setScenario(MockScenario.DEFAULT)
        assertEquals(MockScenario.DEFAULT, store.scenario().first())
    }

    @Test
    fun corruptedStoredValueFallsBackToDefault() = runTest {
        val file = File.createTempFile("checky_scenario", ".preferences_pb").apply { deleteOnExit() }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file }
        )
        // Simulate a corrupted/unknown enum written by an older build.
        dataStore.edit { it[stringPreferencesKey("mock_scenario")] = "NOT_A_SCENARIO" }

        val store = MockScenarioStoreImpl(dataStore)
        assertEquals(MockScenario.DEFAULT, store.scenario().first())
    }

    private fun scenarioStore(testScope: TestScope): MockScenarioStoreImpl {
        val file = File.createTempFile("checky_scenario", ".preferences_pb").apply { deleteOnExit() }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { file }
        )
        return MockScenarioStoreImpl(dataStore)
    }
}
