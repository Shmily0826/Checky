package com.checky.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.data.preferences.RunMode
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.CheckInAllUseCase
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.AuthHealthStore
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.LegacyAuthHealthMigration
import com.checky.app.domain.NoOpAuthHealthStore
import com.checky.app.domain.ProviderConnectionGate
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.ProviderMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

data class HomeServiceState(
    val service: com.checky.app.data.model.ServiceSnapshot,
    val isConnected: Boolean
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: CheckInRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val checkInAllUseCase: CheckInAllUseCase,
    private val credentialStore: CredentialStore,
    private val providers: @JvmSuppressWildcards List<CheckInProvider>,
    val metas: @JvmSuppressWildcards List<ProviderMeta>,
    private val authHealthStore: AuthHealthStore = NoOpAuthHealthStore
) : ViewModel() {

    private val connectionRefresh = MutableStateFlow(0)
    private val _today = MutableStateFlow(LocalDate.now())
    val today: StateFlow<LocalDate> = _today.asStateFlow()
    private val _progressDate = MutableStateFlow<LocalDate?>(null)
    val progressDate: StateFlow<LocalDate?> = _progressDate.asStateFlow()

    val services = repository.observeServices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Enabled services remain visible even when their credential is missing or stale. */
    val homeServices = services
        .combine(connectionRefresh) { list, _ -> list }
        .map { list ->
            LegacyAuthHealthMigration.seedIfNeeded(providers, list, credentialStore, authHealthStore)
            list.filter { it.isEnabled }.map { service ->
                HomeServiceState(service = service, isConnected = isConnected(service.serviceId))
            }
        }
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
        runningJob = viewModelScope.launch {
            LegacyAuthHealthMigration.seedIfNeeded(
                providers, services.value, credentialStore, authHealthStore
            )
            val targets = enabledProviders()
            if (targets.isEmpty()) return@launch
            _progressDate.value = LocalDate.now()
            val parallel = prefs.value.runMode == RunMode.PARALLEL
            checkInAllUseCase(targets, parallel = parallel).collect { _progress.value = it }
        }
    }

    /** Retry a single provider individually. */
    fun retry(serviceId: String) {
        if (_progress.value is CheckInAllProgress.Running) return
        val provider = providers.firstOrNull { it.meta.id == serviceId } ?: return
        runningJob = viewModelScope.launch {
            if (services.value.firstOrNull { it.serviceId == serviceId }?.isEnabled != true) return@launch
            LegacyAuthHealthMigration.seedIfNeeded(
                providers, services.value, credentialStore, authHealthStore
            )
            if (!isConnected(serviceId)) return@launch
            _progressDate.value = LocalDate.now()
            checkInAllUseCase(listOf(provider), parallel = false).collect { _progress.value = it }
        }
    }

    /** Cancel an in-flight "Check in all" run. */
    fun cancelCheckInAll() {
        runningJob?.cancel()
        runningJob = null
        _progress.value = null
        _progressDate.value = null
    }

    fun dismissSummary() {
        _progress.value = null
        _progressDate.value = null
    }

    fun refreshConnections() {
        connectionRefresh.value++
    }

    /** Refresh the local date projection when Home returns to the foreground. */
    fun refreshToday() {
        _today.value = LocalDate.now()
    }

    fun setEnabled(serviceId: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(serviceId, enabled) }
    }

    private suspend fun enabledProviders(): List<CheckInProvider> {
        val enabled = services.value.filter { it.isEnabled }.map { it.serviceId }.toSet()
        return providers.filter { it.meta.id in enabled && isConnected(it.meta.id) }
    }

    private suspend fun isConnected(serviceId: String): Boolean {
        val provider = providers.firstOrNull { it.meta.id == serviceId } ?: return false
        return ProviderConnectionGate.isConnected(provider, credentialStore, authHealthStore)
    }
}
