package com.checky.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.data.preferences.RunMode
import com.checky.app.data.preferences.ThemeMode
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.data.preferences.AutoCheckInDiagnostics
import com.checky.app.data.preferences.AutoCheckInDiagnosticsStore
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.data.work.ReminderWorker
import com.checky.app.data.work.AutoCheckInWorker
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.background.BackgroundReliabilityReader
import com.checky.app.domain.background.BackgroundReliabilityReport
import com.checky.app.domain.background.BackgroundReliabilityClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val autoCheckInDiagnosticsStore: AutoCheckInDiagnosticsStore,
    private val repository: CheckInRepository,
    private val credentialStore: CredentialStore,
    private val backgroundReliabilityReader: BackgroundReliabilityReader,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _backgroundReliability = MutableStateFlow<BackgroundReliabilityReport?>(null)
    val backgroundReliability: StateFlow<BackgroundReliabilityReport?> = _backgroundReliability.asStateFlow()

    val preferences: StateFlow<UserPreferences> = userPreferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val autoCheckInDiagnostics: StateFlow<AutoCheckInDiagnostics> = autoCheckInDiagnosticsStore.diagnostics
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AutoCheckInDiagnostics())

    fun refreshBackgroundReliability() {
        _backgroundReliability.value = BackgroundReliabilityClassifier.classify(
            backgroundReliabilityReader.read()
        )
    }

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
                AutoCheckInWorker.schedule(
                    context,
                    prefs.autoCheckInHour,
                    prefs.autoCheckInMinute,
                    diagnostics = autoCheckInDiagnosticsStore
                )
            } else {
                AutoCheckInWorker.cancel(context, diagnostics = autoCheckInDiagnosticsStore)
            }
        }
    }

    fun setAutoCheckInTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            userPreferencesRepository.setAutoCheckInTime(hour, minute)
            val prefs = userPreferencesRepository.preferences.first()
            if (prefs.autoCheckInEnabled) {
                AutoCheckInWorker.schedule(
                    context,
                    prefs.autoCheckInHour,
                    prefs.autoCheckInMinute,
                    diagnostics = autoCheckInDiagnosticsStore
                )
            }
        }
    }

    fun setRunMode(mode: RunMode) {
        viewModelScope.launch { userPreferencesRepository.setRunMode(mode) }
    }

    fun setCheckInResultNotify(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setCheckInResultNotify(enabled) }
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
