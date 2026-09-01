package com.checky.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How "Check in all" runs the providers. */
enum class RunMode { PARALLEL, SEQUENTIAL }

data class UserPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val onboardingComplete: Boolean = false,
    val displayName: String = "Buddy",
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 9,
    val reminderMinute: Int = 0,
    val autoCheckInEnabled: Boolean = false,
    val autoCheckInHour: Int = 3,
    val autoCheckInMinute: Int = 0,
    /** Post a notification with the auto check-in result (success/failure summary). */
    val checkInResultNotify: Boolean = true,
    val runMode: RunMode = RunMode.PARALLEL
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @Named("userPrefs") private val dataStore: DataStore<Preferences>
) {
    val preferences: Flow<UserPreferences> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            UserPreferences(
                themeMode = ThemeMode.valueOf(prefs[KEY_THEME] ?: ThemeMode.SYSTEM.name),
                onboardingComplete = prefs[KEY_ONBOARDING] ?: false,
                displayName = prefs[KEY_NAME] ?: "Buddy",
                reminderEnabled = prefs[KEY_REMINDER_ENABLED] ?: false,
                reminderHour = prefs[KEY_REMINDER_HOUR] ?: 9,
                reminderMinute = prefs[KEY_REMINDER_MINUTE] ?: 0,
                autoCheckInEnabled = prefs[KEY_AUTO_ENABLED] ?: false,
                autoCheckInHour = prefs[KEY_AUTO_HOUR] ?: 3,
                autoCheckInMinute = prefs[KEY_AUTO_MINUTE] ?: 0,
                checkInResultNotify = prefs[KEY_RESULT_NOTIFY] ?: true,
                runMode = RunMode.valueOf(prefs[KEY_RUN_MODE] ?: RunMode.PARALLEL.name)
            )
        }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setOnboardingComplete(value: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING] = value }
    }

    suspend fun setDisplayName(name: String) {
        dataStore.edit { it[KEY_NAME] = name }
    }

    suspend fun setReminderEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_REMINDER_ENABLED] = enabled }
    }

    suspend fun setReminderTime(hour: Int, minute: Int) {
        dataStore.edit {
            it[KEY_REMINDER_HOUR] = hour
            it[KEY_REMINDER_MINUTE] = minute
        }
    }

    suspend fun setAutoCheckInEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_ENABLED] = enabled }
    }

    suspend fun setAutoCheckInTime(hour: Int, minute: Int) {
        dataStore.edit {
            it[KEY_AUTO_HOUR] = hour
            it[KEY_AUTO_MINUTE] = minute
        }
    }

    suspend fun setCheckInResultNotify(enabled: Boolean) {
        dataStore.edit { it[KEY_RESULT_NOTIFY] = enabled }
    }

    suspend fun setRunMode(mode: RunMode) {
        dataStore.edit { it[KEY_RUN_MODE] = mode.name }
    }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_ONBOARDING = booleanPreferencesKey("onboarding_complete")
        private val KEY_NAME = stringPreferencesKey("display_name")
        private val KEY_REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
        private val KEY_REMINDER_HOUR = intPreferencesKey("reminder_hour")
        private val KEY_REMINDER_MINUTE = intPreferencesKey("reminder_minute")
        private val KEY_AUTO_ENABLED = booleanPreferencesKey("auto_checkin_enabled")
        private val KEY_AUTO_HOUR = intPreferencesKey("auto_checkin_hour")
        private val KEY_AUTO_MINUTE = intPreferencesKey("auto_checkin_minute")
        private val KEY_RESULT_NOTIFY = booleanPreferencesKey("checkin_result_notify")
        private val KEY_RUN_MODE = stringPreferencesKey("run_mode")
    }
}
