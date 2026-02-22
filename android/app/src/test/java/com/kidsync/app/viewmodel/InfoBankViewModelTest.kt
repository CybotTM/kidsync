package com.kidsync.app.viewmodel

import com.kidsync.app.data.local.dao.InfoBankDao
import com.kidsync.app.data.local.entity.InfoBankEntryEntity
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.model.InfoBankCategory
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import com.kidsync.app.ui.viewmodel.InfoBankViewModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.util.UUID

/**
 * Tests for InfoBankViewModel covering:
 * - Initial state and loading
 * - loadEntries populates entries and groups by category
 * - loadEntries handles errors
 * - filterByCategory filters entries
 * - onSearchQueryChanged filters by search text
 * - selectEntry loads single entry
 * - clearSelectedEntry clears selection
 * - Form field updates
 * - saveEntry validation (childId required, contact name for emergency, title+content for note)
 * - saveEntry success creates operation and writes to local DB
 * - saveEntry failure sets error
 * - saveEntry not authenticated
 * - deleteEntry success
 * - deleteEntry failure
 * - resetForm clears all form fields
 * - prepareEditForm populates form from entity
 * - resetSavedState and clearError
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InfoBankViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    val infoBankDao = mockk<InfoBankDao>(relaxed = true)
    val createOperationUseCase = mockk<CreateOperationUseCase>()
    val authRepository = mockk<AuthRepository>(relaxed = true)
    val bucketRepository = mockk<BucketRepository>(relaxed = true)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
        coEvery { authRepository.getSession() } returns DeviceSession("device-1", "token", 3600)
        coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-1")
        coEvery { infoBankDao.getAllEntries() } returns emptyList()
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): InfoBankViewModel {
        return InfoBankViewModel(infoBankDao, createOperationUseCase, authRepository, bucketRepository)
    }

    // ── Loading ───────────────────────────────────────────────────────────────

    test("initial load fetches entries from DAO") {
        runTest(testDispatcher) {
            val childId = UUID.randomUUID()
            val entries = listOf(
                InfoBankEntryEntity(
                    entryId = UUID.randomUUID(),
                    childId = childId,
                    category = "MEDICAL",
                    title = "Allergies",
                    content = """{"allergies":"Peanuts"}""",
                    clientTimestamp = "2026-01-01T00:00:00Z"
                )
            )
            coEvery { infoBankDao.getAllEntries() } returns entries

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoading shouldBe false
            state.entries.size shouldBe 1
            state.error shouldBe null
        }
    }

    test("loadEntries handles DAO exception") {
        runTest(testDispatcher) {
            coEvery { infoBankDao.getAllEntries() } throws RuntimeException("Database error")

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoading shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Database error"
        }
    }

    test("loadEntries groups entries by category") {
        runTest(testDispatcher) {
            val childId = UUID.randomUUID()
            val entries = listOf(
                InfoBankEntryEntity(
                    entryId = UUID.randomUUID(), childId = childId,
                    category = "MEDICAL", title = "Dr. Smith"
                ),
                InfoBankEntryEntity(
                    entryId = UUID.randomUUID(), childId = childId,
                    category = "SCHOOL", title = "Main Elementary"
                ),
                InfoBankEntryEntity(
                    entryId = UUID.randomUUID(), childId = childId,
                    category = "MEDICAL", title = "Allergies"
                )
            )
            coEvery { infoBankDao.getAllEntries() } returns entries

            val vm = createViewModel()
            advanceUntilIdle()

            val grouped = vm.uiState.value.filteredEntries
            grouped[InfoBankCategory.MEDICAL]?.size shouldBe 2
            grouped[InfoBankCategory.SCHOOL]?.size shouldBe 1
        }
    }

    // ── Filtering ─────────────────────────────────────────────────────────────

    test("filterByCategory limits to selected category") {
        runTest(testDispatcher) {
            val childId = UUID.randomUUID()
            val entries = listOf(
                InfoBankEntryEntity(
                    entryId = UUID.randomUUID(), childId = childId,
                    category = "MEDICAL", title = "Dr. Smith"
                ),
                InfoBankEntryEntity(
                    entryId = UUID.randomUUID(), childId = childId,
                    category = "SCHOOL", title = "Main Elementary"
                )
            )
            coEvery { infoBankDao.getAllEntries() } returns entries

            val vm = createViewModel()
            advanceUntilIdle()

            vm.filterByCategory(InfoBankCategory.MEDICAL)

            val grouped = vm.uiState.value.filteredEntries
            grouped.keys shouldBe setOf(InfoBankCategory.MEDICAL)
            grouped[InfoBankCategory.MEDICAL]?.size shouldBe 1
        }
    }

    test("filterByCategory with null shows all categories") {
        runTest(testDispatcher) {
            val childId = UUID.randomUUID()
            val entries = listOf(
                InfoBankEntryEntity(
                    entryId = UUID.randomUUID(), childId = childId,
                    category = "MEDICAL", title = "Dr. Smith"
                ),
                InfoBankEntryEntity(
                    entryId = UUID.randomUUID(), childId = childId,
                    category = "SCHOOL", title = "Main Elementary"
                )
            )
            coEvery { infoBankDao.getAllEntries() } returns entries

            val vm = createViewModel()
            advanceUntilIdle()

            vm.filterByCategory(null)

            val grouped = vm.uiState.value.filteredEntries
            grouped.size shouldBe 2
        }
    }

    test("onSearchQueryChanged filters entries by title match") {
        runTest(testDispatcher) {
            val childId = UUID.randomUUID()
            val entries = listOf(
                InfoBankEntryEntity(
                    entryId = UUID.randomUUID(), childId = childId,
                    category = "MEDICAL", title = "Dr. Smith"
                ),
                InfoBankEntryEntity(
                    entryId = UUID.randomUUID(), childId = childId,
                    category = "MEDICAL", title = "Allergies info"
                )
            )
            coEvery { infoBankDao.getAllEntries() } returns entries

            val vm = createViewModel()
            advanceUntilIdle()

            vm.onSearchQueryChanged("smith")

            val grouped = vm.uiState.value.filteredEntries
            val medicalEntries = grouped[InfoBankCategory.MEDICAL] ?: emptyList()
            medicalEntries.size shouldBe 1
            medicalEntries[0].title shouldBe "Dr. Smith"
        }
    }

    // ── Detail ────────────────────────────────────────────────────────────────

    test("selectEntry loads entry from DAO") {
        runTest(testDispatcher) {
            val entryId = UUID.randomUUID()
            val entity = InfoBankEntryEntity(
                entryId = entryId, childId = UUID.randomUUID(),
                category = "NOTE", title = "My Note"
            )
            coEvery { infoBankDao.getEntryById(entryId) } returns entity

            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectEntry(entryId.toString())
            advanceUntilIdle()

            vm.uiState.value.selectedEntry shouldBe entity
        }
    }

    test("selectEntry ignores invalid UUID") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectEntry("not-a-uuid")
            advanceUntilIdle()

            vm.uiState.value.selectedEntry shouldBe null
        }
    }

    test("clearSelectedEntry clears selection") {
        runTest(testDispatcher) {
            val entryId = UUID.randomUUID()
            val entity = InfoBankEntryEntity(
                entryId = entryId, childId = UUID.randomUUID(),
                category = "NOTE", title = "My Note"
            )
            coEvery { infoBankDao.getEntryById(entryId) } returns entity

            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectEntry(entryId.toString())
            advanceUntilIdle()
            vm.uiState.value.selectedEntry shouldNotBe null

            vm.clearSelectedEntry()
            vm.uiState.value.selectedEntry shouldBe null
        }
    }

    // ── Form Field Updates ────────────────────────────────────────────────────

    test("onFormCategoryChanged updates category and clears error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onFormCategoryChanged(InfoBankCategory.SCHOOL)
            vm.uiState.value.formCategory shouldBe InfoBankCategory.SCHOOL
        }
    }

    test("form field setters update state") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onAllergiesChanged("Peanuts")
            vm.uiState.value.formAllergies shouldBe "Peanuts"

            vm.onDoctorNameChanged("Dr. Jones")
            vm.uiState.value.formDoctorName shouldBe "Dr. Jones"

            vm.onSchoolNameChanged("Springfield Elementary")
            vm.uiState.value.formSchoolName shouldBe "Springfield Elementary"

            vm.onContactNameChanged("Jane Doe")
            vm.uiState.value.formContactName shouldBe "Jane Doe"

            vm.onTitleChanged("My Note")
            vm.uiState.value.formTitle shouldBe "My Note"

            vm.onContentChanged("Important information")
            vm.uiState.value.formContent shouldBe "Important information"
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    test("saveEntry sets error when childId is null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Don't set formChildId
            vm.saveEntry()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "Please select a child"
        }
    }

    test("saveEntry sets error when emergency contact name is blank") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onFormChildSelected(UUID.randomUUID())
            vm.onFormCategoryChanged(InfoBankCategory.EMERGENCY_CONTACT)
            // Don't set contact name
            vm.saveEntry()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "Please enter a contact name"
        }
    }

    test("saveEntry sets error when note title is blank") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onFormChildSelected(UUID.randomUUID())
            vm.onFormCategoryChanged(InfoBankCategory.NOTE)
            vm.onContentChanged("Some content")
            // Don't set title
            vm.saveEntry()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "Please enter a title"
        }
    }

    test("saveEntry sets error when note content is blank") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onFormChildSelected(UUID.randomUUID())
            vm.onFormCategoryChanged(InfoBankCategory.NOTE)
            vm.onTitleChanged("My Title")
            // Don't set content
            vm.saveEntry()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "Please enter content"
        }
    }

    test("saveEntry allows medical category with all optional fields blank") {
        runTest(testDispatcher) {
            val entity = mockk<OpLogEntryEntity>()
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.onFormChildSelected(UUID.randomUUID())
            vm.onFormCategoryChanged(InfoBankCategory.MEDICAL)
            // All medical fields are optional; should proceed to save

            vm.saveEntry()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isSaved shouldBe true
            state.error shouldBe null
        }
    }

    // ── Save Entry ────────────────────────────────────────────────────────────

    test("saveEntry success creates operation and saves locally") {
        runTest(testDispatcher) {
            val entity = mockk<OpLogEntryEntity>()
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

            val vm = createViewModel()
            advanceUntilIdle()

            val childId = UUID.randomUUID()
            vm.onFormChildSelected(childId)
            vm.onFormCategoryChanged(InfoBankCategory.NOTE)
            vm.onTitleChanged("Bedtime Routine")
            vm.onContentChanged("Lights out at 8pm")

            vm.saveEntry()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isSaved shouldBe true
            state.isSaving shouldBe false
            state.error shouldBe null

            coVerify { infoBankDao.insertEntry(any()) }
        }
    }

    test("saveEntry failure sets error") {
        runTest(testDispatcher) {
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("Sync failed"))

            val vm = createViewModel()
            advanceUntilIdle()

            vm.onFormChildSelected(UUID.randomUUID())
            vm.onFormCategoryChanged(InfoBankCategory.MEDICAL)

            vm.saveEntry()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isSaved shouldBe false
            state.isSaving shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Sync failed"
        }
    }

    test("saveEntry sets error when not authenticated") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            vm.onFormChildSelected(UUID.randomUUID())
            vm.onFormCategoryChanged(InfoBankCategory.MEDICAL)

            vm.saveEntry()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "Not authenticated"
        }
    }

    test("saveEntry sets error when no bucket available") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            vm.onFormChildSelected(UUID.randomUUID())
            vm.onFormCategoryChanged(InfoBankCategory.MEDICAL)

            vm.saveEntry()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "No bucket available"
        }
    }

    // ── Delete Entry ──────────────────────────────────────────────────────────

    test("deleteEntry success marks as deleted and reloads") {
        runTest(testDispatcher) {
            val entity = mockk<OpLogEntryEntity>()
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

            val vm = createViewModel()
            advanceUntilIdle()

            val entryId = UUID.randomUUID()
            vm.deleteEntry(entryId)
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoading shouldBe false
            state.error shouldBe null
            coVerify { infoBankDao.markDeleted(entryId) }
        }
    }

    test("deleteEntry failure sets error") {
        runTest(testDispatcher) {
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("Delete failed"))

            val vm = createViewModel()
            advanceUntilIdle()

            vm.deleteEntry(UUID.randomUUID())
            advanceUntilIdle()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "Delete failed"
        }
    }

    // ── Reset Form ────────────────────────────────────────────────────────────

    test("resetForm clears all form fields") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onFormChildSelected(UUID.randomUUID())
            vm.onFormCategoryChanged(InfoBankCategory.SCHOOL)
            vm.onSchoolNameChanged("Test School")
            vm.onTitleChanged("A title")

            vm.resetForm()

            val state = vm.uiState.value
            state.isEditing shouldBe false
            state.editingEntryId shouldBe null
            state.formCategory shouldBe InfoBankCategory.MEDICAL
            state.formChildId shouldBe null
            state.formSchoolName shouldBe ""
            state.formTitle shouldBe ""
            state.isSaved shouldBe false
            state.error shouldBe null
        }
    }

    // ── prepareEditForm ───────────────────────────────────────────────────────

    test("prepareEditForm populates form from entity") {
        runTest(testDispatcher) {
            val entryId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val entity = InfoBankEntryEntity(
                entryId = entryId,
                childId = childId,
                category = "EMERGENCY_CONTACT",
                title = "Grandma",
                content = """{"contactName":"Grandma","relationship":"Grandmother","phone":"555-1234"}""",
                notes = "Primary emergency contact"
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.prepareEditForm(entity)

            val state = vm.uiState.value
            state.isEditing shouldBe true
            state.editingEntryId shouldBe entryId
            state.formCategory shouldBe InfoBankCategory.EMERGENCY_CONTACT
            state.formChildId shouldBe childId
            state.formContactName shouldBe "Grandma"
            state.formRelationship shouldBe "Grandmother"
            state.formPhone shouldBe "555-1234"
            state.formNotes shouldBe "Primary emergency contact"
        }
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    test("resetSavedState clears isSaved") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Manually trigger a save to set isSaved
            val entity = mockk<OpLogEntryEntity>()
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)
            vm.onFormChildSelected(UUID.randomUUID())
            vm.onFormCategoryChanged(InfoBankCategory.MEDICAL)
            vm.saveEntry()
            advanceUntilIdle()
            vm.uiState.value.isSaved shouldBe true

            vm.resetSavedState()
            vm.uiState.value.isSaved shouldBe false
        }
    }

    test("clearError sets error to null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Trigger error
            vm.saveEntry()
            advanceUntilIdle()
            vm.uiState.value.error shouldNotBe null

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }
})
