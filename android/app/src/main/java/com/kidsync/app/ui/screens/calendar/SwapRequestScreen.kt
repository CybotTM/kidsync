package com.kidsync.app.ui.screens.calendar

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kidsync.app.R
import com.kidsync.app.ui.components.DateRangePicker
import com.kidsync.app.ui.components.LoadingButton
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.theme.Amber40
import com.kidsync.app.ui.theme.Blue40
import com.kidsync.app.ui.viewmodel.SwapRequestViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Swap request screen for creating custody swap requests.
 *
 * Features:
 * - Date range picker (start + end date)
 * - Optional reason/note input
 * - Preview showing which days would change and how
 * - Submit button
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwapRequestScreen(
    startDate: String?,
    onBack: () -> Unit,
    onSwapSubmitted: () -> Unit,
    viewModel: SwapRequestViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

    val parentAColor = Blue40
    val parentBColor = Amber40

    // Initialize from passed date
    LaunchedEffect(startDate) {
        startDate?.let {
            val date = LocalDate.parse(it)
            viewModel.initializeSwapForDate(date)
        }
    }

    // Handle errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Validate date range
    val dateValidationError = when {
        uiState.swapStartDate != null && uiState.swapEndDate != null &&
            uiState.swapEndDate!!.isBefore(uiState.swapStartDate) ->
            stringResource(R.string.calendar_date_range_error)
        else -> null
    }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.calendar_request_swap),
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        // In solo mode, swap requests are not needed
        if (uiState.isSolo) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.calendar_swap_solo_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.calendar_swap_solo_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.calendar_swap_heading),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() }
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.calendar_swap_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Date range picker
            item {
                Text(
                    text = stringResource(R.string.calendar_swap_dates),
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                DateRangePicker(
                    startDate = uiState.swapStartDate,
                    endDate = uiState.swapEndDate,
                    onStartDateSelected = { viewModel.setSwapStartDate(it) },
                    onEndDateSelected = { viewModel.setSwapEndDate(it) },
                    validationError = dateValidationError,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Reason/note
            item {
                Text(
                    text = stringResource(R.string.calendar_swap_reason),
                    style = MaterialTheme.typography.titleSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = uiState.swapNote,
                    onValueChange = { viewModel.setSwapNote(it) },
                    label = { Text(stringResource(R.string.calendar_swap_reason_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
            }

            // Preview section
            if (uiState.swapPreview.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.calendar_swap_preview),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { heading() }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.calendar_swap_preview_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(uiState.swapPreview) { day ->
                    val parentName = when (day.assignedParentId) {
                        uiState.parentAId -> uiState.parentAName
                        uiState.parentBId -> uiState.parentBName
                        else -> ""
                    }
                    val parentColor = when (day.assignedParentId) {
                        uiState.parentAId -> parentAColor
                        uiState.parentBId -> parentBColor
                        else -> Color.Transparent
                    }

                    SwapPreviewRow(
                        date = day.date,
                        parentName = parentName,
                        parentColor = parentColor,
                        dateFormatter = dateFormatter
                    )
                }
            }

            // Submit button
            item {
                Spacer(modifier = Modifier.height(8.dp))

                LoadingButton(
                    text = stringResource(R.string.calendar_submit_swap),
                    onClick = {
                        viewModel.submitSwapRequest()
                        onSwapSubmitted()
                    },
                    isLoading = uiState.isSubmittingSwap,
                    enabled = uiState.swapStartDate != null &&
                        uiState.swapEndDate != null &&
                        dateValidationError == null,
                    loadingDescription = stringResource(R.string.cd_submitting_swap),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SwapPreviewRow(
    date: LocalDate,
    parentName: String,
    parentColor: Color,
    dateFormatter: DateTimeFormatter,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${date.format(dateFormatter)}: would be assigned to $parentName"
            },
        colors = CardDefaults.cardColors(
            containerColor = parentColor.copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = date.format(dateFormatter),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(parentColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = parentName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
