package com.kidsync.app.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kidsync.app.R
import com.kidsync.app.ui.components.PatternPreview
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.theme.Amber40
import com.kidsync.app.ui.theme.Blue40
import com.kidsync.app.ui.viewmodel.ScheduleSetupViewModel

/**
 * Custom pattern builder screen.
 *
 * Allows the user to:
 * - Set cycle length (number of days, default 14)
 * - Tap each day in the cycle to toggle between Parent A and Parent B
 * - See a 4-week preview of the repeating pattern
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CustomPatternScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    viewModel: ScheduleSetupViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    val parentAColor = Blue40
    val parentBColor = Amber40

    // Initialize pattern if empty
    if (uiState.setupPattern.isEmpty()) {
        viewModel.setCustomCycleLength(14)
    }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.calendar_custom_pattern),
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

            Text(
                text = stringResource(R.string.calendar_build_pattern),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.calendar_build_pattern_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Cycle length input
            Text(
                text = stringResource(R.string.calendar_cycle_length),
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.setupCycleLength.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { viewModel.setCustomCycleLength(it) }
                },
                label = { Text(stringResource(R.string.calendar_days)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Tap-to-assign grid
            Text(
                text = stringResource(R.string.calendar_tap_to_assign),
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.calendar_tap_to_assign_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Parent color legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(parentAColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = uiState.parentAName,
                    style = MaterialTheme.typography.labelMedium
                )

                Spacer(modifier = Modifier.width(24.dp))

                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(parentBColor, CircleShape)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = uiState.parentBName,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pattern day grid
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                uiState.setupPattern.forEachIndexed { index, isParentA ->
                    val color = if (isParentA) parentAColor else parentBColor
                    val parentName = if (isParentA) uiState.parentAName else uiState.parentBName
                    val dayLabel = "Day ${index + 1}"
                    val description = "$dayLabel: $parentName. Tap to change"

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.25f))
                            .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .clickable { viewModel.togglePatternDay(index) }
                            .clearAndSetSemantics {
                                contentDescription = description
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(color, CircleShape)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4-week preview
            Text(
                text = stringResource(R.string.calendar_pattern_preview),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.setupPattern.isNotEmpty()) {
                PatternPreview(
                    pattern = uiState.setupPattern,
                    weeksToShow = 4,
                    parentAName = uiState.parentAName,
                    parentBName = uiState.parentBName,
                    parentAColor = parentAColor,
                    parentBColor = parentBColor,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Continue button
            Button(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = uiState.setupPattern.isNotEmpty()
            ) {
                Text(stringResource(R.string.calendar_continue))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
