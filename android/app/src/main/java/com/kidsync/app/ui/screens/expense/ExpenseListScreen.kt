package com.kidsync.app.ui.screens.expense

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsync.app.R
import com.kidsync.app.domain.model.ExpenseCategory
import com.kidsync.app.domain.model.ExpenseStatusType
import com.kidsync.app.ui.components.BalanceWidget
import com.kidsync.app.ui.components.ExpenseStatusBadge
import com.kidsync.app.ui.components.categoryIcon
import com.kidsync.app.ui.components.categoryLabel
import com.kidsync.app.ui.components.formatCurrency
import com.kidsync.app.ui.viewmodel.ExpenseFilter
import com.kidsync.app.ui.viewmodel.ExpenseViewModel
import com.kidsync.app.ui.viewmodel.ExpenseWithStatus
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Main expense list screen showing a running balance widget at the top,
 * optional filter chips, and a LazyColumn of expense cards.
 * Includes pull-to-refresh and a FAB for adding new expenses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseListScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToAddExpense: () -> Unit,
    onNavigateToSummary: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            scope.launch {
                snackbarHostState.showSnackbar(message)
                viewModel.clearError()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.expense_list_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSummary,
                        modifier = Modifier.semantics {
                            contentDescription = "View expense summary"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Assessment,
                            contentDescription = stringResource(R.string.cd_expense_summary)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToAddExpense,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.expense_add_button)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = { viewModel.refreshExpenses() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Balance widget
                item(key = "balance") {
                    BalanceWidget(
                        balanceCents = uiState.balanceCents,
                        currencyCode = uiState.currencyCode
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Filter bar
                item(key = "filters") {
                    ExpenseFilterBar(
                        filter = uiState.filter,
                        onFilterChanged = { viewModel.applyFilter(it) },
                        onClearFilters = { viewModel.clearFilters() }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // Expense list
                if (uiState.expenses.isEmpty() && !uiState.isLoading) {
                    item(key = "empty") {
                        EmptyExpenseState(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    items(
                        items = uiState.expenses,
                        key = { it.expense.expenseId.toString() }
                    ) { expenseWithStatus ->
                        ExpenseCard(
                            expenseWithStatus = expenseWithStatus,
                            currencyCode = uiState.currencyCode,
                            onClick = {
                                onNavigateToDetail(
                                    expenseWithStatus.expense.expenseId.toString()
                                )
                            }
                        )
                    }
                }

                // Bottom spacer for FAB clearance
                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun ExpenseFilterBar(
    filter: ExpenseFilter,
    onFilterChanged: (ExpenseFilter) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCategoryMenu by remember { mutableStateOf(false) }
    var showStatusMenu by remember { mutableStateOf(false) }
    val hasActiveFilters = filter.category != null || filter.status != null ||
        filter.childId != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Expense filters" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.FilterList,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Category filter
        Box {
            FilterChip(
                selected = filter.category != null,
                onClick = { showCategoryMenu = true },
                label = {
                    Text(
                        text = filter.category?.let { categoryLabel(it) }
                            ?: stringResource(R.string.expense_filter_category)
                    )
                }
            )
            DropdownMenu(
                expanded = showCategoryMenu,
                onDismissRequest = { showCategoryMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.expense_filter_all)) },
                    onClick = {
                        onFilterChanged(filter.copy(category = null))
                        showCategoryMenu = false
                    }
                )
                ExpenseCategory.entries.forEach { cat ->
                    DropdownMenuItem(
                        text = { Text(categoryLabel(cat)) },
                        onClick = {
                            onFilterChanged(filter.copy(category = cat))
                            showCategoryMenu = false
                        }
                    )
                }
            }
        }

        // Status filter
        Box {
            FilterChip(
                selected = filter.status != null,
                onClick = { showStatusMenu = true },
                label = {
                    Text(
                        text = filter.status?.name
                            ?: stringResource(R.string.expense_filter_status)
                    )
                }
            )
            DropdownMenu(
                expanded = showStatusMenu,
                onDismissRequest = { showStatusMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.expense_filter_all)) },
                    onClick = {
                        onFilterChanged(filter.copy(status = null))
                        showStatusMenu = false
                    }
                )
                ExpenseStatusType.entries.forEach { status ->
                    DropdownMenuItem(
                        text = { Text(status.name) },
                        onClick = {
                            onFilterChanged(filter.copy(status = status))
                            showStatusMenu = false
                        }
                    )
                }
            }
        }

        if (hasActiveFilters) {
            FilterChip(
                selected = false,
                onClick = onClearFilters,
                label = { Text(stringResource(R.string.expense_filter_clear)) }
            )
        }
    }
}

@Composable
private fun ExpenseCard(
    expenseWithStatus: ExpenseWithStatus,
    currencyCode: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val entity = expenseWithStatus.expense
    val category = try { ExpenseCategory.valueOf(entity.category) }
    catch (_: IllegalArgumentException) { ExpenseCategory.OTHER }
    val date = try { LocalDate.parse(entity.incurredAt) }
    catch (_: Exception) { null }

    val formattedAmount = formatCurrency(entity.amountCents.toLong(), entity.currencyCode)
    val formattedDate = date?.format(
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    ) ?: entity.incurredAt

    val cardDescription = "$formattedAmount for ${entity.description}, " +
        "${categoryLabel(category)}, $formattedDate, " +
        "status ${expenseWithStatus.latestStatus.name}"

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = cardDescription },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category icon
            Icon(
                imageVector = categoryIcon(category),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Description and date
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.description,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$formattedDate  \u2022  ${categoryLabel(category)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Amount and status
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formattedAmount,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ExpenseStatusBadge(status = expenseWithStatus.latestStatus)
            }
        }
    }
}

@Composable
private fun EmptyExpenseState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.expense_list_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.expense_list_empty_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
