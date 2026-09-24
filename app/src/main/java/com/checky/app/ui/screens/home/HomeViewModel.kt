package com.checky.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.data.preferences.RunMode
import com.checky.app.data.preferences.UserPreferences
import com.checky.app.data.preferences.UserPreferencesRepository
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.CheckInAllUseCase
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.AuthHealth
import com.checky.app.domain.AuthHealthStore
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.LegacyAuthHealthMigration
import com.checky.app.domain.NoOpAuthHealthStore
import com.checky.app.domain.ProviderConnectionGate
import com.checky.app.domain.providers.MiyousheCredentialSharing
import com.checky.app.domain.providers.TaygedoProvider
import com.checky.app.domain.model.CheckInAllProgress
import com.checky.app.domain.model.ProviderMeta
import com.checky.app.domain.model.ServiceCheckInState
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
import java.time.Instant
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

data class HomeServiceState(
    val service: com.checky.app.data.model.ServiceSnapshot,
    val isConnected: Boolean,
    val authHealth: AuthHealth? = null
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
    private val _now = MutableStateFlow(Instant.now())
    val now: StateFlow<Instant> = _now.asStateFlow()
    private val _runStartedAt = MutableStateFlow<Instant?>(null)
    val runStartedAt: StateFlow<Instant?> = _runStartedAt.asStateFlow()

    val services = repository.observeServices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Enabled services remain visible even when their credential is missing or stale. */
    val homeServices = services
        .combine(connectionRefresh) { list, _ -> list }
        .map { list ->
            LegacyAuthHealthMigration.seedIfNeeded(providers, list, credentialStore, authHealthStore)
            MiyousheCredentialSharing.reconcile(credentialStore, authHealthStore)
            list.filter { it.isEnabled }.map { service ->
                val health = authHealth(service.serviceId)
                HomeServiceState(
                    service = service,
                    isConnected = health == AuthHealth.VALID,
                    authHealth = health
                )
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
            _runStartedAt.value = Instant.now()
            val parallel = prefs.value.runMode == RunMode.PARALLEL
            checkInAllUseCase(targets, parallel = parallel).collect { _progress.value = it }
        }
    }

    /** Retry a single provider individually. */
    fun retry(serviceId: String) {
        if (_progress.value is CheckInAllProgress.Running) return
        val provider = providers.firstOrNull { it.meta.id == serviceId } ?: return
        _runStartedAt.value = Instant.now()
        _progress.value = CheckInAllProgress.Running(
            mapOf(
                serviceId to ServiceCheckInState(
                    meta = provider.meta,
                    status = com.checky.app.domain.model.CheckInStatus.RUNNING,
                    progress = 0f,
                    message = "签到中…",
                    reward = null,
                    timestamp = null
                )
            )
        )
        runningJob = viewModelScope.launch {
            var emitted = false
            try {
                if (services.value.firstOrNull { it.serviceId == serviceId }?.isEnabled != true) return@launch
                LegacyAuthHealthMigration.seedIfNeeded(
                    providers, services.value, credentialStore, authHealthStore
                )
                if (!isConnected(serviceId)) return@launch
                checkInAllUseCase(listOf(provider), parallel = false).collect {
                    emitted = true
                    _progress.value = it
                }
            } finally {
                if (!emitted) {
                    _progress.value = null
                    _runStartedAt.value = null
                }
            }
        }
    }

    /** Cancel an in-flight "Check in all" run. */
    fun cancelCheckInAll() {
        runningJob?.cancel()
        runningJob = null
        _progress.value = null
        _runStartedAt.value = null
    }

    fun dismissSummary() {
        _progress.value = null
        _runStartedAt.value = null
    }

    fun refreshConnections() {
        connectionRefresh.value++
    }

    /** Refresh the local date projection when Home returns to the foreground. */
    fun refreshNow() {
        _now.value = Instant.now()
    }

    fun setEnabled(serviceId: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(serviceId, enabled) }
    }

    private suspend fun enabledProviders(): List<CheckInProvider> {
        val enabled = services.value.filter { it.isEnabled }.map { it.serviceId }.toSet()
        val taygedoPreflightByOwner = mutableMapOf<String, Boolean>()
        return providers.filter {
            it.meta.id in enabled && isConnected(it.meta.id, taygedoPreflightByOwner)
        }
    }

    private suspend fun isConnected(
        serviceId: String,
        taygedoPreflightByOwner: MutableMap<String, Boolean> = mutableMapOf()
    ): Boolean {
        val provider = providers.firstOrNull { it.meta.id == serviceId } ?: return false
        val health = ProviderConnectionGate.health(provider, credentialStore, authHealthStore)
        return when {
            provider is TaygedoProvider &&
                (health == AuthHealth.VALID || health == AuthHealth.EXPIRED) -> {
                val owner = provider.credentialOwnerId
                if (!taygedoPreflightByOwner.containsKey(owner)) {
                    taygedoPreflightByOwner[owner] = provider.recoverExpiredSession(authHealthStore)
                }
                taygedoPreflightByOwner.getValue(owner)
            }
            else -> health == AuthHealth.VALID
        }
    }

    private suspend fun authHealth(serviceId: String): AuthHealth? {
        val provider = providers.firstOrNull { it.meta.id == serviceId } ?: return null
        return ProviderConnectionGate.health(provider, credentialStore, authHealthStore)
    }
}
