package com.checky.app.ui.screens.provider

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.model.ProviderMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProviderDetailsViewModel @Inject constructor(
    private val repository: CheckInRepository,
    val metas: List<ProviderMeta>,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val serviceId: String = savedStateHandle.get<String>("serviceId") ?: ""

    val meta: ProviderMeta? = metas.firstOrNull { it.id == serviceId }

    val service = repository.observeServices()
        .map { list -> list.firstOrNull { it.serviceId == serviceId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(serviceId, enabled) }
    }
}
