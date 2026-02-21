package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class ChildEntry(
    val name: String = "",
    val dateOfBirth: LocalDate? = null
)

data class FamilyUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val children: List<ChildEntry> = listOf(ChildEntry())
)

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val bucketRepository: BucketRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FamilyUiState())
    val uiState: StateFlow<FamilyUiState> = _uiState.asStateFlow()

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun onChildNameChanged(index: Int, name: String) {
        _uiState.update { state ->
            val updated = state.children.toMutableList()
            if (index in updated.indices) {
                updated[index] = updated[index].copy(name = name)
            }
            state.copy(children = updated)
        }
    }

    fun addChild() {
        _uiState.update { state ->
            state.copy(children = state.children + ChildEntry())
        }
    }

    fun removeChild(index: Int) {
        _uiState.update { state ->
            if (state.children.size > 1 && index in state.children.indices) {
                state.copy(children = state.children.toMutableList().apply { removeAt(index) })
            } else {
                state
            }
        }
    }

    fun saveChildren() {
        val children = _uiState.value.children.filter { it.name.isNotBlank() }
        if (children.isEmpty()) {
            _uiState.update { it.copy(error = "Please add at least one child") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                // Children data is stored as encrypted ops in the bucket.
                // The actual persistence is handled by the sync engine when
                // creating custody schedules or other child-related entities.
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to save children"
                    )
                }
            }
        }
    }
}
