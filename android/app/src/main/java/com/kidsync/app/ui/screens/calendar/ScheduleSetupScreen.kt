package com.kidsync.app.ui.screens.calendar

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kidsync.app.R
import com.kidsync.app.ui.components.PatternPreview
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.theme.Amber40
import com.kidsync.app.ui.theme.Blue40
import com.kidsync.app.ui.viewmodel.PatternPreset
import com.kidsync.app.ui.viewmodel.ScheduleSetupViewModel

/**
 * Schedule setup screen with visual pattern picker showing common custody presets.
 *
 * Each preset displays:
 * - Pattern name (e.g., "Week-on/Week-off")
 * - Description of the rotation
 * - Visual 2-week preview grid
 *
 * Also includes a "Custom" option to build a custom pattern.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleSetupScreen(
    onBack: () -> Unit,
    onPresetSelected: () -> Unit,
    onCustomSelected: () -> Unit,
    viewModel: ScheduleSetupViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.calendar_schedule_setup),
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.calendar_choose_pattern),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )

            Text(
                text = stringResource(R.string.calendar_choose_pattern_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Preset patterns
            PatternPresetCard(
                title = stringResource(R.string.calendar_pattern_7_7),
                description = stringResource(R.string.calendar_pattern_7_7_desc),
                preset = PatternPreset.WEEK_ON_WEEK_OFF,
                parentAName = uiState.parentAName,
                parentBName = uiState.parentBName,
                isSelected = uiState.setupPattern == PatternPreset.WEEK_ON_WEEK_OFF.pattern,
                onClick = {
                    viewModel.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
                    onPresetSelected()
                }
            )

            PatternPresetCard(
                title = stringResource(R.string.calendar_pattern_2_2_3),
                description = stringResource(R.string.calendar_pattern_2_2_3_desc),
                preset = PatternPreset.TWO_TWO_THREE,
                parentAName = uiState.parentAName,
                parentBName = uiState.parentBName,
                isSelected = uiState.setupPattern == PatternPreset.TWO_TWO_THREE.pattern,
                onClick = {
                    viewModel.selectPresetPattern(PatternPreset.TWO_TWO_THREE)
                    onPresetSelected()
                }
            )

            PatternPresetCard(
                title = stringResource(R.string.calendar_pattern_2_2_5_5),
                description = stringResource(R.string.calendar_pattern_2_2_5_5_desc),
                preset = PatternPreset.TWO_TWO_FIVE_FIVE,
                parentAName = uiState.parentAName,
                parentBName = uiState.parentBName,
                isSelected = uiState.setupPattern == PatternPreset.TWO_TWO_FIVE_FIVE.pattern,
                onClick = {
                    viewModel.selectPresetPattern(PatternPreset.TWO_TWO_FIVE_FIVE)
                    onPresetSelected()
                }
            )

            PatternPresetCard(
                title = stringResource(R.string.calendar_pattern_alt_weekends),
                description = stringResource(R.string.calendar_pattern_alt_weekends_desc),
                preset = PatternPreset.ALTERNATING_WEEKENDS,
                parentAName = uiState.parentAName,
                parentBName = uiState.parentBName,
                isSelected = uiState.setupPattern == PatternPreset.ALTERNATING_WEEKENDS.pattern,
                onClick = {
                    viewModel.selectPresetPattern(PatternPreset.ALTERNATING_WEEKENDS)
                    onPresetSelected()
                }
            )

            // Custom option
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onCustomSelected)
                    .semantics {
                        contentDescription = "Custom pattern: build your own custody schedule"
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.calendar_pattern_custom),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.calendar_pattern_custom_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PatternPresetCard(
    title: String,
    description: String,
    preset: PatternPreset,
    parentAName: String,
    parentBName: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "$title: $description"
            },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            PatternPreview(
                pattern = preset.pattern,
                weeksToShow = 2,
                parentAName = parentAName,
                parentBName = parentBName,
                parentAColor = Blue40,
                parentBColor = Amber40,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
