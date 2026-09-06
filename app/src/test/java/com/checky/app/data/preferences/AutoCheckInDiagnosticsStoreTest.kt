package com.checky.app.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.checky.app.domain.model.CheckInSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class AutoCheckInDiagnosticsStoreTest {
    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStoreAutoCheckInDiagnosticsStore

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val file = File(
            System.getProperty("java.io.tmpdir"),
            "checky_auto_diag_${UUID.randomUUID()}.preferences_pb"
        )
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file }
        )
        store = DataStoreAutoCheckInDiagnosticsStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun startClearsPlannedAndTerminalPersistsOnlyTypedAggregateCounts() = runBlocking {
        store.recordPlannedNext(100L)
        store.recordDailyStart(200L)

        val started = store.diagnostics.first()
        assertNull(started.plannedNextEpochMillis)
        assertEquals(200L, started.lastStartEpochMillis)
        assertNull(started.lastOutcome)
        assertNull(started.lastFinishEpochMillis)

        store.recordDailyTerminal(
            outcome = AutoCheckInDiagnosticOutcome.COMPLETED,
            finishEpochMillis = 300L,
            summary = CheckInSummary(
                total = 2,
                succeeded = 1,
                alreadyCheckedIn = 1,
                failed = 0,
                attention = 0,
                totalPoints = 10,
                totalXp = 20,
                durationMs = 30
            )
        )

        assertEquals(
            AutoCheckInDiagnostics(
                lastStartEpochMillis = 200L,
                lastOutcome = AutoCheckInDiagnosticOutcome.COMPLETED,
                lastFinishEpochMillis = 300L,
                completedTotal = 2,
                completedSucceeded = 1,
                completedAlreadyCheckedIn = 1,
                completedFailed = 0,
                completedAttention = 0
            ),
            store.diagnostics.first()
        )
    }
}
