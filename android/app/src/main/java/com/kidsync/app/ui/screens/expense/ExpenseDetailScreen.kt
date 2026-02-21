package com.kidsync.app.ui.screens.expense

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Report
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsync.app.R
import com.kidsync.app.data.local.entity.ExpenseStatusEntity
import com.kidsync.app.domain.model.ExpenseCategory
import com.kidsync.app.domain.model.ExpenseStatusType
import com.kidsync.app.ui.components.ExpenseStatusBadge
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.components.categoryIcon
import com.kidsync.app.ui.components.categoryLabel
import com.kidsync.app.ui.components.formatCurrency
import com.kidsync.app.ui.viewmodel.ExpenseViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Expense detail screen showing full expense info, receipt image placeholder,
 * status history timeline, and action buttons for Acknowledge/Dispute.
 */
@Composable
fun ExpenseDetailScreen(
    expenseId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDisputeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(expenseId) {
        viewModel.selectExpense(expenseId)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            scope.launch {
                snackbarHostState.showSnackbar(message)
                viewModel.clearError()
            }
        }
    }

    val selected = uiState.selectedExpense

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.expense_detail_title),
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        if (selected == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Loading expense details"
                        }
                    )
                } else {
                    Text(
                        text = stringResource(R.string.expense_detail_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        val entity = selected.expense
        val category = try { ExpenseCategory.valueOf(entity.category) }
        catch (_: IllegalArgumentException) { ExpenseCategory.OTHER }
        val date = try { LocalDate.parse(entity.incurredAt) }
        catch (_: Exception) { null }
        val formattedAmount = formatCurrency(entity.amountCents.toLong(), entity.currencyCode)
        val formattedDate = date?.format(
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
        ) ?: entity.incurredAt
        val splitPercent = (entity.payerResponsibilityRatio * 100).toInt()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Amount and status header
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = categoryIcon(category),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = formattedAmount,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ExpenseStatusBadge(status = selected.latestStatus)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Detail rows
            DetailRow(
                icon = Icons.Filled.Description,
                label = stringResource(R.string.expense_detail_description),
                value = entity.description
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DetailRow(
                icon = categoryIcon(category),
                label = stringResource(R.string.expense_detail_category),
                value = categoryLabel(category)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DetailRow(
                icon = Icons.Filled.CalendarToday,
                label = stringResource(R.string.expense_detail_date),
                value = formattedDate
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DetailRow(
                icon = Icons.Filled.Person,
                label = stringResource(R.string.expense_detail_paid_by),
                value = entity.paidByDeviceId.take(8) + "..."
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            DetailRow(
                icon = Icons.Filled.Description,
                label = stringResource(R.string.expense_detail_split),
                value = stringResource(R.string.expense_detail_split_value, splitPercent, 100 - splitPercent)
            )

            // Receipt placeholder
            if (entity.receiptBlobId != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Receipt image attached"
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Receipt,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.expense_detail_receipt_attached),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Status history timeline
            Text(
                text = stringResource(R.string.expense_detail_history),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (selected.statusHistory.isEmpty()) {
                StatusTimelineEntry(
                    status = ExpenseStatusType.LOGGED,
                    timestamp = entity.clientTimestamp ?: "",
                    note = null,
                    isLast = true
                )
            } else {
                selected.statusHistory.forEachIndexed { index, statusEntity ->
                    StatusTimelineEntry(
                        status = try { ExpenseStatusType.valueOf(statusEntity.status) }
                        catch (_: IllegalArgumentException) { ExpenseStatusType.LOGGED },
                        timestamp = statusEntity.clientTimestamp,
                        note = statusEntity.note,
                        isLast = index == selected.statusHistory.lastIndex
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons (only for PENDING status)
            if (selected.latestStatus == ExpenseStatusType.LOGGED) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.acknowledgeExpense(entity.expenseId)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Acknowledge this expense"
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.expense_action_acknowledge))
                    }

                    OutlinedButton(
                        onClick = { showDisputeDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Dispute this expense"
                            },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Report,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.expense_action_dispute))
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Dispute dialog
    if (showDisputeDialog) {
        DisputeDialog(
            onConfirm = { note ->
                selected?.let {
                    viewModel.disputeExpense(it.expense.expenseId, note)
                }
                showDisputeDialog = false
            },
            onDismiss = { showDisputeDialog = false }
        )
    }
}

@Composable
private fun DetailRow(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "$label: $value" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun StatusTimelineEntry(
    status: ExpenseStatusType,
    timestamp: String,
    note: String?,
    isLast: Boolean,
    modifier: Modifier = Modifier
) {
    val icon = when (status) {
        ExpenseStatusType.LOGGED -> Icons.Filled.HourglassBottom
        ExpenseStatusType.ACKNOWLEDGED -> Icons.Filled.CheckCircle
        ExpenseStatusType.DISPUTED -> Icons.Filled.Report
    }

    val formattedTime = try {
        val instant = Instant.parse(timestamp)
        val localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
        localDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    } catch (_: Exception) {
        timestamp.take(10)
    }

    val entryDescription = "Status: ${status.name}, Date: $formattedTime" +
        (note?.let { ", Note: $it" } ?: "")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .semantics { contentDescription = entryDescription },
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = status.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!note.isNullOrBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun DisputeDialog(
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var disputeNote by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.expense_dispute_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.expense_dispute_description),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = disputeNote,
                    onValueChange = { disputeNote = it },
                    label = { Text(stringResource(R.string.expense_dispute_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(disputeNote.ifBlank { null }) }
            ) {
                Text(
                    text = stringResource(R.string.expense_action_dispute),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.expense_dispute_cancel))
            }
        }
    )
}
