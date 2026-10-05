package com.checky.app.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class AutoCheckInDailyTargetTest {
    @Test
    fun targetIsBoundedStableAcrossStoreInstancesAndChangesNextDay() = runTest {
        val file = File.createTempFile("checky_daily_target", ".preferences_pb").apply { deleteOnExit() }
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val store = DataStoreAutoCheckInDiagnosticsStore(dataStore)
        val today = LocalDate.of(2026, 9, 28)
        val base = today.atTime(3, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        var selections = 0
        val first = store.getOrCreateDailyTarget(today, today, base) { selections++; 5 }

        assertTrue(first - base in 0L..300_000L)
        assertEquals(first, DataStoreAutoCheckInDiagnosticsStore(dataStore)
            .getOrCreateDailyTarget(today, today, base) { selections++; -5 })
        assertEquals(1, selections)

        val tomorrow = today.plusDays(1)
        val nextBase = base + 86_400_000L
        val next = store.getOrCreateDailyTarget(tomorrow, today, nextBase) { -4 }
        assertEquals(-240_000L, next - nextBase)
    }

    @Test
    fun negativeJitterNearMidnightCannotMoveTargetToPreviousLocalDate() = runTest {
        val file = File.createTempFile("checky_daily_target_midnight", ".preferences_pb").apply { deleteOnExit() }
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        val store = DataStoreAutoCheckInDiagnosticsStore(dataStore)
        val zone = ZoneId.systemDefault()
        val scheduledDate = LocalDate.of(2026, 9, 28)
        val base = scheduledDate.atTime(0, 2).atZone(zone).toInstant().toEpochMilli()

        val target = store.getOrCreateDailyTarget(scheduledDate, scheduledDate, base) { -5 }

        assertEquals(scheduledDate, java.time.Instant.ofEpochMilli(target).atZone(zone).toLocalDate())
        assertEquals(scheduledDate.atStartOfDay(zone).toInstant().toEpochMilli(), target)
    }
}
