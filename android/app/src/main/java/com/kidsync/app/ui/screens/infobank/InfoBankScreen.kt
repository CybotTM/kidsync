package com.kidsync.app.ui.screens.infobank

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
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsync.app.R
import com.kidsync.app.data.local.entity.InfoBankEntryEntity
import com.kidsync.app.domain.model.InfoBankCategory
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.viewmodel.InfoBankViewModel
import kotlinx.coroutines.launch

/**
 * Main Info Bank list screen showing entries grouped by category
 * (Medical, School, Emergency Contacts, Notes) with a child selector,
 * search bar, and category filter chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoBankScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToAddEntry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InfoBankViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }

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
            TopAppBarWithBack(
                title = stringResource(R.string.info_bank_title),
                onBack = onBack
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToAddEntry,
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null
                    )
                },
                text = { Text(stringResource(R.string.info_bank_add_entry)) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp,
                vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Search bar
            item(key = "search") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::onSearchQueryChanged,
                        placeholder = { Text(stringResource(R.string.info_bank_search)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null
                            )
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Search info bank entries"
                            }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Category filter chips
            item(key = "filters") {
                InfoBankFilterBar(
                    selectedCategory = uiState.selectedCategory,
                    onCategorySelected = { viewModel.filterByCategory(it) }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Grouped entries
            if (uiState.filteredEntries.isEmpty() && !uiState.isLoading) {
                item(key = "empty") {
                    EmptyInfoBankState(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                val categoryOrder = listOf(
                    InfoBankCategory.MEDICAL,
                    InfoBankCategory.SCHOOL,
                    InfoBankCategory.EMERGENCY_CONTACT,
                    InfoBankCategory.NOTE
                )

                categoryOrder.forEach { category ->
                    val entriesInCategory = uiState.filteredEntries[category]
                    if (!entriesInCategory.isNullOrEmpty()) {
                        item(key = "header_${category.name}") {
                            CategoryHeader(category = category)
                        }

                        items(
                            items = entriesInCategory,
                            key = { it.entryId.toString() }
                        ) { entry ->
                            InfoBankEntryCard(
                                entry = entry,
                                onClick = {
                                    onNavigateToDetail(entry.entryId.toString())
                                }
                            )
                        }
                    }
                }
            }

            // Bottom spacer for FAB clearance
            item(key = "bottom_spacer") {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun InfoBankFilterBar(
    selectedCategory: InfoBankCategory?,
    onCategorySelected: (InfoBankCategory?) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Info bank category filters" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Filled.FilterList,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        FilterChip(
            selected = selectedCategory == null,
            onClick = { onCategorySelected(null) },
            label = { Text(stringResource(R.string.expense_filter_all)) }
        )

        InfoBankCategory.entries.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = {
                    onCategorySelected(
                        if (selectedCategory == category) null else category
                    )
                },
                label = { Text(infoBankCategoryLabel(category)) }
            )
        }
    }
}

@Composable
private fun CategoryHeader(
    category: InfoBankCategory,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = infoBankCategoryIcon(category),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = infoBankCategoryLabel(category),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() }
        )
    }
}

@Composable
private fun InfoBankEntryCard(
    entry: InfoBankEntryEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val category = try { InfoBankCategory.valueOf(entry.category) }
    catch (_: IllegalArgumentException) { InfoBankCategory.NOTE }

    val entryTitle = getEntryTitle(entry, category)
    val entrySubtitle = getEntrySubtitle(entry, category)
    val cardDescription = "${infoBankCategoryLabel(category)}: $entryTitle"

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
            Icon(
                imageVector = infoBankCategoryIcon(category),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entryTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (entrySubtitle.isNotBlank()) {
                    Text(
                        text = entrySubtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyInfoBankState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.info_bank_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.info_bank_empty_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

@Composable
fun infoBankCategoryLabel(category: InfoBankCategory): String {
    return when (category) {
        InfoBankCategory.MEDICAL -> stringResource(R.string.info_bank_category_medical)
        InfoBankCategory.SCHOOL -> stringResource(R.string.info_bank_category_school)
        InfoBankCategory.EMERGENCY_CONTACT -> stringResource(R.string.info_bank_category_emergency)
        InfoBankCategory.NOTE -> stringResource(R.string.info_bank_category_notes)
    }
}

fun infoBankCategoryIcon(category: InfoBankCategory): ImageVector {
    return when (category) {
        InfoBankCategory.MEDICAL -> Icons.Filled.LocalHospital
        InfoBankCategory.SCHOOL -> Icons.Filled.School
        InfoBankCategory.EMERGENCY_CONTACT -> Icons.Filled.ContactPhone
        InfoBankCategory.NOTE -> Icons.Filled.Note
    }
}

private fun getEntryTitle(entry: InfoBankEntryEntity, category: InfoBankCategory): String {
    return when (category) {
        InfoBankCategory.MEDICAL -> {
            listOfNotNull(
                entry.doctorName?.let { "Dr. $it" },
                entry.allergies?.let { "Allergies: $it" },
                entry.medicationName
            ).firstOrNull() ?: "Medical Info"
        }
        InfoBankCategory.SCHOOL -> {
            entry.schoolName ?: "School Info"
        }
        InfoBankCategory.EMERGENCY_CONTACT -> {
            entry.contactName ?: "Emergency Contact"
        }
        InfoBankCategory.NOTE -> {
            entry.title ?: "Note"
        }
    }
}

private fun getEntrySubtitle(entry: InfoBankEntryEntity, category: InfoBankCategory): String {
    return when (category) {
        InfoBankCategory.MEDICAL -> {
            listOfNotNull(
                entry.bloodType?.let { "Blood type: $it" },
                entry.insuranceInfo
            ).joinToString(" | ")
        }
        InfoBankCategory.SCHOOL -> {
            listOfNotNull(
                entry.gradeClass,
                entry.teacherNames
            ).joinToString(" | ")
        }
        InfoBankCategory.EMERGENCY_CONTACT -> {
            listOfNotNull(
                entry.relationship,
                entry.phone
            ).joinToString(" | ")
        }
        InfoBankCategory.NOTE -> {
            entry.tag ?: ""
        }
    }
}
