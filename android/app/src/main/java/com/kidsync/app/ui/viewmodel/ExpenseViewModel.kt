package com.kidsync.app.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.data.local.entity.ExpenseEntity
import com.kidsync.app.data.local.entity.ExpenseStatusEntity
import com.kidsync.app.domain.model.Expense
import com.kidsync.app.domain.model.ExpenseCategory
import com.kidsync.app.domain.model.ExpenseStatusType
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.repository.ExpenseRepository
import com.kidsync.app.domain.usecase.expense.CreateExpenseUseCase
import com.kidsync.app.domain.usecase.expense.ExpenseSummary
import com.kidsync.app.domain.usecase.expense.GetExpenseSummaryUseCase
import com.kidsync.app.domain.usecase.expense.UpdateExpenseStatusUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import javax.inject.Inject

// ─── Filter State ────────────────────────────────────────────────────────────

data class ExpenseFilter(
    val category: ExpenseCategory? = null,
    val childId: UUID? = null,
    val status: ExpenseStatusType? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null
)

// ─── Display Models ──────────────────────────────────────────────────────────

data class ExpenseWithStatus(
    val expense: ExpenseEntity,
    val latestStatus: ExpenseStatusType,
    val statusHistory: List<ExpenseStatusEntity>
)

enum class SummaryPeriod { MONTHLY, YEARLY }

data class CategoryTotal(
    val category: ExpenseCategory,
    val totalCents: Long
)

// ─── UI State ────────────────────────────────────────────────────────────────

data class ExpenseUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,

    // Solo mode
    val isSolo: Boolean = false,

    // List
    val expenses: List<ExpenseWithStatus> = emptyList(),
    val filter: ExpenseFilter = ExpenseFilter(),
    val balanceCents: Long = 0L,
    val currencyCode: String = "USD",

    // Detail
    val selectedExpense: ExpenseWithStatus? = null,

    // Add expense form
    val addAmountText: String = "",
    val addCategory: ExpenseCategory? = null,
    val addDescription: String = "",
    val addDate: LocalDate = LocalDate.now(),
    val addChildId: UUID? = null,
    val addSplitRatio: Float = 0.5f,
    val addCurrencyCode: String = "USD",
    val addReceiptUri: Uri? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,

    // Summary
    val summaryPeriod: SummaryPeriod = SummaryPeriod.MONTHLY,
    val summaryMonth: YearMonth = YearMonth.now(),
    val summary: ExpenseSummary? = null,
    val categoryTotals: List<CategoryTotal> = emptyList(),

    // Available children for selection
    val availableChildren: List<Pair<UUID, String>> = emptyList()
)

