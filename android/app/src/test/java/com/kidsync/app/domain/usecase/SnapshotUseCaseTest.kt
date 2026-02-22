package com.kidsync.app.domain.usecase

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.*
import com.kidsync.app.data.local.entity.CustodyScheduleEntity
import com.kidsync.app.data.local.entity.ExpenseEntity
import com.kidsync.app.data.local.entity.ScheduleOverrideEntity
import com.kidsync.app.data.local.entity.SyncStateEntity
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
    val opLogDao = mockk<OpLogDao>()
    val syncStateDao = mockk<SyncStateDao>()
    val cryptoManager = mockk<CryptoManager>()
    val keyManager = mockk<KeyManager>()
    val apiService = mockk<ApiService>()

    fun createUseCase() = SnapshotUseCase(
        custodyScheduleDao, overrideDao, expenseDao, opLogDao, syncStateDao,
        cryptoManager, keyManager, apiService
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
})
