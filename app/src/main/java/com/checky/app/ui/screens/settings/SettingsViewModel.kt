package com.checky.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.data.preferences.RunMode
import com.checky.app.data.preferences.ThemeMode
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.data.work.ReminderWorker
import com.checky.app.data.work.AutoCheckInWorker
import com.checky.app.domain.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val repository: CheckInRepository,
    private val credentialStore: CredentialStore,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = userPreferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { userPreferencesRepository.setThemeMode(mode) }
    }

    fun setReminderEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setReminderEnabled(enabled)
            val prefs = userPreferencesRepository.preferences.first()
            if (enabled) {
                ReminderWorker.schedule(context, prefs.reminderHour, prefs.reminderMinute)
            } else {
                ReminderWorker.cancel(context)
            }
        }
    }

    fun setReminderTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setReminderTime(hour, minute)
            val prefs = userPreferencesRepository.preferences.first()
            if (prefs.reminderEnabled) {
                ReminderWorker.schedule(context, prefs.reminderHour, prefs.reminderMinute)
            }
        }
    }

    fun setAutoCheckInEnabled(enabled: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setAutoCheckInEnabled(enabled)
            val prefs = userPreferencesRepository.preferences.first()
            if (enabled) {
                AutoCheckInWorker.schedule(context, prefs.autoCheckInHour, prefs.autoCheckInMinute)
            } else {
                AutoCheckInWorker.cancel(context)
            }
        }
    }

    fun setAutoCheckInTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setAutoCheckInTime(hour, minute)
            val prefs = userPreferencesRepository.preferences.first()
            if (prefs.autoCheckInEnabled) {
                AutoCheckInWorker.schedule(context, prefs.autoCheckInHour, prefs.autoCheckInMinute)
            }
        }
    }

    fun setRunMode(mode: RunMode) {
        viewModelScope.launch { userPreferencesRepository.setRunMode(mode) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    fun deleteAllCredentials() {
        viewModelScope.launch { credentialStore.deleteAll() }
    }

    fun showOnboardingAgain() {
        viewModelScope.launch { userPreferencesRepository.setOnboardingComplete(false) }
    }
}
