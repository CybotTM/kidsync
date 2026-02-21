package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.model.CalendarEvent
import com.kidsync.app.domain.model.CustodyDay
import com.kidsync.app.domain.model.CustodySchedule
import com.kidsync.app.domain.model.ScheduleOverride
import com.kidsync.app.domain.usecase.custody.GetCustodyCalendarUseCase
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

// ── UI State ────────────────────────────────────────────────────────────────

data class CalendarUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Solo mode
    val isSolo: Boolean = false,

    // Month navigation
    val currentMonth: YearMonth = YearMonth.now(),
    val selectedDate: LocalDate? = null,

    // Child selection
    val childId: String? = null,
    val children: List<ChildInfo> = emptyList(),

    // Custody assignments for the displayed month
    val assignments: Map<LocalDate, CustodyDay> = emptyMap(),

    // Parent info
    val parentAId: String? = null,
    val parentBId: String? = null,
    val parentAName: String = "Parent A",
    val parentBName: String = "Parent B",

    // Today's assigned parent
    val todayAssignedParentId: String? = null,

    // Events for the selected day
    val events: List<CalendarEvent> = emptyList(),

    // All events for the current month (for dot indicators)
    val monthEvents: List<CalendarEvent> = emptyList(),

    // Pending swap requests
    val pendingSwaps: List<ScheduleOverride> = emptyList(),

    // Schedule setup state
    val activeSchedule: CustodySchedule? = null,
    val hasSchedule: Boolean = false
)

data class ChildInfo(
    val id: String,
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
    private val authRepository: AuthRepository,
    private val bucketRepository: BucketRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

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

    fun selectChild(childId: String) {
        _uiState.update { it.copy(childId = childId) }
        loadMonth()
    }

    fun setChildren(children: List<ChildInfo>) {
        _uiState.update { state ->
            val selectedChild = state.childId ?: children.firstOrNull()?.id
            state.copy(children = children, childId = selectedChild)
        }
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

    // ── Utility ─────────────────────────────────────────────────────────

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
