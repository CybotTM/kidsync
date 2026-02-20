package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.model.CalendarEvent
import com.kidsync.app.domain.model.CustodyDay
import com.kidsync.app.domain.model.CustodyDaySource
import com.kidsync.app.domain.model.CustodySchedule
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.model.OverrideType
import com.kidsync.app.domain.model.ScheduleOverride
import com.kidsync.app.domain.usecase.custody.GetCustodyCalendarUseCase
import com.kidsync.app.domain.usecase.custody.OverrideStateMachine
import com.kidsync.app.domain.usecase.custody.PatternGenerator
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import com.kidsync.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject

// ── UI State ────────────────────────────────────────────────────────────────

data class CalendarUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Month navigation
    val currentMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate? = null,

    // Child selection
    val childId: UUID? = null,
    val children: List<ChildInfo> = emptyList(),

    // Custody assignments for the displayed month
    val assignments: Map<LocalDate, CustodyDay> = emptyMap(),

    // Parent info
    val parentAId: UUID? = null,
    val parentBId: UUID? = null,
    val parentAName: String = "Parent A",
    val parentBName: String = "Parent B",

    // Today's assigned parent
    val todayAssignedParentId: UUID? = null,

    // Events for the selected day
    val events: List<CalendarEvent> = emptyList(),

    // All events for the current month (for dot indicators)
    val monthEvents: List<CalendarEvent> = emptyList(),

    // Pending swap requests
    val pendingSwaps: List<ScheduleOverride> = emptyList(),

    // Schedule setup state
    val activeSchedule: CustodySchedule? = null,
    val hasSchedule: Boolean = false,

    // Swap request form
    val swapStartDate: LocalDate? = null,
    val swapEndDate: LocalDate? = null,
    val swapNote: String = "",
    val swapPreview: List<CustodyDay> = emptyList(),
    val isSubmittingSwap: Boolean = false,

    // Event form
    val eventTitle: String = "",
    val eventDate: LocalDate? = null,
    val eventTime: LocalTime? = null,
    val eventLocation: String = "",
    val eventNotes: String = "",
    val editingEventId: UUID? = null,
    val isSavingEvent: Boolean = false,

    // Schedule setup form
    val setupPattern: List<Boolean> = emptyList(),
    val setupCycleLength: Int = 14,
    val setupAnchorDate: LocalDate? = null,
    val setupTimeZone: String = java.util.TimeZone.getDefault().id
)

data class ChildInfo(
    val id: UUID,
    val name: String
)

/**
 * Common custody pattern presets with their pattern arrays.
 * Each Boolean in the pattern represents one day: true = Parent A, false = Parent B.
 */
