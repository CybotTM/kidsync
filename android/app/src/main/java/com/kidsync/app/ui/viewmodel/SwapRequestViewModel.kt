package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.model.CustodyDay
import com.kidsync.app.domain.model.CustodyDaySource
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.model.OverrideType
import com.kidsync.app.domain.model.ScheduleOverride
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.FamilyRepository
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

data class SwapRequestUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Solo mode
    val isSolo: Boolean = false,

    // Parent context (needed for swap logic)
    val parentAId: UUID? = null,
    val parentBId: UUID? = null,
    val parentAName: String = "Parent A",
    val parentBName: String = "Parent B",

    // Child context
    val childId: UUID? = null,

    // Current calendar assignments (needed for swap preview)
    val assignments: Map<LocalDate, CustodyDay> = emptyMap(),

    // Pending swap requests
    val pendingSwaps: List<ScheduleOverride> = emptyList(),

    // Swap request form
    val swapStartDate: LocalDate? = null,
    val swapEndDate: LocalDate? = null,
    val swapNote: String = "",
    val swapPreview: List<CustodyDay> = emptyList(),
    val isSubmittingSwap: Boolean = false,

    // Result flag
    val swapSubmitted: Boolean = false
)

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class SwapRequestViewModel @Inject constructor(
    private val createOperationUseCase: CreateOperationUseCase,
    private val authRepository: AuthRepository,
    private val familyRepository: FamilyRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SwapRequestUiState())
    val uiState: StateFlow<SwapRequestUiState> = _uiState.asStateFlow()

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

    fun setAssignments(assignments: Map<LocalDate, CustodyDay>) {
        _uiState.update { it.copy(assignments = assignments) }
    }

    fun setPendingSwaps(swaps: List<ScheduleOverride>) {
        _uiState.update { it.copy(pendingSwaps = swaps) }
    }

    // ── Swap Request Form ────────────────────────────────────────────────

    fun initializeSwapForDate(date: LocalDate) {
        _uiState.update {
            it.copy(
                swapStartDate = date,
                swapEndDate = date,
                swapNote = "",
                swapPreview = emptyList(),
                swapSubmitted = false
            )
        }
        updateSwapPreview()
    }

    fun setSwapStartDate(date: LocalDate) {
        _uiState.update { it.copy(swapStartDate = date) }
        updateSwapPreview()
    }

    fun setSwapEndDate(date: LocalDate) {
        _uiState.update { it.copy(swapEndDate = date) }
        updateSwapPreview()
    }

    fun setSwapNote(note: String) {
        _uiState.update { it.copy(swapNote = note) }
    }

    private fun updateSwapPreview() {
        val state = _uiState.value
        val start = state.swapStartDate ?: return
        val end = state.swapEndDate ?: return
        if (end.isBefore(start)) return

        // Show what the swap would look like by flipping parent assignments
        val currentAssignments = state.assignments
        val preview = mutableListOf<CustodyDay>()
        var date = start
        while (!date.isAfter(end)) {
            val current = currentAssignments[date]
            if (current != null) {
                val flippedParent = if (current.assignedParentId == state.parentAId) {
                    state.parentBId
                } else {
                    state.parentAId
                }
                preview.add(
                    CustodyDay(
                        date = date,
                        assignedParentId = flippedParent ?: current.assignedParentId,
                        source = CustodyDaySource.OVERRIDE
                    )
                )
            }
            date = date.plusDays(1)
        }

        _uiState.update { it.copy(swapPreview = preview) }
    }

    fun submitSwapRequest() {
        val state = _uiState.value
        val childId = state.childId ?: return
        val startDate = state.swapStartDate ?: return
        val endDate = state.swapEndDate ?: return
        val parentAId = state.parentAId ?: return
        val parentBId = state.parentBId ?: return

        if (endDate.isBefore(startDate)) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmittingSwap = true, error = null) }

            try {
                val session = authRepository.getSession() ?: run {
                    _uiState.update { it.copy(isSubmittingSwap = false, error = "Not logged in") }
                    return@launch
                }

                val overrideId = UUID.randomUUID()
                // The swap assigns the OTHER parent for the requested days
                val assignedParentId = if (session.userId == parentAId) parentBId else parentAId

                val payloadMap = mapOf(
                    "payloadType" to "UpsertOverride",
                    "entityId" to overrideId.toString(),
                    "timestamp" to Instant.now().toString(),
                    "operationType" to OperationType.CREATE.name,
                    "overrideId" to overrideId.toString(),
                    "type" to OverrideType.SWAP_REQUEST.name,
                    "childId" to childId.toString(),
                    "startDate" to startDate.toString(),
                    "endDate" to endDate.toString(),
                    "assignedParentId" to assignedParentId.toString(),
                    "status" to OverrideStatus.PROPOSED.name,
                    "proposerId" to session.userId.toString(),
                    "note" to state.swapNote.ifBlank { null }
                )

                val result = createOperationUseCase(
                    familyId = session.familyId,
                    deviceId = session.deviceId,
                    entityType = EntityType.ScheduleOverride,
                    entityId = overrideId,
                    operationType = OperationType.CREATE,
                    payloadMap = payloadMap,
                    transitionTo = OverrideStatus.PROPOSED.name
                )

                result.fold(
                    onSuccess = {
                        _uiState.update {
                            it.copy(
                                isSubmittingSwap = false,
                                swapStartDate = null,
                                swapEndDate = null,
                                swapNote = "",
                                swapPreview = emptyList(),
                                swapSubmitted = true
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(isSubmittingSwap = false, error = error.message)
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSubmittingSwap = false, error = e.message ?: "Failed to submit swap")
                }
            }
        }
    }

    // ── Swap Approval/Decline ────────────────────────────────────────────

    fun approveSwap(overrideId: UUID) {
        respondToSwap(overrideId, OverrideStatus.APPROVED)
    }

    fun declineSwap(overrideId: UUID) {
        respondToSwap(overrideId, OverrideStatus.DECLINED)
    }

    private fun respondToSwap(overrideId: UUID, newStatus: OverrideStatus) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val session = authRepository.getSession() ?: run {
                    _uiState.update { it.copy(isLoading = false, error = "Not logged in") }
                    return@launch
                }

                val payloadMap = mapOf(
                    "payloadType" to "UpsertOverride",
                    "entityId" to overrideId.toString(),
                    "timestamp" to Instant.now().toString(),
                    "operationType" to OperationType.UPDATE.name,
                    "overrideId" to overrideId.toString(),
                    "status" to newStatus.name,
                    "responderId" to session.userId.toString()
                )

                val result = createOperationUseCase(
                    familyId = session.familyId,
                    deviceId = session.deviceId,
                    entityType = EntityType.ScheduleOverride,
                    entityId = overrideId,
                    operationType = OperationType.UPDATE,
                    payloadMap = payloadMap,
                    transitionTo = newStatus.name
                )

                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(isLoading = false) }
                    },
                    onFailure = { error ->
                        _uiState.update { it.copy(isLoading = false, error = error.message) }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to respond to swap")
                }
            }
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
