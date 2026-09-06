package com.checky.app.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.checky.app.domain.AuthHealth
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DataStoreAuthHealthStoreTest {
    @Test
    fun healthSurvivesASecondStoreInstance() = runTest {
        val file = File.createTempFile("checky_auth_health", ".preferences_pb").apply { deleteOnExit() }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file }
        )

        DataStoreAuthHealthStore(dataStore).set("owner", AuthHealth.EXPIRED)

        assertEquals(AuthHealth.EXPIRED, DataStoreAuthHealthStore(dataStore).get("owner"))
        assertEquals(true, DataStoreAuthHealthStore(dataStore).hasRecord("owner"))
    }
}
