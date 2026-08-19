package com.checky.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.data.preferences.RunMode
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.CheckInAllUseCase
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.ProviderMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: CheckInRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val checkInAllUseCase: CheckInAllUseCase,
    private val providers: @JvmSuppressWildcards List<CheckInProvider>,
    val metas: @JvmSuppressWildcards List<ProviderMeta>
) : ViewModel() {

    val services = repository.observeServices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Eagerly-collected preferences so run mode is always current without suspension. */
    val prefs: StateFlow<UserPreferences> = userPreferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserPreferences())

    private val _progress = MutableStateFlow<CheckInAllProgress?>(null)
    val progress: StateFlow<CheckInAllProgress?> = _progress.asStateFlow()

    val isRunning: StateFlow<Boolean> = progress
        .map { it is CheckInAllProgress.Running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private var runningJob: Job? = null

    /** Run every enabled provider ("Check in all"). */
    fun checkInAll() {
        if (_progress.value is CheckInAllProgress.Running) return
        val targets = enabledProviders()
        if (targets.isEmpty()) return
        runningJob = viewModelScope.launch {
            val parallel = prefs.value.runMode == RunMode.PARALLEL
            checkInAllUseCase(targets, parallel = parallel).collect { _progress.value = it }
        }
    }

    /** Retry a single provider individually. */
    fun retry(serviceId: String) {
        if (_progress.value is CheckInAllProgress.Running) return
        val provider = providers.firstOrNull { it.meta.id == serviceId } ?: return
        runningJob = viewModelScope.launch {
            checkInAllUseCase(listOf(provider), parallel = false).collect { _progress.value = it }
        }
    }

    /** Cancel an in-flight "Check in all" run. */
    fun cancelCheckInAll() {
        runningJob?.cancel()
        runningJob = null
        _progress.value = null
    }

    fun dismissSummary() {
        _progress.value = null
    }

    fun setEnabled(serviceId: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(serviceId, enabled) }
    }

    private fun enabledProviders(): List<CheckInProvider> {
        val enabled = services.value.filter { it.isEnabled }.map { it.serviceId }.toSet()
        val filtered = providers.filter { it.meta.id in enabled }
        // Before the service list loads, fall back to all providers.
        return if (filtered.isEmpty() && services.value.isEmpty()) providers else filtered
    }
}
