package com.kidsync.app.ui.screens.infobank

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsync.app.R
import com.kidsync.app.domain.model.InfoBankCategory
import com.kidsync.app.ui.components.LoadingButton
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.viewmodel.InfoBankViewModel
import kotlinx.coroutines.launch

/**
 * Add/edit form for Info Bank entries. The form adapts its fields
 * based on the selected category type (Medical, School,
 * Emergency Contact, or Note).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoBankFormScreen(
    entryId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InfoBankViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showCategoryDropdown by remember { mutableStateOf(false) }
    var showChildDropdown by remember { mutableStateOf(false) }

    val isEditing = entryId != null

    // Load entry for editing
    LaunchedEffect(entryId) {
        if (entryId != null) {
            viewModel.selectEntry(entryId)
        } else {
            viewModel.resetForm()
        }
    }

    // Populate form when entry is loaded for editing
    LaunchedEffect(uiState.selectedEntry) {
        if (isEditing && uiState.selectedEntry != null && !uiState.isEditing) {
            viewModel.prepareEditForm(uiState.selectedEntry!!)
        }
    }

    // Navigate back on save
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            viewModel.resetSavedState()
            viewModel.resetForm()
            onSaved()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            scope.launch {
                snackbarHostState.showSnackbar(message)
                viewModel.clearError()
            }
        }
    }

    val titleRes = if (isEditing) R.string.info_bank_edit_title else R.string.info_bank_add_title

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(titleRes),
                onBack = {
                    viewModel.resetForm()
                    onBack()
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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

            // Category selector (disabled when editing)
            if (!isEditing) {
                ExposedDropdownMenuBox(
                    expanded = showCategoryDropdown,
                    onExpandedChange = { showCategoryDropdown = it }
                ) {
                    OutlinedTextField(
                        value = infoBankCategoryLabel(uiState.formCategory),
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(stringResource(R.string.info_bank_form_category)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryDropdown)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = showCategoryDropdown,
                        onDismissRequest = { showCategoryDropdown = false }
                    ) {
                        InfoBankCategory.entries.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(infoBankCategoryLabel(cat)) },
                                onClick = {
                                    viewModel.onFormCategoryChanged(cat)
                                    showCategoryDropdown = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Child selector
            ExposedDropdownMenuBox(
                expanded = showChildDropdown,
                onExpandedChange = { showChildDropdown = it }
            ) {
                val selectedChild = uiState.availableChildren.find {
                    it.first == uiState.formChildId
                }
                OutlinedTextField(
                    value = selectedChild?.second ?: "",
                    onValueChange = { },
                    readOnly = true,
                    label = { Text(stringResource(R.string.info_bank_form_child)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showChildDropdown)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                )
                ExposedDropdownMenu(
                    expanded = showChildDropdown,
                    onDismissRequest = { showChildDropdown = false }
                ) {
                    uiState.availableChildren.forEach { (childId, childName) ->
                        DropdownMenuItem(
                            text = { Text(childName) },
                            onClick = {
                                viewModel.onFormChildSelected(childId)
                                showChildDropdown = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Category-specific fields
            when (uiState.formCategory) {
                InfoBankCategory.MEDICAL -> MedicalFormFields(
                    allergies = uiState.formAllergies,
                    onAllergiesChanged = viewModel::onAllergiesChanged,
                    medicationName = uiState.formMedicationName,
                    onMedicationNameChanged = viewModel::onMedicationNameChanged,
                    medicationDosage = uiState.formMedicationDosage,
                    onMedicationDosageChanged = viewModel::onMedicationDosageChanged,
                    medicationSchedule = uiState.formMedicationSchedule,
                    onMedicationScheduleChanged = viewModel::onMedicationScheduleChanged,
                    doctorName = uiState.formDoctorName,
                    onDoctorNameChanged = viewModel::onDoctorNameChanged,
                    doctorPhone = uiState.formDoctorPhone,
                    onDoctorPhoneChanged = viewModel::onDoctorPhoneChanged,
                    insuranceInfo = uiState.formInsuranceInfo,
                    onInsuranceInfoChanged = viewModel::onInsuranceInfoChanged,
                    bloodType = uiState.formBloodType,
                    onBloodTypeChanged = viewModel::onBloodTypeChanged
                )
                InfoBankCategory.SCHOOL -> SchoolFormFields(
                    schoolName = uiState.formSchoolName,
                    onSchoolNameChanged = viewModel::onSchoolNameChanged,
                    teacherNames = uiState.formTeacherNames,
                    onTeacherNamesChanged = viewModel::onTeacherNamesChanged,
                    gradeClass = uiState.formGradeClass,
                    onGradeClassChanged = viewModel::onGradeClassChanged,
                    schoolPhone = uiState.formSchoolPhone,
                    onSchoolPhoneChanged = viewModel::onSchoolPhoneChanged,
                    scheduleNotes = uiState.formScheduleNotes,
                    onScheduleNotesChanged = viewModel::onScheduleNotesChanged
                )
                InfoBankCategory.EMERGENCY_CONTACT -> EmergencyContactFormFields(
                    contactName = uiState.formContactName,
                    onContactNameChanged = viewModel::onContactNameChanged,
                    relationship = uiState.formRelationship,
                    onRelationshipChanged = viewModel::onRelationshipChanged,
                    phone = uiState.formPhone,
                    onPhoneChanged = viewModel::onPhoneChanged,
                    email = uiState.formEmail,
                    onEmailChanged = viewModel::onEmailChanged
                )
                InfoBankCategory.NOTE -> NoteFormFields(
                    title = uiState.formTitle,
                    onTitleChanged = viewModel::onTitleChanged,
                    content = uiState.formContent,
                    onContentChanged = viewModel::onContentChanged,
                    tag = uiState.formTag,
                    onTagChanged = viewModel::onTagChanged
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Common notes field
            OutlinedTextField(
                value = uiState.formNotes,
                onValueChange = viewModel::onNotesChanged,
                label = { Text(stringResource(R.string.info_bank_notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Save button
            LoadingButton(
                text = stringResource(R.string.info_bank_save),
                onClick = { viewModel.saveEntry() },
                isLoading = uiState.isSaving,
                enabled = uiState.formChildId != null,
                loadingDescription = stringResource(R.string.cd_info_bank_saving),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ─── Medical Form Fields ─────────────────────────────────────────────────────

@Composable
private fun MedicalFormFields(
    allergies: String,
    onAllergiesChanged: (String) -> Unit,
    medicationName: String,
    onMedicationNameChanged: (String) -> Unit,
    medicationDosage: String,
    onMedicationDosageChanged: (String) -> Unit,
    medicationSchedule: String,
    onMedicationScheduleChanged: (String) -> Unit,
    doctorName: String,
    onDoctorNameChanged: (String) -> Unit,
    doctorPhone: String,
    onDoctorPhoneChanged: (String) -> Unit,
    insuranceInfo: String,
    onInsuranceInfoChanged: (String) -> Unit,
    bloodType: String,
    onBloodTypeChanged: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.info_bank_category_medical),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = allergies,
        onValueChange = onAllergiesChanged,
        label = { Text(stringResource(R.string.info_bank_medical_allergies)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = medicationName,
        onValueChange = onMedicationNameChanged,
        label = { Text(stringResource(R.string.info_bank_medical_medication_name)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = medicationDosage,
        onValueChange = onMedicationDosageChanged,
        label = { Text(stringResource(R.string.info_bank_medical_dosage)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = medicationSchedule,
        onValueChange = onMedicationScheduleChanged,
        label = { Text(stringResource(R.string.info_bank_medical_schedule)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = doctorName,
        onValueChange = onDoctorNameChanged,
        label = { Text(stringResource(R.string.info_bank_medical_doctor)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = doctorPhone,
        onValueChange = onDoctorPhoneChanged,
        label = { Text(stringResource(R.string.info_bank_medical_doctor_phone)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = insuranceInfo,
        onValueChange = onInsuranceInfoChanged,
        label = { Text(stringResource(R.string.info_bank_medical_insurance)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = bloodType,
        onValueChange = onBloodTypeChanged,
        label = { Text(stringResource(R.string.info_bank_medical_blood_type)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

// ─── School Form Fields ──────────────────────────────────────────────────────

@Composable
private fun SchoolFormFields(
    schoolName: String,
    onSchoolNameChanged: (String) -> Unit,
    teacherNames: String,
    onTeacherNamesChanged: (String) -> Unit,
    gradeClass: String,
    onGradeClassChanged: (String) -> Unit,
    schoolPhone: String,
    onSchoolPhoneChanged: (String) -> Unit,
    scheduleNotes: String,
    onScheduleNotesChanged: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.info_bank_category_school),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = schoolName,
        onValueChange = onSchoolNameChanged,
        label = { Text(stringResource(R.string.info_bank_school_name)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = teacherNames,
        onValueChange = onTeacherNamesChanged,
        label = { Text(stringResource(R.string.info_bank_school_teachers)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = gradeClass,
        onValueChange = onGradeClassChanged,
        label = { Text(stringResource(R.string.info_bank_school_grade)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = schoolPhone,
        onValueChange = onSchoolPhoneChanged,
        label = { Text(stringResource(R.string.info_bank_school_phone)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = scheduleNotes,
        onValueChange = onScheduleNotesChanged,
        label = { Text(stringResource(R.string.info_bank_school_schedule)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4
    )
}

// ─── Emergency Contact Form Fields ──────────────────────────────────────────

@Composable
private fun EmergencyContactFormFields(
    contactName: String,
    onContactNameChanged: (String) -> Unit,
    relationship: String,
    onRelationshipChanged: (String) -> Unit,
    phone: String,
    onPhoneChanged: (String) -> Unit,
    email: String,
    onEmailChanged: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.info_bank_category_emergency),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = contactName,
        onValueChange = onContactNameChanged,
        label = { Text(stringResource(R.string.info_bank_emergency_name)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = relationship,
        onValueChange = onRelationshipChanged,
        label = { Text(stringResource(R.string.info_bank_emergency_relationship)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = phone,
        onValueChange = onPhoneChanged,
        label = { Text(stringResource(R.string.info_bank_emergency_phone)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = email,
        onValueChange = onEmailChanged,
        label = { Text(stringResource(R.string.info_bank_emergency_email)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}

// ─── Note Form Fields ───────────────────────────────────────────────────────

@Composable
private fun NoteFormFields(
    title: String,
    onTitleChanged: (String) -> Unit,
    content: String,
    onContentChanged: (String) -> Unit,
    tag: String,
    onTagChanged: (String) -> Unit
) {
    Text(
        text = stringResource(R.string.info_bank_category_notes),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() }
    )
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = title,
        onValueChange = onTitleChanged,
        label = { Text(stringResource(R.string.info_bank_note_title)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = content,
        onValueChange = onContentChanged,
        label = { Text(stringResource(R.string.info_bank_note_content)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
        maxLines = 8
    )
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = tag,
        onValueChange = onTagChanged,
        label = { Text(stringResource(R.string.info_bank_note_tag)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true
    )
}
