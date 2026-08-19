package com.checky.app.ui.screens.addservice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.checky.app.data.repository.CheckInRepository
import com.checky.app.domain.model.ProviderMeta
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AddServiceViewModel @Inject constructor(
    private val repository: CheckInRepository,
    val catalog: List<ProviderMeta>
) : ViewModel() {

    val services = repository.observeServices()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setEnabled(serviceId: String, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(serviceId, enabled) }
    }
}
