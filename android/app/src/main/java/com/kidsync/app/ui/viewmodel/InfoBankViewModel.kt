package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.data.local.dao.InfoBankDao
import com.kidsync.app.data.local.entity.InfoBankEntryEntity
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.InfoBankCategory
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

// ─── UI State ────────────────────────────────────────────────────────────────

data class InfoBankUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // List
    val entries: List<InfoBankEntryEntity> = emptyList(),
    val filteredEntries: Map<InfoBankCategory, List<InfoBankEntryEntity>> = emptyMap(),
    val selectedCategory: InfoBankCategory? = null,
    val searchQuery: String = "",

    // Detail
    val selectedEntry: InfoBankEntryEntity? = null,

    // Form
    val formCategory: InfoBankCategory = InfoBankCategory.MEDICAL,
    val formChildId: UUID? = null,
    val formAllergies: String = "",
    val formMedicationName: String = "",
    val formMedicationDosage: String = "",
    val formMedicationSchedule: String = "",
    val formDoctorName: String = "",
    val formDoctorPhone: String = "",
    val formInsuranceInfo: String = "",
    val formBloodType: String = "",
    val formSchoolName: String = "",
    val formTeacherNames: String = "",
    val formGradeClass: String = "",
    val formSchoolPhone: String = "",
    val formScheduleNotes: String = "",
    val formContactName: String = "",
    val formRelationship: String = "",
    val formPhone: String = "",
    val formEmail: String = "",
    val formTitle: String = "",
    val formContent: String = "",
    val formTag: String = "",
    val formNotes: String = "",
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val isEditing: Boolean = false,
    val editingEntryId: UUID? = null,

    // Available children for selection
    val availableChildren: List<Pair<UUID, String>> = emptyList(),
    val selectedChildId: UUID? = null
)

