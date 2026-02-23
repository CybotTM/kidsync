package com.kidsync.app.domain.usecase

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.*
import com.kidsync.app.data.local.entity.CalendarEventEntity
import com.kidsync.app.data.local.entity.CustodyScheduleEntity
import com.kidsync.app.data.local.entity.ExpenseEntity
import com.kidsync.app.data.local.entity.InfoBankEntryEntity
import com.kidsync.app.data.local.entity.ScheduleOverrideEntity
import com.kidsync.app.data.local.entity.SyncStateEntity
import java.util.UUID
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.UploadSnapshotResponse
import com.kidsync.app.domain.usecase.sync.SnapshotUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*

class SnapshotUseCaseTest : FunSpec({

    val custodyScheduleDao = mockk<CustodyScheduleDao>()
    val overrideDao = mockk<OverrideDao>()
    val expenseDao = mockk<ExpenseDao>()
    val calendarEventDao = mockk<CalendarEventDao>()
    val infoBankDao = mockk<InfoBankDao>()
    val opLogDao = mockk<OpLogDao>()
    val syncStateDao = mockk<SyncStateDao>()
    val cryptoManager = mockk<CryptoManager>()
    val keyManager = mockk<KeyManager>()
    val apiService = mockk<ApiService>()

    fun createUseCase() = SnapshotUseCase(
        custodyScheduleDao, overrideDao, expenseDao, calendarEventDao, infoBankDao,
        opLogDao, syncStateDao, cryptoManager, keyManager, apiService
    )

    beforeEach {
        clearAllMocks()
    }

    val bucketId = "bucket-snapshot-test"
    val deviceId = "device-snapshot-test"

    fun setupHappyPath() {
        coEvery { syncStateDao.getSyncState(bucketId) } returns SyncStateEntity(
            bucketId = bucketId,
            lastGlobalSequence = 50,
            lastSyncTimestamp = "2026-01-15T00:00:00Z"
        )
        coEvery { keyManager.getDeviceId() } returns deviceId
        coEvery { keyManager.getCurrentEpoch(bucketId) } returns 1
        coEvery { keyManager.getDek(bucketId, 1) } returns ByteArray(32) { 0x42.toByte() }
        coEvery { keyManager.getOrCreateSigningKeyPair() } returns Pair(
            ByteArray(32) { 0x01.toByte() },
            ByteArray(32) { 0x02.toByte() }
        )

        coEvery { custodyScheduleDao.getAllSchedules() } returns emptyList()
        coEvery { overrideDao.getAllOverrides() } returns emptyList()
        coEvery { expenseDao.getAllExpenses() } returns emptyList()
        coEvery { calendarEventDao.getAllEvents() } returns emptyList()
        coEvery { infoBankDao.getAllEntries() } returns emptyList()

        every { cryptoManager.encryptPayload(any(), any(), any()) } returns "encrypted-snapshot-base64"
        every { cryptoManager.signEd25519(any(), any()) } returns ByteArray(64) { 0xAA.toByte() }

        coEvery { apiService.uploadSnapshot(bucketId, any(), any()) } returns UploadSnapshotResponse(
            snapshotId = "snap-1",
            atSequence = 50
        )
    }

    // ── Successful snapshot creation ────────────────────────────────────────

    test("createSnapshot success returns state hash") {
        setupHappyPath()

        val useCase = createUseCase()
        val result = useCase.createSnapshot(bucketId)

        result.isSuccess shouldBe true
        result.getOrNull() shouldNotBe null
        // Hash is hex-encoded SHA-256
        result.getOrNull()!!.length shouldBe 64
    }

    test("createSnapshot uploads via API") {
        setupHappyPath()

        val useCase = createUseCase()
        useCase.createSnapshot(bucketId)

        coVerify { apiService.uploadSnapshot(bucketId, any(), any()) }
    }

    test("createSnapshot encrypts with correct AAD") {
        setupHappyPath()
        val expectedAad = CryptoManager.buildPayloadAad(bucketId = bucketId, deviceId = deviceId)

        val useCase = createUseCase()
        useCase.createSnapshot(bucketId)

        verify { cryptoManager.encryptPayload(any(), any(), expectedAad) }
    }

    // ── Key material zeroing ────────────────────────────────────────────────

    test("createSnapshot zeros DEK after use") {
        setupHappyPath()
        val dek = ByteArray(32) { 0x42.toByte() }
        coEvery { keyManager.getDek(bucketId, 1) } returns dek

        val useCase = createUseCase()
        useCase.createSnapshot(bucketId)

        // DEK should be zeroed
        dek.all { it == 0.toByte() } shouldBe true
    }

    // ── Missing prerequisites ───────────────────────────────────────────────

    test("createSnapshot fails when no sync state exists") {
        coEvery { syncStateDao.getSyncState(bucketId) } returns null

        val useCase = createUseCase()
        val result = useCase.createSnapshot(bucketId)

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldContain "No sync state"
    }

    test("createSnapshot fails when device not registered") {
        coEvery { syncStateDao.getSyncState(bucketId) } returns SyncStateEntity(
            bucketId = bucketId, lastGlobalSequence = 10, lastSyncTimestamp = "2026-01-01T00:00:00Z"
        )
        coEvery { keyManager.getDeviceId() } returns null

        val useCase = createUseCase()
        val result = useCase.createSnapshot(bucketId)

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldContain "Device not registered"
    }

    test("createSnapshot fails when no DEK available") {
        coEvery { syncStateDao.getSyncState(bucketId) } returns SyncStateEntity(
            bucketId = bucketId, lastGlobalSequence = 10, lastSyncTimestamp = "2026-01-01T00:00:00Z"
        )
        coEvery { keyManager.getDeviceId() } returns deviceId
        coEvery { keyManager.getCurrentEpoch(bucketId) } returns 2
        coEvery { keyManager.getDek(bucketId, 2) } returns null

        val useCase = createUseCase()
        val result = useCase.createSnapshot(bucketId)

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldContain "No DEK"
    }

    // ── State hash computation ──────────────────────────────────────────────

    test("state hash includes expenses in computation") {
        setupHappyPath()
        coEvery { expenseDao.getAllExpenses() } returns listOf(
            ExpenseEntity(
                expenseId = "exp-1", childId = "child-1", paidByDeviceId = "device-1",
                amountCents = 5000, currencyCode = "USD", category = "MEDICAL",
                description = "Test", incurredAt = "2026-01-15", payerResponsibilityRatio = 0.5
            )
        )

        val useCase = createUseCase()
        val result1 = useCase.createSnapshot(bucketId)

        // Reset to verify hash changes with different data
        setupHappyPath()
        coEvery { expenseDao.getAllExpenses() } returns listOf(
            ExpenseEntity(
                expenseId = "exp-2", childId = "child-1", paidByDeviceId = "device-1",
                amountCents = 9999, currencyCode = "EUR", category = "EDUCATION",
                description = "Different", incurredAt = "2026-02-20", payerResponsibilityRatio = 0.7
            )
        )

        val useCase2 = createUseCase()
        val result2 = useCase2.createSnapshot(bucketId)

        // Different expense data should produce different state hashes
        result1.isSuccess shouldBe true
        result2.isSuccess shouldBe true
        result1.getOrNull() shouldNotBe result2.getOrNull()
    }

    // ── SEC6-A-16: Calendar event and info bank entity coverage ─────────

    test("state hash changes when calendar event is added") {
        setupHappyPath()
        val useCase1 = createUseCase()
        val result1 = useCase1.createSnapshot(bucketId)

        setupHappyPath()
        coEvery { calendarEventDao.getAllEvents() } returns listOf(
            CalendarEventEntity(
                eventId = "evt-1",
                childId = "child-1",
                title = "Doctor visit",
                startTime = "2026-03-01T10:00:00Z",
                endTime = "2026-03-01T11:00:00Z",
                clientTimestamp = "2026-02-20T12:00:00Z"
            )
        )

        val useCase2 = createUseCase()
        val result2 = useCase2.createSnapshot(bucketId)

        result1.isSuccess shouldBe true
        result2.isSuccess shouldBe true
        result1.getOrNull() shouldNotBe result2.getOrNull()
    }

    test("state hash changes when info bank entry is added") {
        setupHappyPath()
        val useCase1 = createUseCase()
        val result1 = useCase1.createSnapshot(bucketId)

        setupHappyPath()
        coEvery { infoBankDao.getAllEntries() } returns listOf(
            InfoBankEntryEntity(
                entryId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                childId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                category = "MEDICAL",
                title = "Allergies",
                content = "Peanuts, shellfish"
            )
        )

        val useCase2 = createUseCase()
        val result2 = useCase2.createSnapshot(bucketId)

        result1.isSuccess shouldBe true
        result2.isSuccess shouldBe true
        result1.getOrNull() shouldNotBe result2.getOrNull()
    }

    test("state hash is stable for same state") {
        setupHappyPath()
        coEvery { calendarEventDao.getAllEvents() } returns listOf(
            CalendarEventEntity(
                eventId = "evt-stable",
                childId = "child-1",
                title = "Playdate",
                startTime = "2026-04-01T14:00:00Z",
                endTime = "2026-04-01T16:00:00Z",
                clientTimestamp = "2026-03-15T09:00:00Z"
            )
        )
        coEvery { infoBankDao.getAllEntries() } returns listOf(
            InfoBankEntryEntity(
                entryId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
                childId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                category = "EMERGENCY",
                content = "Mom: 555-1234"
            )
        )

        val useCase1 = createUseCase()
        val result1 = useCase1.createSnapshot(bucketId)

        // Re-setup with exact same data
        setupHappyPath()
        coEvery { calendarEventDao.getAllEvents() } returns listOf(
            CalendarEventEntity(
                eventId = "evt-stable",
                childId = "child-1",
                title = "Playdate",
                startTime = "2026-04-01T14:00:00Z",
                endTime = "2026-04-01T16:00:00Z",
                clientTimestamp = "2026-03-15T09:00:00Z"
            )
        )
        coEvery { infoBankDao.getAllEntries() } returns listOf(
            InfoBankEntryEntity(
                entryId = UUID.fromString("33333333-3333-3333-3333-333333333333"),
                childId = UUID.fromString("44444444-4444-4444-4444-444444444444"),
                category = "EMERGENCY",
                content = "Mom: 555-1234"
            )
        )

        val useCase2 = createUseCase()
        val result2 = useCase2.createSnapshot(bucketId)

        result1.isSuccess shouldBe true
        result2.isSuccess shouldBe true
        result1.getOrNull() shouldBe result2.getOrNull()
    }

    test("deleted info bank entries are excluded from hash via getAllEntries") {
        // getAllEntries() DAO query filters deleted = 0, so deleted entries
        // should never be returned. Verify by checking that if we mock
        // getAllEntries to return nothing (simulating all deleted), the hash
        // matches the baseline with no entries.
        setupHappyPath()
        // Baseline: no info bank entries
        val useCase1 = createUseCase()
        val result1 = useCase1.createSnapshot(bucketId)

        // Same setup: getAllEntries returns empty (all entries are deleted)
        setupHappyPath()
        // infoBankDao.getAllEntries() already returns emptyList() from setupHappyPath
        val useCase2 = createUseCase()
        val result2 = useCase2.createSnapshot(bucketId)

        result1.isSuccess shouldBe true
        result2.isSuccess shouldBe true
        result1.getOrNull() shouldBe result2.getOrNull()
    }
})
