package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

// ── UI State ────────────────────────────────────────────────────────────────

data class ScheduleSetupUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Child + parent context (set by the screen from navigation args or shared state)
    val childId: UUID? = null,
    val parentAId: UUID? = null,
    val parentBId: UUID? = null,
    val parentAName: String = "Parent A",
    val parentBName: String = "Parent B",

    // Schedule setup form
    val setupPattern: List<Boolean> = emptyList(),
    val setupCycleLength: Int = 14,
    val setupAnchorDate: LocalDate? = null,
    val setupTimeZone: String = java.util.TimeZone.getDefault().id,

    // Result
    val scheduleSaved: Boolean = false
)

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class ScheduleSetupViewModel @Inject constructor(
    private val createOperationUseCase: CreateOperationUseCase,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScheduleSetupUiState())
    val uiState: StateFlow<ScheduleSetupUiState> = _uiState.asStateFlow()

    // ── Context Setup ────────────────────────────────────────────────────

    fun setChildId(childId: UUID) {
        _uiState.update { it.copy(childId = childId) }
    }

    fun setParentInfo(parentAId: UUID, parentAName: String, parentBId: UUID, parentBName: String) {
        _uiState.update {
            it.copy(
                parentAId = parentAId,
                parentAName = parentAName,
                parentBId = parentBId,
                parentBName = parentBName
            )
        }
    }

    // ── Pattern Selection ────────────────────────────────────────────────

    fun selectPresetPattern(preset: PatternPreset) {
        _uiState.update {
            it.copy(
                setupPattern = preset.pattern,
                setupCycleLength = preset.cycleLength
            )
        }
    }

    fun setCustomCycleLength(length: Int) {
        val clamped = length.coerceIn(1, 60)
        _uiState.update { state ->
            val currentPattern = state.setupPattern
            val newPattern = if (currentPattern.size == clamped) {
                currentPattern
            } else {
                List(clamped) { index ->
                    if (index < currentPattern.size) currentPattern[index] else true
                }
            }
            state.copy(setupCycleLength = clamped, setupPattern = newPattern)
        }
    }

    fun togglePatternDay(index: Int) {
        _uiState.update { state ->
            val mutablePattern = state.setupPattern.toMutableList()
            if (index in mutablePattern.indices) {
                mutablePattern[index] = !mutablePattern[index]
            }
            state.copy(setupPattern = mutablePattern)
        }
    }

    // ── Anchor Date & Timezone ───────────────────────────────────────────

    fun setAnchorDate(date: LocalDate) {
        _uiState.update { it.copy(setupAnchorDate = date) }
    }

    fun setTimeZone(timeZone: String) {
        _uiState.update { it.copy(setupTimeZone = timeZone) }
    }

    // ── Save Schedule ────────────────────────────────────────────────────

    fun saveSchedule() {
        val state = _uiState.value
        val childId = state.childId ?: return
        val anchorDate = state.setupAnchorDate ?: return
        val pattern = state.setupPattern
        val parentAId = state.parentAId ?: return
        val parentBId = state.parentBId ?: return

        if (pattern.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val session = authRepository.getSession() ?: run {
                    _uiState.update { it.copy(isLoading = false, error = "Not logged in") }
                    return@launch
                }

                val scheduleId = UUID.randomUUID()
                val patternUuids = pattern.map { isParentA ->
                    if (isParentA) parentAId.toString() else parentBId.toString()
                }

                val payloadMap = mapOf(
                    "payloadType" to "SetCustodySchedule",
                    "entityId" to scheduleId.toString(),
                    "timestamp" to Instant.now().toString(),
                    "operationType" to OperationType.CREATE.name,
                    "scheduleId" to scheduleId.toString(),
                    "childId" to childId.toString(),
                    "anchorDate" to anchorDate.toString(),
                    "cycleLengthDays" to state.setupCycleLength,
                    "pattern" to patternUuids,
                    "effectiveFrom" to Instant.now().toString(),
                    "timeZone" to state.setupTimeZone
                )

                val result = createOperationUseCase(
                    familyId = session.familyId,
                    deviceId = session.deviceId,
                    entityType = EntityType.CustodySchedule,
                    entityId = scheduleId,
                    operationType = OperationType.CREATE,
                    payloadMap = payloadMap
                )

                result.fold(
                    onSuccess = {
                        _uiState.update {
                            it.copy(isLoading = false, scheduleSaved = true)
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(isLoading = false, error = error.message)
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to save schedule")
                }
            }
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