@HiltViewModel
class InfoBankViewModel @Inject constructor(
    private val infoBankDao: InfoBankDao,
    private val createOperationUseCase: CreateOperationUseCase,
    private val authRepository: AuthRepository,
    private val bucketRepository: BucketRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(InfoBankUiState())
    val uiState: StateFlow<InfoBankUiState> = _uiState.asStateFlow()

    init {
        loadEntries()
    }

    // ─── List ────────────────────────────────────────────────────────────────

    fun loadEntries() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val childId = _uiState.value.selectedChildId
                val allEntries = if (childId != null) {
                    infoBankDao.getEntriesForChild(childId)
                } else {
                    infoBankDao.getAllEntries()
                }

                val grouped = groupAndFilter(allEntries)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        entries = allEntries,
                        filteredEntries = grouped
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load entries"
                    )
                }
            }
        }
    }

    fun selectChild(childId: UUID?) {
        _uiState.update { it.copy(selectedChildId = childId) }
        loadEntries()
    }

    fun filterByCategory(category: InfoBankCategory?) {
        _uiState.update { it.copy(selectedCategory = category) }
        val grouped = groupAndFilter(_uiState.value.entries)
        _uiState.update { it.copy(filteredEntries = grouped) }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        val grouped = groupAndFilter(_uiState.value.entries)
        _uiState.update { it.copy(filteredEntries = grouped) }
    }

    private fun groupAndFilter(
        entries: List<InfoBankEntryEntity>
    ): Map<InfoBankCategory, List<InfoBankEntryEntity>> {
        val state = _uiState.value
        val query = state.searchQuery.trim().lowercase()
        val categoryFilter = state.selectedCategory

        return entries
            .filter { entry ->
                val cat = try { InfoBankCategory.valueOf(entry.category) }
                catch (_: IllegalArgumentException) { null }
                val matchesCategory = categoryFilter == null || cat == categoryFilter
                val matchesSearch = query.isBlank() || entryMatchesSearch(entry, query)
                matchesCategory && matchesSearch
            }
            .groupBy { entry ->
                try { InfoBankCategory.valueOf(entry.category) }
                catch (_: IllegalArgumentException) { InfoBankCategory.NOTE }
            }
    }

    private fun entryMatchesSearch(entry: InfoBankEntryEntity, query: String): Boolean {
        val searchable = listOfNotNull(
            entry.allergies, entry.medicationName, entry.doctorName,
            entry.schoolName, entry.teacherNames,
            entry.contactName, entry.relationship, entry.phone, entry.email,
            entry.title, entry.content, entry.tag, entry.notes,
            entry.insuranceInfo, entry.bloodType, entry.gradeClass
        )
        return searchable.any { it.lowercase().contains(query) }
    }

    // ─── Detail ──────────────────────────────────────────────────────────────

    fun selectEntry(entryId: String) {
        viewModelScope.launch {
            val uuid = try { UUID.fromString(entryId) }
            catch (_: IllegalArgumentException) { return@launch }

            val entry = infoBankDao.getEntryById(uuid)
            _uiState.update { it.copy(selectedEntry = entry) }
        }
    }

    fun clearSelectedEntry() {
        _uiState.update { it.copy(selectedEntry = null) }
    }

    // ─── Form ────────────────────────────────────────────────────────────────

    fun onFormCategoryChanged(category: InfoBankCategory) {
        _uiState.update { it.copy(formCategory = category, error = null) }
    }

    fun onFormChildSelected(childId: UUID) {
        _uiState.update { it.copy(formChildId = childId, error = null) }
    }

    // Medical
    fun onAllergiesChanged(value: String) { _uiState.update { it.copy(formAllergies = value) } }
    fun onMedicationNameChanged(value: String) { _uiState.update { it.copy(formMedicationName = value) } }
    fun onMedicationDosageChanged(value: String) { _uiState.update { it.copy(formMedicationDosage = value) } }
    fun onMedicationScheduleChanged(value: String) { _uiState.update { it.copy(formMedicationSchedule = value) } }
    fun onDoctorNameChanged(value: String) { _uiState.update { it.copy(formDoctorName = value) } }
    fun onDoctorPhoneChanged(value: String) { _uiState.update { it.copy(formDoctorPhone = value) } }
    fun onInsuranceInfoChanged(value: String) { _uiState.update { it.copy(formInsuranceInfo = value) } }
    fun onBloodTypeChanged(value: String) { _uiState.update { it.copy(formBloodType = value) } }

    // School
    fun onSchoolNameChanged(value: String) { _uiState.update { it.copy(formSchoolName = value) } }
    fun onTeacherNamesChanged(value: String) { _uiState.update { it.copy(formTeacherNames = value) } }
    fun onGradeClassChanged(value: String) { _uiState.update { it.copy(formGradeClass = value) } }
    fun onSchoolPhoneChanged(value: String) { _uiState.update { it.copy(formSchoolPhone = value) } }
    fun onScheduleNotesChanged(value: String) { _uiState.update { it.copy(formScheduleNotes = value) } }

    // Emergency Contact
    fun onContactNameChanged(value: String) { _uiState.update { it.copy(formContactName = value) } }
    fun onRelationshipChanged(value: String) { _uiState.update { it.copy(formRelationship = value) } }
    fun onPhoneChanged(value: String) { _uiState.update { it.copy(formPhone = value) } }
    fun onEmailChanged(value: String) { _uiState.update { it.copy(formEmail = value) } }

    // Note
    fun onTitleChanged(value: String) { _uiState.update { it.copy(formTitle = value) } }
    fun onContentChanged(value: String) { _uiState.update { it.copy(formContent = value) } }
    fun onTagChanged(value: String) { _uiState.update { it.copy(formTag = value) } }

    // Common
    fun onNotesChanged(value: String) { _uiState.update { it.copy(formNotes = value) } }

    fun prepareEditForm(entry: InfoBankEntryEntity) {
        val category = try { InfoBankCategory.valueOf(entry.category) }
        catch (_: IllegalArgumentException) { InfoBankCategory.NOTE }

        _uiState.update {
            it.copy(
                isEditing = true,
                editingEntryId = entry.entryId,
                formCategory = category,
                formChildId = entry.childId,
                formAllergies = entry.allergies ?: "",
                formMedicationName = entry.medicationName ?: "",
                formMedicationDosage = entry.medicationDosage ?: "",
                formMedicationSchedule = entry.medicationSchedule ?: "",
                formDoctorName = entry.doctorName ?: "",
                formDoctorPhone = entry.doctorPhone ?: "",
                formInsuranceInfo = entry.insuranceInfo ?: "",
                formBloodType = entry.bloodType ?: "",
                formSchoolName = entry.schoolName ?: "",
                formTeacherNames = entry.teacherNames ?: "",
                formGradeClass = entry.gradeClass ?: "",
                formSchoolPhone = entry.schoolPhone ?: "",
                formScheduleNotes = entry.scheduleNotes ?: "",
                formContactName = entry.contactName ?: "",
                formRelationship = entry.relationship ?: "",
                formPhone = entry.phone ?: "",
                formEmail = entry.email ?: "",
                formTitle = entry.title ?: "",
                formContent = entry.content ?: "",
                formTag = entry.tag ?: "",
                formNotes = entry.notes ?: ""
            )
        }
    }

    fun resetForm() {
        _uiState.update {
            it.copy(
                isEditing = false,
                editingEntryId = null,
                formCategory = InfoBankCategory.MEDICAL,
                formChildId = null,
                formAllergies = "",
                formMedicationName = "",
                formMedicationDosage = "",
                formMedicationSchedule = "",
                formDoctorName = "",
                formDoctorPhone = "",
                formInsuranceInfo = "",
                formBloodType = "",
                formSchoolName = "",
                formTeacherNames = "",
                formGradeClass = "",
                formSchoolPhone = "",
                formScheduleNotes = "",
                formContactName = "",
                formRelationship = "",
                formPhone = "",
                formEmail = "",
                formTitle = "",
                formContent = "",
                formTag = "",
                formNotes = "",
                isSaved = false,
                error = null
            )
        }
    }

    fun saveEntry() {
        val state = _uiState.value

        // Validate
        if (state.formChildId == null) {
            _uiState.update { it.copy(error = "Please select a child") }
            return
        }

        when (state.formCategory) {
            InfoBankCategory.EMERGENCY_CONTACT -> {
                if (state.formContactName.isBlank()) {
                    _uiState.update { it.copy(error = "Please enter a contact name") }
                    return
                }
            }
            InfoBankCategory.NOTE -> {
                if (state.formTitle.isBlank()) {
                    _uiState.update { it.copy(error = "Please enter a title") }
                    return
                }
                if (state.formContent.isBlank()) {
                    _uiState.update { it.copy(error = "Please enter content") }
                    return
                }
            }
            else -> { /* Medical and School fields are all optional */ }
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

            val entryId = state.editingEntryId ?: UUID.randomUUID()
            val isUpdate = state.isEditing
            val operationType = if (isUpdate) OperationType.UPDATE else OperationType.CREATE

            val contentData = buildJsonObject {
                put("entryId", JsonPrimitive(entryId.toString()))
                put("childId", JsonPrimitive(state.formChildId.toString()))
                put("category", JsonPrimitive(state.formCategory.name))

                when (state.formCategory) {
                    InfoBankCategory.MEDICAL -> {
                        state.formAllergies.ifBlank { null }?.let { put("allergies", JsonPrimitive(it)) }
                        state.formMedicationName.ifBlank { null }?.let { put("medicationName", JsonPrimitive(it)) }
                        state.formMedicationDosage.ifBlank { null }?.let { put("medicationDosage", JsonPrimitive(it)) }
                        state.formMedicationSchedule.ifBlank { null }?.let { put("medicationSchedule", JsonPrimitive(it)) }
                        state.formDoctorName.ifBlank { null }?.let { put("doctorName", JsonPrimitive(it)) }
                        state.formDoctorPhone.ifBlank { null }?.let { put("doctorPhone", JsonPrimitive(it)) }
                        state.formInsuranceInfo.ifBlank { null }?.let { put("insuranceInfo", JsonPrimitive(it)) }
                        state.formBloodType.ifBlank { null }?.let { put("bloodType", JsonPrimitive(it)) }
                    }
                    InfoBankCategory.SCHOOL -> {
                        state.formSchoolName.ifBlank { null }?.let { put("schoolName", JsonPrimitive(it)) }
                        state.formTeacherNames.ifBlank { null }?.let { put("teacherNames", JsonPrimitive(it)) }
                        state.formGradeClass.ifBlank { null }?.let { put("gradeClass", JsonPrimitive(it)) }
                        state.formSchoolPhone.ifBlank { null }?.let { put("schoolPhone", JsonPrimitive(it)) }
                        state.formScheduleNotes.ifBlank { null }?.let { put("scheduleNotes", JsonPrimitive(it)) }
                    }
                    InfoBankCategory.EMERGENCY_CONTACT -> {
                        put("contactName", JsonPrimitive(state.formContactName.trim()))
                        state.formRelationship.ifBlank { null }?.let { put("relationship", JsonPrimitive(it)) }
                        state.formPhone.ifBlank { null }?.let { put("phone", JsonPrimitive(it)) }
                        state.formEmail.ifBlank { null }?.let { put("email", JsonPrimitive(it)) }
                    }
                    InfoBankCategory.NOTE -> {
                        put("title", JsonPrimitive(state.formTitle.trim()))
                        put("content", JsonPrimitive(state.formContent.trim()))
                        state.formTag.ifBlank { null }?.let { put("tag", JsonPrimitive(it)) }
                    }
                }
                state.formNotes.ifBlank { null }?.let { put("notes", JsonPrimitive(it)) }
            }

            val result = createOperationUseCase(
                bucketId = bucketId,
                entityType = EntityType.InfoBank,
                entityId = entryId.toString(),
                operationType = operationType,
                contentData = contentData
            )

            result.fold(
                onSuccess = {
                    // Also write locally immediately
                    val entity = InfoBankEntryEntity(
                        entryId = entryId,
                        childId = state.formChildId,
                        category = state.formCategory.name,
                        allergies = state.formAllergies.ifBlank { null },
                        medicationName = state.formMedicationName.ifBlank { null },
                        medicationDosage = state.formMedicationDosage.ifBlank { null },
                        medicationSchedule = state.formMedicationSchedule.ifBlank { null },
                        doctorName = state.formDoctorName.ifBlank { null },
                        doctorPhone = state.formDoctorPhone.ifBlank { null },
                        insuranceInfo = state.formInsuranceInfo.ifBlank { null },
                        bloodType = state.formBloodType.ifBlank { null },
                        schoolName = state.formSchoolName.ifBlank { null },
                        teacherNames = state.formTeacherNames.ifBlank { null },
                        gradeClass = state.formGradeClass.ifBlank { null },
                        schoolPhone = state.formSchoolPhone.ifBlank { null },
                        scheduleNotes = state.formScheduleNotes.ifBlank { null },
                        contactName = state.formContactName.ifBlank { null },
                        relationship = state.formRelationship.ifBlank { null },
                        phone = state.formPhone.ifBlank { null },
                        email = state.formEmail.ifBlank { null },
                        title = state.formTitle.ifBlank { null },
                        content = state.formContent.ifBlank { null },
                        tag = state.formTag.ifBlank { null },
                        notes = state.formNotes.ifBlank { null },
                        clientTimestamp = Instant.now().toString(),
                        updatedTimestamp = Instant.now().toString()
                    )
                    infoBankDao.insertEntry(entity)

                    _uiState.update {
                        it.copy(isSaving = false, isSaved = true)
                    }
                    loadEntries()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            error = error.message ?: "Failed to save entry"
                        )
                    }
                }
            )
        }
    }

    fun deleteEntry(entryId: UUID) {
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

            val contentData = buildJsonObject {
                put("entryId", JsonPrimitive(entryId.toString()))
            }

            val result = createOperationUseCase(
                bucketId = bucketId,
                entityType = EntityType.InfoBank,
                entityId = entryId.toString(),
                operationType = OperationType.DELETE,
                contentData = contentData
            )

            result.fold(
                onSuccess = {
                    infoBankDao.markDeleted(entryId)
                    _uiState.update { it.copy(isLoading = false) }
                    loadEntries()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Failed to delete entry"
                        )
                    }
                }
            )
        }
    }

    fun resetSavedState() {
        _uiState.update { it.copy(isSaved = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