enum class PatternPreset(
    val labelKey: String,
    val pattern: List<Boolean>,
    val cycleLength: Int
) {
    WEEK_ON_WEEK_OFF(
        labelKey = "7-7",
        pattern = List(7) { true } + List(7) { false },
        cycleLength = 14
    ),
    TWO_TWO_THREE(
        labelKey = "2-2-3",
        pattern = listOf(
            true, true,       // A: Mon-Tue
            false, false,     // B: Wed-Thu
            true, true, true, // A: Fri-Sun
            false, false,     // B: Mon-Tue
            true, true,       // A: Wed-Thu
            false, false, false // B: Fri-Sun
        ),
        cycleLength = 14
    ),
    TWO_TWO_FIVE_FIVE(
        labelKey = "2-2-5-5",
        pattern = listOf(
            true, true,                       // A: 2 days
            false, false,                     // B: 2 days
            true, true, true, true, true,     // A: 5 days
            false, false, false, false, false  // B: 5 days
        ),
        cycleLength = 14
    ),
    ALTERNATING_WEEKENDS(
        labelKey = "alternating_weekends",
        pattern = listOf(
            true, true, true, true, true, true, true,     // Week 1: A (incl. weekend)
            true, true, true, true, true, false, false     // Week 2: A weekdays, B weekend
        ),
        cycleLength = 14
    )
}

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val getCustodyCalendarUseCase: GetCustodyCalendarUseCase,
    private val patternGenerator: PatternGenerator,
    private val overrideStateMachine: OverrideStateMachine,
    private val createOperationUseCase: CreateOperationUseCase,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    // ── Month Navigation ────────────────────────────────────────────────

    fun navigateMonth(forward: Boolean) {
        _uiState.update {
            val newMonth = if (forward) it.currentMonth.plusMonths(1)
            else it.currentMonth.minusMonths(1)
            it.copy(currentMonth = newMonth)
        }
        loadMonth()
    }

    fun loadMonth() {
        val state = _uiState.value
        val childId = state.childId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val startDate = state.currentMonth.atDay(1)
            val endDate = state.currentMonth.atEndOfMonth()

            val result = getCustodyCalendarUseCase(childId, startDate, endDate)

            result.fold(
                onSuccess = { days ->
                    val assignmentMap = days.associateBy { it.date }
                    val today = LocalDate.now()
                    val todayParent = assignmentMap[today]?.assignedParentId

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            assignments = assignmentMap,
                            todayAssignedParentId = todayParent,
                            hasSchedule = true
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message,
                            hasSchedule = false
                        )
                    }
                }
            )
        }
    }

    // ── Day Selection ───────────────────────────────────────────────────

    fun selectDate(date: LocalDate) {
        _uiState.update { it.copy(selectedDate = date) }
        loadEventsForDate(date)
        loadSwapsForDate(date)
    }

    private fun loadEventsForDate(date: LocalDate) {
        // Events are loaded from local state; in a real app, this would query the database
        // For now, filter from monthEvents
        _uiState.update { state ->
            val dayEvents = state.monthEvents.filter { it.date == date && !it.cancelled }
            state.copy(events = dayEvents)
        }
    }

    private fun loadSwapsForDate(date: LocalDate) {
        // Filter pending swaps that affect this date
        _uiState.update { state ->
            val relevantSwaps = state.pendingSwaps.filter { swap ->
                !date.isBefore(swap.startDate) && !date.isAfter(swap.endDate)
            }
            state.copy(pendingSwaps = relevantSwaps)
        }
    }

    // ── Child Selection ─────────────────────────────────────────────────

    fun selectChild(childId: UUID) {
        _uiState.update { it.copy(childId = childId) }
        loadMonth()
    }

    fun setChildren(children: List<ChildInfo>) {
        _uiState.update { state ->
            val selectedChild = state.childId ?: children.firstOrNull()?.id
            state.copy(children = children, childId = selectedChild)
        }
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

    // ── Schedule Setup ──────────────────────────────────────────────────

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

    fun setAnchorDate(date: LocalDate) {
        _uiState.update { it.copy(setupAnchorDate = date) }
    }

    fun setTimeZone(timeZone: String) {
        _uiState.update { it.copy(setupTimeZone = timeZone) }
    }

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
                            it.copy(isLoading = false, hasSchedule = true)
                        }
                        loadMonth()
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

    // ── Swap Requests ───────────────────────────────────────────────────

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
                                swapPreview = emptyList()
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
                        loadMonth()
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

    // ── Events ──────────────────────────────────────────────────────────

    fun setEventTitle(title: String) {
        _uiState.update { it.copy(eventTitle = title) }
    }

    fun setEventDate(date: LocalDate) {
        _uiState.update { it.copy(eventDate = date) }
    }

    fun setEventTime(time: LocalTime?) {
        _uiState.update { it.copy(eventTime = time) }
    }

    fun setEventLocation(location: String) {
        _uiState.update { it.copy(eventLocation = location) }
    }

    fun setEventNotes(notes: String) {
        _uiState.update { it.copy(eventNotes = notes) }
    }

    fun startEditingEvent(event: CalendarEvent) {
        _uiState.update {
            it.copy(
                editingEventId = event.eventId,
                eventTitle = event.title,
                eventDate = event.date,
                eventTime = event.time,
                eventLocation = event.location ?: "",
                eventNotes = event.notes ?: ""
            )
        }
    }

    fun clearEventForm() {
        _uiState.update {
            it.copy(
                editingEventId = null,
                eventTitle = "",
                eventDate = null,
                eventTime = null,
                eventLocation = "",
                eventNotes = ""
            )
        }
    }

    fun saveEvent() {
        val state = _uiState.value
        val childId = state.childId ?: return
        val title = state.eventTitle.trim()
        val date = state.eventDate ?: return

        if (title.isBlank()) {
            _uiState.update { it.copy(error = "Event title is required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingEvent = true, error = null) }

            try {
                val session = authRepository.getSession() ?: run {
                    _uiState.update { it.copy(isSavingEvent = false, error = "Not logged in") }
                    return@launch
                }

                val eventId = state.editingEventId ?: UUID.randomUUID()
                val operationType = if (state.editingEventId != null) OperationType.UPDATE else OperationType.CREATE
                val payloadType = if (state.editingEventId != null) "UpdateEvent" else "CreateEvent"

                val payloadMap = buildMap<String, Any?> {
                    put("payloadType", payloadType)
                    put("entityId", eventId.toString())
                    put("timestamp", Instant.now().toString())
                    put("operationType", operationType.name)
                    put("eventId", eventId.toString())
                    put("childId", childId.toString())
                    put("title", title)
                    put("date", date.toString())
                    state.eventTime?.let { put("time", it.toString()) }
                    state.eventLocation.takeIf { it.isNotBlank() }?.let { put("location", it) }
                    state.eventNotes.takeIf { it.isNotBlank() }?.let { put("notes", it) }
                }

                val result = createOperationUseCase(
                    familyId = session.familyId,
                    deviceId = session.deviceId,
                    entityType = EntityType.Event,
                    entityId = eventId,
                    operationType = operationType,
                    payloadMap = payloadMap
                )

                result.fold(
                    onSuccess = {
                        _uiState.update {
                            it.copy(isSavingEvent = false)
                        }
                        clearEventForm()
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(isSavingEvent = false, error = error.message)
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSavingEvent = false, error = e.message ?: "Failed to save event")
                }
            }
        }
    }

    fun deleteEvent(eventId: UUID) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val session = authRepository.getSession() ?: run {
                    _uiState.update { it.copy(isLoading = false, error = "Not logged in") }
                    return@launch
                }

                val payloadMap = mapOf(
                    "payloadType" to "CancelEvent",
                    "entityId" to eventId.toString(),
                    "timestamp" to Instant.now().toString(),
                    "operationType" to OperationType.UPDATE.name,
                    "eventId" to eventId.toString()
                )

                val result = createOperationUseCase(
                    familyId = session.familyId,
                    deviceId = session.deviceId,
                    entityType = EntityType.Event,
                    entityId = eventId,
                    operationType = OperationType.UPDATE,
                    payloadMap = payloadMap
                )

                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(isLoading = false) }
                        // Reload events for current date
                        _uiState.value.selectedDate?.let { loadEventsForDate(it) }
                    },
                    onFailure = { error ->
                        _uiState.update { it.copy(isLoading = false, error = error.message) }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to delete event")
                }
            }
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun initializeSwapForDate(date: LocalDate) {
        _uiState.update {
            it.copy(
                swapStartDate = date,
                swapEndDate = date,
                swapNote = "",
                swapPreview = emptyList()
            )
        }
        updateSwapPreview()
    }

    fun initializeEventForDate(date: LocalDate) {
        _uiState.update {
            it.copy(
                editingEventId = null,
                eventTitle = "",
                eventDate = date,
                eventTime = null,
                eventLocation = "",
                eventNotes = ""
            )
        }
    }
}
