package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.FamilyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val isSolo: Boolean = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val familyRepository: FamilyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadSoloMode()
    }

    private fun loadSoloMode() {
        viewModelScope.launch {
            try {
                val session = authRepository.getSession() ?: return@launch
                val family = familyRepository.getFamily(session.familyId)
                if (family?.isSolo == true) {
                    _uiState.update { it.copy(isSolo = true) }
                }
            } catch (_: Exception) {
                // Non-critical: default to shared mode behavior
            }
        }
    }
}