@HiltViewModel
class ExpenseViewModel @Inject constructor(
    private val createExpenseUseCase: CreateExpenseUseCase,
    private val updateExpenseStatusUseCase: UpdateExpenseStatusUseCase,
    private val getExpenseSummaryUseCase: GetExpenseSummaryUseCase,
    private val expenseRepository: ExpenseRepository,
    private val authRepository: AuthRepository,
    private val bucketRepository: BucketRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpenseUiState())
    val uiState: StateFlow<ExpenseUiState> = _uiState.asStateFlow()

    init {
        loadSoloMode()
        loadExpenses()
        // Restore receipt URI from saved state if process was killed
        savedStateHandle.get<String>("receipt_uri")?.let { uriString ->
            _uiState.update { it.copy(addReceiptUri = Uri.parse(uriString)) }
        }
    }

    private fun loadSoloMode() {
        viewModelScope.launch {
            try {
                val session = authRepository.getSession() ?: return@launch
                val bucketId = bucketRepository.getAccessibleBuckets().firstOrNull() ?: return@launch
                val isSolo = bucketRepository.getBucketDevices(bucketId).getOrDefault(emptyList()).size <= 1
                if (isSolo) {
                    _uiState.update {
                        it.copy(
                            isSolo = true,
                            addSplitRatio = 1.0f
                        )
                    }
                }
            } catch (_: Exception) {
                // Non-critical: default to shared mode behavior
            }
        }
    }

    // ─── List ────────────────────────────────────────────────────────────────

    fun loadExpenses() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val allExpenses = expenseRepository.getAllExpenses()
                val session = authRepository.getSession()

                val expensesWithStatus = allExpenses.map { entity ->
                    val latestStatus = expenseRepository.getLatestStatusForExpense(entity.expenseId)
                    val statusHistory = expenseRepository.getStatusHistoryForExpense(entity.expenseId)
                    val statusType = latestStatus?.let {
                        try { ExpenseStatusType.valueOf(it.status) }
                        catch (_: IllegalArgumentException) { ExpenseStatusType.PENDING }
                    } ?: ExpenseStatusType.PENDING

                    ExpenseWithStatus(
                        expense = entity,
                        latestStatus = statusType,
                        statusHistory = statusHistory
                    )
                }

                val filtered = applyFilters(expensesWithStatus, _uiState.value.filter)

                // Calculate balance: positive means they owe you, negative means you owe them
                val balance = calculateBalance(allExpenses, session?.deviceId)

                // Determine currency from first expense or default
                val currency = allExpenses.firstOrNull()?.currencyCode ?: "USD"

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        expenses = filtered,
                        balanceCents = balance,
                        currencyCode = currency
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        error = e.message ?: "Failed to load expenses"
                    )
                }
            }
        }
    }

    fun refreshExpenses() {
        _uiState.update { it.copy(isRefreshing = true) }
        loadExpenses()
    }

    // ─── Filters ─────────────────────────────────────────────────────────────

    fun applyFilter(filter: ExpenseFilter) {
        _uiState.update { it.copy(filter = filter) }
        loadExpenses()
    }

    fun clearFilters() {
        applyFilter(ExpenseFilter())
    }

    private fun applyFilters(
        expenses: List<ExpenseWithStatus>,
        filter: ExpenseFilter
    ): List<ExpenseWithStatus> {
        return expenses.filter { item ->
            val entity = item.expense
            val matchesCategory = filter.category == null ||
                entity.category == filter.category.name
            val matchesChild = filter.childId == null ||
                entity.childId == filter.childId
            val matchesStatus = filter.status == null ||
                item.latestStatus == filter.status
            val date = try { LocalDate.parse(entity.incurredAt) }
            catch (_: Exception) { null }
            val matchesDateFrom = filter.dateFrom == null ||
                (date != null && !date.isBefore(filter.dateFrom))
            val matchesDateTo = filter.dateTo == null ||
                (date != null && !date.isAfter(filter.dateTo))

            matchesCategory && matchesChild && matchesStatus &&
                matchesDateFrom && matchesDateTo
        }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    fun selectExpense(expenseId: String) {
        viewModelScope.launch {
            val uuid = try { UUID.fromString(expenseId) }
            catch (_: IllegalArgumentException) { return@launch }

            val entity = expenseRepository.getExpenseById(uuid) ?: return@launch
            val latestStatus = expenseRepository.getLatestStatusForExpense(uuid)
            val statusHistory = expenseRepository.getStatusHistoryForExpense(uuid)
            val statusType = latestStatus?.let {
                try { ExpenseStatusType.valueOf(it.status) }
                catch (_: IllegalArgumentException) { ExpenseStatusType.PENDING }
            } ?: ExpenseStatusType.PENDING

            _uiState.update {
                it.copy(
                    selectedExpense = ExpenseWithStatus(
                        expense = entity,
                        latestStatus = statusType,
                        statusHistory = statusHistory
                    )
                )
            }
        }
    }

    fun clearSelectedExpense() {
        _uiState.update { it.copy(selectedExpense = null) }
    }

    // ─── Status Updates ──────────────────────────────────────────────────────

    fun acknowledgeExpense(expenseId: UUID) {
        updateStatus(expenseId, ExpenseStatusType.ACKNOWLEDGED)
    }

    fun disputeExpense(expenseId: UUID, note: String? = null) {
        updateStatus(expenseId, ExpenseStatusType.DISPUTED, note)
    }

    private fun updateStatus(
        expenseId: UUID,
        status: ExpenseStatusType,
        note: String? = null
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val session = authRepository.getSession()
            if (session == null) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Not authenticated")
                }
                return@launch
            }

            val bucketId = bucketRepository.getAccessibleBuckets().firstOrNull()
            if (bucketId == null) {
                _uiState.update {
                    it.copy(isLoading = false, error = "No bucket available")
                }
                return@launch
            }

            val result = updateExpenseStatusUseCase(
                bucketId = bucketId,
                expenseId = expenseId.toString(),
                status = status,
                responderDeviceId = session.deviceId,
                note = note
            )

            result.fold(
                onSuccess = {
                    // Reload to reflect changes
                    selectExpense(expenseId.toString())
                    loadExpenses()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Failed to update status"
                        )
                    }
                }
            )
        }
    }

    // ─── Add Expense Form ────────────────────────────────────────────────────

    fun onAmountChanged(amount: String) {
        // Allow only valid currency input
        val sanitized = amount.filter { it.isDigit() || it == '.' }
        _uiState.update { it.copy(addAmountText = sanitized, error = null) }
    }

    fun onCategoryChanged(category: ExpenseCategory) {
        _uiState.update { it.copy(addCategory = category, error = null) }
    }

    fun onDescriptionChanged(description: String) {
        _uiState.update { it.copy(addDescription = description, error = null) }
    }

    fun onDateChanged(date: LocalDate) {
        _uiState.update { it.copy(addDate = date, error = null) }
    }

    fun onChildSelected(childId: UUID) {
        _uiState.update { it.copy(addChildId = childId, error = null) }
    }

    fun onSplitRatioChanged(ratio: Float) {
        _uiState.update { it.copy(addSplitRatio = ratio.coerceIn(0f, 1f)) }
    }

    fun onCurrencyChanged(currencyCode: String) {
        _uiState.update { it.copy(addCurrencyCode = currencyCode, error = null) }
    }

    fun onReceiptCaptured(uri: Uri?) {
        savedStateHandle["receipt_uri"] = uri?.toString()
        _uiState.update { it.copy(addReceiptUri = uri) }
    }

    fun saveExpense() {
        val state = _uiState.value

        // Validation
        val amountCents = parseAmountCents(state.addAmountText)
        if (amountCents == null || amountCents <= 0) {
            _uiState.update { it.copy(error = "Please enter a valid amount") }
            return
        }
        if (state.addCategory == null) {
            _uiState.update { it.copy(error = "Please select a category") }
            return
        }
        if (state.addDescription.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a description") }
            return
        }
        if (state.addChildId == null) {
            _uiState.update { it.copy(error = "Please select a child") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }

            val session = authRepository.getSession()
            if (session == null) {
                _uiState.update {
                    it.copy(isSaving = false, error = "Not authenticated")
                }
                return@launch
            }

            val bucketId = bucketRepository.getAccessibleBuckets().firstOrNull()
            if (bucketId == null) {
                _uiState.update {
                    it.copy(isSaving = false, error = "No bucket available")
                }
                return@launch
            }

            val expense = Expense(
                expenseId = UUID.randomUUID().toString(),
                childId = state.addChildId.toString(),
                paidByDeviceId = session.deviceId,
                amountCents = amountCents,
                currencyCode = state.addCurrencyCode,
                category = state.addCategory,
                description = state.addDescription.trim(),
                incurredAt = state.addDate,
                payerResponsibilityRatio = state.addSplitRatio.toDouble()
            )

            val result = createExpenseUseCase(
                bucketId = bucketId,
                expense = expense
            )

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            isSaved = true,
                            addAmountText = "",
                            addCategory = null,
                            addDescription = "",
                            addDate = LocalDate.now(),
                            addChildId = null,
                            addSplitRatio = 0.5f,
                            addReceiptUri = null
                        )
                    }
                    savedStateHandle.remove<String>("receipt_uri")
                    loadExpenses()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = error.message ?: "Failed to save expense"
                        )
                    }
                }
            )
        }
    }

    fun resetSavedState() {
        _uiState.update { it.copy(isSaved = false) }
    }

    // ─── Summary ─────────────────────────────────────────────────────────────

    fun loadSummary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val allExpenses = expenseRepository.getAllExpenses()

                val period = _uiState.value.summaryPeriod
                val month = _uiState.value.summaryMonth

                // Filter by time period
                val periodExpenses = allExpenses.filter { entity ->
                    val date = try { LocalDate.parse(entity.incurredAt) }
                    catch (_: Exception) { null }
                    if (date == null) return@filter false

                    when (period) {
                        SummaryPeriod.MONTHLY ->
                            YearMonth.from(date) == month
                        SummaryPeriod.YEARLY ->
                            date.year == month.year
                    }
                }

                // Calculate category totals
                val categoryTotals = ExpenseCategory.entries.mapNotNull { cat ->
                    val total = periodExpenses
                        .filter { it.category == cat.name }
                        .sumOf { it.amountCents.toLong() }
                    if (total > 0) CategoryTotal(cat, total) else null
                }

                val session = authRepository.getSession()
                val totalCents = periodExpenses.sumOf { it.amountCents.toLong() }
                val deviceId = session?.deviceId

                var yourShare = 0L
                var theirShare = 0L
                for (expense in periodExpenses) {
                    val ratio = expense.payerResponsibilityRatio
                    if (deviceId != null && expense.paidByUserId.toString() == deviceId) {
                        yourShare += (expense.amountCents * ratio).toLong()
                        theirShare += (expense.amountCents * (1 - ratio)).toLong()
                    } else {
                        theirShare += (expense.amountCents * ratio).toLong()
                        yourShare += (expense.amountCents * (1 - ratio)).toLong()
                    }
                }

                val currency = allExpenses.firstOrNull()?.currencyCode ?: "USD"

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        summary = ExpenseSummary(
                            totalExpensesCents = totalCents,
                            parentAShareCents = yourShare,
                            parentBShareCents = theirShare,
                            parentAOwes = 0,
                            parentBOwes = 0,
                            expenseCount = periodExpenses.size
                        ),
                        categoryTotals = categoryTotals,
                        currencyCode = currency
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load summary"
                    )
                }
            }
        }
    }

    fun onSummaryPeriodChanged(period: SummaryPeriod) {
        _uiState.update { it.copy(summaryPeriod = period) }
        loadSummary()
    }

    fun onSummaryMonthChanged(yearMonth: YearMonth) {
        _uiState.update { it.copy(summaryMonth = yearMonth) }
        loadSummary()
    }

    fun navigateSummaryForward() {
        val current = _uiState.value.summaryMonth
        val next = when (_uiState.value.summaryPeriod) {
            SummaryPeriod.MONTHLY -> current.plusMonths(1)
            SummaryPeriod.YEARLY -> current.plusYears(1)
        }
        onSummaryMonthChanged(next)
    }

    fun navigateSummaryBackward() {
        val current = _uiState.value.summaryMonth
        val prev = when (_uiState.value.summaryPeriod) {
            SummaryPeriod.MONTHLY -> current.minusMonths(1)
            SummaryPeriod.YEARLY -> current.minusYears(1)
        }
        onSummaryMonthChanged(prev)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun parseAmountCents(text: String): Int? {
        val amount = text.toDoubleOrNull() ?: return null
        return (amount * 100).toInt()
    }

    private fun calculateBalance(expenses: List<ExpenseEntity>, deviceId: String?): Long {
        if (deviceId == null) return 0L

        var youPaid = 0L
        var yourResponsibility = 0L

        for (expense in expenses) {
            val ratio = expense.payerResponsibilityRatio
            if (expense.paidByUserId.toString() == deviceId) {
                youPaid += expense.amountCents
                yourResponsibility += (expense.amountCents * ratio).toLong()
            } else {
                yourResponsibility += (expense.amountCents * (1 - ratio)).toLong()
            }
        }

        // Positive = they owe you (you overpaid), negative = you owe them
        return youPaid - yourResponsibility
    }
}
