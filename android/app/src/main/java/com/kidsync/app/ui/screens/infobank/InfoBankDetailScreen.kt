package com.kidsync.app.ui.screens.infobank

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
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.ContactPhone
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tag
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
import com.kidsync.app.data.local.entity.InfoBankEntryEntity
import com.kidsync.app.domain.model.InfoBankCategory
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.viewmodel.InfoBankViewModel
import kotlinx.coroutines.launch

/**
 * Detail screen displaying all fields for a single Info Bank entry.
 * Shows category-specific fields with icons, and action buttons
 * for edit and delete operations.
 */
@Composable
fun InfoBankDetailScreen(
    entryId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InfoBankViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(entryId) {
        viewModel.selectEntry(entryId)
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            scope.launch {
                snackbarHostState.showSnackbar(message)
                viewModel.clearError()
            }
        }
    }

    val entry = uiState.selectedEntry

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.info_bank_detail_title),
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        if (entry == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Loading entry details"
                        }
                    )
                } else {
                    Text(
                        text = stringResource(R.string.info_bank_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        val category = try { InfoBankCategory.valueOf(entry.category) }
        catch (_: IllegalArgumentException) { InfoBankCategory.NOTE }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Category header card
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
                        imageVector = infoBankCategoryIcon(category),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = infoBankCategoryLabel(category),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.semantics { heading() }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Category-specific fields
            when (category) {
                InfoBankCategory.MEDICAL -> MedicalDetailFields(entry)
                InfoBankCategory.SCHOOL -> SchoolDetailFields(entry)
                InfoBankCategory.EMERGENCY_CONTACT -> EmergencyContactDetailFields(entry)
                InfoBankCategory.NOTE -> NoteDetailFields(entry)
            }

            // Common notes field
            entry.notes?.let { notes ->
                if (notes.isNotBlank()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    DetailRow(
                        icon = Icons.Filled.Note,
                        label = stringResource(R.string.info_bank_notes),
                        value = notes
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onEdit(entry.entryId.toString()) },
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "Edit this entry"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.info_bank_edit))
                }

                OutlinedButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "Delete this entry"
                        },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.info_bank_delete))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.info_bank_delete_confirm_title)) },
            text = { Text(stringResource(R.string.info_bank_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        entry?.let { viewModel.deleteEntry(it.entryId) }
                        showDeleteDialog = false
                        onBack()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.info_bank_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.expense_dispute_cancel))
                }
            }
        )
    }
}

@Composable
private fun MedicalDetailFields(entry: InfoBankEntryEntity) {
    val fields = listOfNotNull(
        entry.allergies?.let { Triple(Icons.Filled.LocalHospital, R.string.info_bank_medical_allergies, it) },
        entry.medicationName?.let { Triple(Icons.Filled.MedicalServices, R.string.info_bank_medical_medication_name, it) },
        entry.medicationDosage?.let { Triple(Icons.Filled.MedicalServices, R.string.info_bank_medical_dosage, it) },
        entry.medicationSchedule?.let { Triple(Icons.Filled.CalendarToday, R.string.info_bank_medical_schedule, it) },
        entry.doctorName?.let { Triple(Icons.Filled.Person, R.string.info_bank_medical_doctor, it) },
        entry.doctorPhone?.let { Triple(Icons.Filled.Phone, R.string.info_bank_medical_doctor_phone, it) },
        entry.insuranceInfo?.let { Triple(Icons.Filled.Shield, R.string.info_bank_medical_insurance, it) },
        entry.bloodType?.let { Triple(Icons.Filled.Cake, R.string.info_bank_medical_blood_type, it) }
    )

    fields.forEachIndexed { index, (icon, labelRes, value) ->
        DetailRow(
            icon = icon,
            label = stringResource(labelRes),
            value = value
        )
        if (index < fields.lastIndex) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun SchoolDetailFields(entry: InfoBankEntryEntity) {
    val fields = listOfNotNull(
        entry.schoolName?.let { Triple(Icons.Filled.School, R.string.info_bank_school_name, it) },
        entry.teacherNames?.let { Triple(Icons.Filled.Person, R.string.info_bank_school_teachers, it) },
        entry.gradeClass?.let { Triple(Icons.Filled.School, R.string.info_bank_school_grade, it) },
        entry.schoolPhone?.let { Triple(Icons.Filled.Phone, R.string.info_bank_school_phone, it) },
        entry.scheduleNotes?.let { Triple(Icons.Filled.CalendarToday, R.string.info_bank_school_schedule, it) }
    )

    fields.forEachIndexed { index, (icon, labelRes, value) ->
        DetailRow(
            icon = icon,
            label = stringResource(labelRes),
            value = value
        )
        if (index < fields.lastIndex) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun EmergencyContactDetailFields(entry: InfoBankEntryEntity) {
    val fields = listOfNotNull(
        entry.contactName?.let { Triple(Icons.Filled.Person, R.string.info_bank_emergency_name, it) },
        entry.relationship?.let { Triple(Icons.Filled.ContactPhone, R.string.info_bank_emergency_relationship, it) },
        entry.phone?.let { Triple(Icons.Filled.Phone, R.string.info_bank_emergency_phone, it) },
        entry.email?.let { Triple(Icons.Filled.Email, R.string.info_bank_emergency_email, it) }
    )

    fields.forEachIndexed { index, (icon, labelRes, value) ->
        DetailRow(
            icon = icon,
            label = stringResource(labelRes),
            value = value
        )
        if (index < fields.lastIndex) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun NoteDetailFields(entry: InfoBankEntryEntity) {
    val fields = listOfNotNull(
        entry.title?.let { Triple(Icons.Filled.Note, R.string.info_bank_note_title, it) },
        entry.content?.let { Triple(Icons.Filled.Note, R.string.info_bank_note_content, it) },
        entry.tag?.let { Triple(Icons.Filled.Tag, R.string.info_bank_note_tag, it) }
    )

    fields.forEachIndexed { index, (icon, labelRes, value) ->
        DetailRow(
            icon = icon,
            label = stringResource(labelRes),
            value = value
        )
        if (index < fields.lastIndex) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }
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
        verticalAlignment = Alignment.Top
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
