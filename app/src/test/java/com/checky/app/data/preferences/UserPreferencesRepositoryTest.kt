package com.checky.app.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM coverage of the DataStore-backed user preferences: defaults, setter
 * round-trips and snapshot consistency.
 */
class UserPreferencesRepositoryTest {

    @Test
    fun freshStoreExposesDefaults() = runTest {
        val repo = prefsRepo(this)
        val prefs = repo.preferences.first()

        assertEquals(ThemeMode.SYSTEM, prefs.themeMode)
        assertEquals(false, prefs.onboardingComplete)
        assertEquals("Buddy", prefs.displayName)
        assertEquals(false, prefs.reminderEnabled)
        assertEquals(9, prefs.reminderHour)
        assertEquals(0, prefs.reminderMinute)
        assertEquals(false, prefs.autoCheckInEnabled)
        assertEquals(3, prefs.autoCheckInHour)
        assertEquals(0, prefs.autoCheckInMinute)
        assertEquals(RunMode.PARALLEL, prefs.runMode)
    }

    @Test
    fun everySetterRoundTrips() = runTest {
        val repo = prefsRepo(this)

        repo.setThemeMode(ThemeMode.DARK)
        repo.setOnboardingComplete(true)
        repo.setDisplayName("Checky Tester")
        repo.setReminderEnabled(true)
        repo.setReminderTime(8, 30)
        repo.setAutoCheckInEnabled(true)
        repo.setAutoCheckInTime(4, 15)
        repo.setRunMode(RunMode.SEQUENTIAL)

        val prefs = repo.preferences.first()
        assertEquals(ThemeMode.DARK, prefs.themeMode)
        assertTrue(prefs.onboardingComplete)
        assertEquals("Checky Tester", prefs.displayName)
        assertTrue(prefs.reminderEnabled)
        assertEquals(8, prefs.reminderHour)
        assertEquals(30, prefs.reminderMinute)
        assertTrue(prefs.autoCheckInEnabled)
        assertEquals(4, prefs.autoCheckInHour)
        assertEquals(15, prefs.autoCheckInMinute)
        assertEquals(RunMode.SEQUENTIAL, prefs.runMode)
    }

    @Test
    fun togglingBackAndForthEmitsLatestSnapshot() = runTest {
        val repo = prefsRepo(this)
        repo.setReminderEnabled(true)
        assertTrue(repo.preferences.first().reminderEnabled)
        repo.setReminderEnabled(false)
        assertFalse(repo.preferences.first().reminderEnabled)

        repo.setRunMode(RunMode.SEQUENTIAL)
        repo.setRunMode(RunMode.PARALLEL)
        assertEquals(RunMode.PARALLEL, repo.preferences.first().runMode)
    }

    private fun prefsRepo(testScope: TestScope): UserPreferencesRepository {
        val file = File.createTempFile("checky_user_prefs", ".preferences_pb").apply { deleteOnExit() }
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope.backgroundScope,
            produceFile = { file }
        )
        return UserPreferencesRepository(dataStore)
    }
}
