package com.kidsync.app.ui.screens.expense

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsync.app.R
import com.kidsync.app.domain.model.ExpenseCategory
import com.kidsync.app.ui.components.BarChartEntry
import com.kidsync.app.ui.components.SimpleBarChart
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.components.categoryLabel
import com.kidsync.app.ui.components.formatCurrency
import com.kidsync.app.ui.viewmodel.ExpenseViewModel
import com.kidsync.app.ui.viewmodel.SummaryPeriod
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Expense summary screen showing monthly/yearly breakdown with bar charts,
 * per-category totals, and share comparison.
 */
@Composable
fun ExpenseSummaryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.loadSummary()
    }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.expense_summary_title),
                onBack = onBack
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Period toggle (Monthly / Yearly)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Select summary period"
                    }
            ) {
                SegmentedButton(
                    selected = uiState.summaryPeriod == SummaryPeriod.MONTHLY,
                    onClick = { viewModel.onSummaryPeriodChanged(SummaryPeriod.MONTHLY) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.expense_summary_monthly))
                }
                SegmentedButton(
                    selected = uiState.summaryPeriod == SummaryPeriod.YEARLY,
                    onClick = { viewModel.onSummaryPeriodChanged(SummaryPeriod.YEARLY) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.expense_summary_yearly))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Month/year navigation
            val periodLabel = when (uiState.summaryPeriod) {
                SummaryPeriod.MONTHLY -> uiState.summaryMonth.format(
                    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
                )
                SummaryPeriod.YEARLY -> uiState.summaryMonth.year.toString()
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Current period: $periodLabel"
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { viewModel.navigateSummaryBackward() },
                    modifier = Modifier.semantics {
                        contentDescription = "Go to previous period"
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = null
                    )
                }
                Text(
                    text = periodLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .width(200.dp)
                        .semantics { heading() },
                    textAlign = TextAlign.Center
                )
                IconButton(
                    onClick = { viewModel.navigateSummaryForward() },
                    modifier = Modifier.semantics {
                        contentDescription = "Go to next period"
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Summary totals card
            val summary = uiState.summary
            if (summary != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.expense_summary_total),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Text(
                            text = formatCurrency(
                                summary.totalExpensesCents,
                                uiState.currencyCode
                            ),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.expense_summary_your_share),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                        alpha = 0.7f
                                    )
                                )
                                Text(
                                    text = formatCurrency(
                                        summary.parentAShareCents,
                                        uiState.currencyCode
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = stringResource(R.string.expense_summary_their_share),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                        alpha = 0.7f
                                    )
                                )
                                Text(
                                    text = formatCurrency(
                                        summary.parentBShareCents,
                                        uiState.currencyCode
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(
                                R.string.expense_summary_count,
                                summary.expenseCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Bar chart by category
                if (uiState.categoryTotals.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.expense_summary_by_category),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val chartColors = listOf(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.colorScheme.secondary,
                        MaterialTheme.colorScheme.tertiary,
                        MaterialTheme.colorScheme.error,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
                    )

                    val barEntries = uiState.categoryTotals.mapIndexed { index, catTotal ->
                        BarChartEntry(
                            label = categoryLabel(catTotal.category),
                            value = catTotal.totalCents,
                            formattedValue = formatCurrency(
                                catTotal.totalCents,
                                uiState.currencyCode
                            ),
                            color = chartColors[index % chartColors.size]
                        )
                    }

                    SimpleBarChart(
                        entries = barEntries,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Category breakdown list
                    uiState.categoryTotals.forEach { catTotal ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .semantics {
                                    contentDescription = "${categoryLabel(catTotal.category)}: " +
                                        formatCurrency(catTotal.totalCents, uiState.currencyCode)
                                },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = categoryLabel(catTotal.category),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = formatCurrency(catTotal.totalCents, uiState.currencyCode),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.expense_summary_no_data),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp)
                    )
                }
            } else if (!uiState.isLoading) {
                Text(
                    text = stringResource(R.string.expense_summary_no_data),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
