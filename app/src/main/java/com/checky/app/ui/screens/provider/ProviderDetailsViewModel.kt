package com.checky.app.ui.screens.provider

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.CheckInProvider
import com.checky.app.domain.CredentialStore
import com.checky.app.domain.ProviderConnectionGate
import com.checky.app.domain.model.ProviderMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.jvm.JvmSuppressWildcards

@HiltViewModel
class ProviderDetailsViewModel @Inject constructor(
    private val repository: CheckInRepository,
    private val credentialStore: CredentialStore,
    private val providers: @JvmSuppressWildcards List<CheckInProvider>,
    val metas: List<ProviderMeta>,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val serviceId: String = savedStateHandle.get<String>("serviceId") ?: ""
    private val connectionRefresh = MutableStateFlow(0)

    val meta: ProviderMeta? = metas.firstOrNull { it.id == serviceId }

    val service = repository.observeServices()
        .map { list -> list.firstOrNull { it.serviceId == serviceId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Current usability according to the shared gate; independent of last check-in status. */
    val isConnected: StateFlow<Boolean> = connectionRefresh
        .map {
            val provider = providers.firstOrNull { it.meta.id == serviceId }
            provider != null && ProviderConnectionGate.isConnected(provider, credentialStore)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** Re-read connection state after returning from the connection screen. */
    fun refreshConnection() {
        connectionRefresh.value++
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(serviceId, enabled) }
    }
}
