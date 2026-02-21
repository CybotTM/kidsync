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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    private val jsonParser = Json { ignoreUnknownKeys = true }

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
            entry.title, entry.content, entry.notes
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

        // Parse content JSON to extract category-specific fields
        val contentData = parseContentJson(entry.content)

        _uiState.update {
            it.copy(
                isEditing = true,
                editingEntryId = entry.entryId,
                formCategory = category,
                formChildId = entry.childId,
                formAllergies = contentData["allergies"] ?: "",
                formMedicationName = contentData["medicationName"] ?: "",
                formMedicationDosage = contentData["medicationDosage"] ?: "",
                formMedicationSchedule = contentData["medicationSchedule"] ?: "",
                formDoctorName = contentData["doctorName"] ?: "",
                formDoctorPhone = contentData["doctorPhone"] ?: "",
                formInsuranceInfo = contentData["insuranceInfo"] ?: "",
                formBloodType = contentData["bloodType"] ?: "",
                formSchoolName = contentData["schoolName"] ?: "",
                formTeacherNames = contentData["teacherNames"] ?: "",
                formGradeClass = contentData["gradeClass"] ?: "",
                formSchoolPhone = contentData["schoolPhone"] ?: "",
                formScheduleNotes = contentData["scheduleNotes"] ?: "",
                formContactName = contentData["contactName"] ?: "",
                formRelationship = contentData["relationship"] ?: "",
                formPhone = contentData["phone"] ?: "",
                formEmail = contentData["email"] ?: "",
                formTitle = entry.title ?: contentData["title"] ?: "",
                formContent = contentData["content"] ?: "",
                formTag = contentData["tag"] ?: "",
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

            // Derive a title from category-specific fields
            val derivedTitle = deriveTitle(state)

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
                entityType = EntityType.InfoBankEntry,
                entityId = entryId.toString(),
                operationType = operationType,
                contentData = contentData
            )

            result.fold(
                onSuccess = {
                    // Also write locally immediately using the generic schema.
                    // Store the full contentData as JSON in the content field.
                    val contentJson = jsonParser.encodeToString(
                        JsonObject.serializer(), contentData
                    )
                    val entity = InfoBankEntryEntity(
                        entryId = entryId,
                        childId = state.formChildId,
                        category = state.formCategory.name,
                        title = derivedTitle,
                        content = contentJson,
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
                entityType = EntityType.InfoBankEntry,
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

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Parse the JSON content field into a string map for populating form fields.
     */
    private fun parseContentJson(content: String?): Map<String, String> {
        if (content.isNullOrBlank()) return emptyMap()
        return try {
            val obj = jsonParser.parseToJsonElement(content).jsonObject
            obj.mapValues { (_, v) -> v.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /**
     * Derive a display title from the form state based on category.
     */
    private fun deriveTitle(state: InfoBankUiState): String {
        return when (state.formCategory) {
            InfoBankCategory.MEDICAL -> listOfNotNull(
                state.formDoctorName.ifBlank { null }?.let { "Dr. $it" },
                state.formAllergies.ifBlank { null }?.let { "Allergies: $it" },
                state.formMedicationName.ifBlank { null }
            ).firstOrNull() ?: "Medical Info"

            InfoBankCategory.SCHOOL ->
                state.formSchoolName.ifBlank { null } ?: "School Info"

            InfoBankCategory.EMERGENCY_CONTACT ->
                state.formContactName.ifBlank { null } ?: "Emergency Contact"

            InfoBankCategory.NOTE ->
                state.formTitle.ifBlank { null } ?: "Note"
        }
    }
}
