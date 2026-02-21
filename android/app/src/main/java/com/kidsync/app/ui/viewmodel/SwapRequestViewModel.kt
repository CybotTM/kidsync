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
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
    val parentAId: String? = null,
    val parentBId: String? = null,
    val parentAName: String = "Parent A",
    val parentBName: String = "Parent B",

    // Child context
    val childId: String? = null,

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
    private val bucketRepository: BucketRepository
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
                val bucketId = bucketRepository.getAccessibleBuckets().firstOrNull() ?: return@launch
                val isSolo = bucketRepository.getBucketDevices(bucketId).getOrDefault(emptyList()).size <= 1
                if (isSolo) {
                    _uiState.update { it.copy(isSolo = true) }
                }
            } catch (_: Exception) {
                // Non-critical: default to shared mode behavior
            }
        }
    }

    // ── Context Setup ────────────────────────────────────────────────

    fun setChildId(childId: String) {
        _uiState.update { it.copy(childId = childId) }
    }

    fun setParentInfo(parentAId: String, parentAName: String, parentBId: String, parentBName: String) {
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

    // ── Swap Request Form ────────────────────────────────────────────

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

                val bucketId = bucketRepository.getAccessibleBuckets().firstOrNull() ?: run {
                    _uiState.update { it.copy(isSubmittingSwap = false, error = "No bucket available") }
                    return@launch
                }

                val overrideId = UUID.randomUUID().toString()
                // The swap assigns the OTHER parent for the requested days
                val assignedParentId = parentBId // Default swap target

                val contentData = buildJsonObject {
                    put("overrideId", JsonPrimitive(overrideId))
                    put("type", JsonPrimitive(OverrideType.SWAP_REQUEST.name))
                    put("childId", JsonPrimitive(childId))
                    put("startDate", JsonPrimitive(startDate.toString()))
                    put("endDate", JsonPrimitive(endDate.toString()))
                    put("assignedParentId", JsonPrimitive(assignedParentId))
                    put("status", JsonPrimitive(OverrideStatus.PROPOSED.name))
                    put("proposerId", JsonPrimitive(session.deviceId))
                    state.swapNote.ifBlank { null }?.let { put("note", JsonPrimitive(it)) }
                }

                val result = createOperationUseCase(
                    bucketId = bucketId,
                    entityType = EntityType.ScheduleOverride,
                    entityId = overrideId,
                    operationType = OperationType.CREATE,
                    contentData = contentData
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

    // ── Swap Approval/Decline ────────────────────────────────────────

    fun approveSwap(overrideId: String) {
        respondToSwap(overrideId, OverrideStatus.APPROVED)
    }

    fun declineSwap(overrideId: String) {
        respondToSwap(overrideId, OverrideStatus.DECLINED)
    }

    private fun respondToSwap(overrideId: String, newStatus: OverrideStatus) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val session = authRepository.getSession() ?: run {
                    _uiState.update { it.copy(isLoading = false, error = "Not logged in") }
                    return@launch
                }

                val bucketId = bucketRepository.getAccessibleBuckets().firstOrNull() ?: run {
                    _uiState.update { it.copy(isLoading = false, error = "No bucket available") }
                    return@launch
                }

                val contentData = buildJsonObject {
                    put("overrideId", JsonPrimitive(overrideId))
                    put("status", JsonPrimitive(newStatus.name))
                    put("responderDeviceId", JsonPrimitive(session.deviceId))
                }

                val result = createOperationUseCase(
                    bucketId = bucketId,
                    entityType = EntityType.ScheduleOverride,
                    entityId = overrideId,
                    operationType = OperationType.UPDATE,
                    contentData = contentData
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

    // ── Utility ──────────────────────────────────────────────────────

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
