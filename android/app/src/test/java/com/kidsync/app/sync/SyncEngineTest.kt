package com.kidsync.app.sync

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.*
import com.kidsync.app.domain.repository.SyncRepository
import com.kidsync.app.domain.usecase.custody.ConflictResolver
import com.kidsync.app.domain.usecase.sync.*
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.time.Instant
import java.util.UUID

/**
 * Tests for the SyncOpsUseCase (sync engine).
 *
 * Verifies:
 * - Full sync pipeline: pull -> verify -> decrypt -> apply -> push
 * - Empty pull handling
 * - Hash chain verification failure handling
 * - Missing DEK handling
 * - Sync state updates
 */
class SyncEngineTest : FunSpec({

    val syncRepository = mockk<SyncRepository>()
    val cryptoManager = mockk<CryptoManager>()
    val keyManager = mockk<KeyManager>()
    val hashChainVerifier = mockk<HashChainVerifier>()
    val conflictResolver = mockk<ConflictResolver>()
    val opApplier = mockk<OpApplier>()

    val familyId = UUID.fromString("fam00001-aaaa-bbbb-cccc-dddddddddddd")
    val deviceId = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444")

    beforeEach {
        clearAllMocks()
    }

    fun createSyncUseCase() = SyncOpsUseCase(
        syncRepository = syncRepository,
        cryptoManager = cryptoManager,
        keyManager = keyManager,
        hashChainVerifier = hashChainVerifier,
        conflictResolver = conflictResolver,
        opApplier = opApplier
    )

    test("sync with no new ops succeeds with zero counts") {
        coEvery { syncRepository.getSyncState(familyId) } returns SyncState(
            familyId = familyId,
            lastGlobalSequence = 10,
            lastSyncTimestamp = Instant.now()
        )
        coEvery { syncRepository.pullOps(familyId, afterSequence = 10) } returns Result.success(emptyList())
        coEvery { opApplier.getPendingOps(familyId) } returns emptyList()

        val useCase = createSyncUseCase()
        val result = useCase(familyId)

        result.isSuccess shouldBe true
        val syncResult = result.getOrThrow()
        syncResult.pulled shouldBe 0
        syncResult.applied shouldBe 0
        syncResult.pushed shouldBe 0
        syncResult.conflictsResolved shouldBe 0
    }

    test("sync from scratch (no sync state) starts from sequence 0") {
        coEvery { syncRepository.getSyncState(familyId) } returns null
        coEvery { syncRepository.pullOps(familyId, afterSequence = 0) } returns Result.success(emptyList())
        coEvery { opApplier.getPendingOps(familyId) } returns emptyList()

        val useCase = createSyncUseCase()
        val result = useCase(familyId)

        result.isSuccess shouldBe true
        coVerify { syncRepository.pullOps(familyId, afterSequence = 0) }
    }

    test("sync fails when pull fails") {
        coEvery { syncRepository.getSyncState(familyId) } returns null
        coEvery { syncRepository.pullOps(familyId, afterSequence = 0) } returns
                Result.failure(RuntimeException("Network error"))

        val useCase = createSyncUseCase()
        val result = useCase(familyId)

        result.isFailure shouldBe true
    }

    test("sync fails when hash chain verification fails") {
        val ops = listOf(
            OpLogEntry(
                globalSequence = 1,
                familyId = familyId,
                deviceId = deviceId,
                deviceSequence = 1,
                entityType = EntityType.CustodySchedule,
                entityId = UUID.randomUUID(),
                operation = OperationType.CREATE,
                keyEpoch = 1,
                encryptedPayload = "dGVzdA==",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "invalid-hash",
                clientTimestamp = Instant.now()
            )
        )

        coEvery { syncRepository.getSyncState(familyId) } returns null
        coEvery { syncRepository.pullOps(familyId, afterSequence = 0) } returns Result.success(ops)
        every { hashChainVerifier.verifyChains(ops) } returns
                Result.failure(HashChainBreakException("device", 1, "expected", "actual"))

        val useCase = createSyncUseCase()
        val result = useCase(familyId)

        result.isFailure shouldBe true
        result.exceptionOrNull() shouldBe instanceOf(HashChainBreakException::class)
    }

    test("sync fails when DEK is missing for an epoch") {
        val ops = listOf(
            OpLogEntry(
                globalSequence = 1,
                familyId = familyId,
                deviceId = deviceId,
                deviceSequence = 1,
                entityType = EntityType.CustodySchedule,
                entityId = UUID.randomUUID(),
                operation = OperationType.CREATE,
                keyEpoch = 5,
                encryptedPayload = "dGVzdA==",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "some-hash",
                clientTimestamp = Instant.now()
            )
        )

        coEvery { syncRepository.getSyncState(familyId) } returns null
        coEvery { syncRepository.pullOps(familyId, afterSequence = 0) } returns Result.success(ops)
        every { hashChainVerifier.verifyChains(ops) } returns Result.success(Unit)
        coEvery { keyManager.getDek(familyId, 5) } returns null

        val useCase = createSyncUseCase()
        val result = useCase(familyId)

        result.isFailure shouldBe true
    }

    test("sync with new ops decrypts, applies, and updates sync state") {
        val dek = ByteArray(32) { 1 }
        val entityId = UUID.randomUUID()
        val ops = listOf(
            OpLogEntry(
                globalSequence = 5,
                familyId = familyId,
                deviceId = deviceId,
                deviceSequence = 1,
                entityType = EntityType.CustodySchedule,
                entityId = entityId,
                operation = OperationType.CREATE,
                keyEpoch = 1,
                encryptedPayload = "encrypted-data",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "hash-1",
                clientTimestamp = Instant.parse("2026-03-28T10:00:00Z")
            )
        )

        coEvery { syncRepository.getSyncState(familyId) } returns null
        coEvery { syncRepository.pullOps(familyId, afterSequence = 0) } returns Result.success(ops)
        every { hashChainVerifier.verifyChains(ops) } returns Result.success(Unit)
        coEvery { keyManager.getDek(familyId, 1) } returns dek
        every { cryptoManager.decryptPayload("encrypted-data", dek, deviceId.toString()) } returns
                """{"payloadType":"SetCustodySchedule"}"""
        coEvery { opApplier.apply(any(), any()) } returns OpApplier.ApplyResult(conflictResolved = false)
        coEvery { syncRepository.updateSyncState(any()) } just Runs
        coEvery { opApplier.getPendingOps(familyId) } returns emptyList()

        val useCase = createSyncUseCase()
        val result = useCase(familyId)

        result.isSuccess shouldBe true
        val syncResult = result.getOrThrow()
        syncResult.pulled shouldBe 1
        syncResult.applied shouldBe 1
        syncResult.conflictsResolved shouldBe 0

        coVerify {
            syncRepository.updateSyncState(match {
                it.lastGlobalSequence == 5L && it.familyId == familyId
            })
        }
    }

    test("sync pushes pending local ops after applying pulled ops") {
        val pendingOps = listOf(
            OpLogEntry(
                globalSequence = 0,
                familyId = familyId,
                deviceId = deviceId,
                deviceSequence = 1,
                entityType = EntityType.Expense,
                entityId = UUID.randomUUID(),
                operation = OperationType.CREATE,
                keyEpoch = 1,
                encryptedPayload = "local-encrypted",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "local-hash",
                clientTimestamp = Instant.now()
            )
        )

        coEvery { syncRepository.getSyncState(familyId) } returns null
        coEvery { syncRepository.pullOps(familyId, afterSequence = 0) } returns Result.success(emptyList())
        coEvery { opApplier.getPendingOps(familyId) } returns pendingOps
        coEvery { syncRepository.pushOps(familyId, pendingOps) } returns Result.success(pendingOps)

        val useCase = createSyncUseCase()
        val result = useCase(familyId)

        result.isSuccess shouldBe true
        result.getOrThrow().pushed shouldBe 1

        coVerify { syncRepository.pushOps(familyId, pendingOps) }
    }

    test("sync counts conflicts resolved during apply") {
        val dek = ByteArray(32) { 1 }
        val ops = listOf(
            OpLogEntry(
                globalSequence = 1,
                familyId = familyId,
                deviceId = deviceId,
                deviceSequence = 1,
                entityType = EntityType.CustodySchedule,
                entityId = UUID.randomUUID(),
                operation = OperationType.CREATE,
                keyEpoch = 1,
                encryptedPayload = "enc1",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "h1",
                clientTimestamp = Instant.now()
            ),
            OpLogEntry(
                globalSequence = 2,
                familyId = familyId,
                deviceId = UUID.fromString("bbbbbbbb-1111-2222-3333-444444444444"),
                deviceSequence = 1,
                entityType = EntityType.CustodySchedule,
                entityId = UUID.randomUUID(),
                operation = OperationType.CREATE,
                keyEpoch = 1,
                encryptedPayload = "enc2",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "h2",
                clientTimestamp = Instant.now()
            )
        )

        coEvery { syncRepository.getSyncState(familyId) } returns null
        coEvery { syncRepository.pullOps(familyId, afterSequence = 0) } returns Result.success(ops)
        every { hashChainVerifier.verifyChains(ops) } returns Result.success(Unit)
        coEvery { keyManager.getDek(familyId, 1) } returns dek
        every { cryptoManager.decryptPayload(any(), dek, any()) } returns "{}"
        coEvery { opApplier.apply(match { it.globalSequence == 1L }, any()) } returns
                OpApplier.ApplyResult(conflictResolved = false)
        coEvery { opApplier.apply(match { it.globalSequence == 2L }, any()) } returns
                OpApplier.ApplyResult(conflictResolved = true)
        coEvery { syncRepository.updateSyncState(any()) } just Runs
        coEvery { opApplier.getPendingOps(familyId) } returns emptyList()

        val useCase = createSyncUseCase()
        val result = useCase(familyId)

        result.isSuccess shouldBe true
        result.getOrThrow().conflictsResolved shouldBe 1
    }

    test("ops are applied in globalSequence order") {
        val dek = ByteArray(32) { 1 }
        val appliedSequences = mutableListOf<Long>()

        val ops = listOf(
            createOp(globalSequence = 3),
            createOp(globalSequence = 1),
            createOp(globalSequence = 2)
        )

        coEvery { syncRepository.getSyncState(familyId) } returns null
        coEvery { syncRepository.pullOps(familyId, afterSequence = 0) } returns Result.success(ops)
        every { hashChainVerifier.verifyChains(ops) } returns Result.success(Unit)
        coEvery { keyManager.getDek(familyId, 1) } returns dek
        every { cryptoManager.decryptPayload(any(), dek, any()) } returns "{}"
        coEvery { opApplier.apply(any(), any()) } answers {
            val op = firstArg<OpLogEntry>()
            appliedSequences.add(op.globalSequence)
            OpApplier.ApplyResult()
        }
        coEvery { syncRepository.updateSyncState(any()) } just Runs
        coEvery { opApplier.getPendingOps(familyId) } returns emptyList()

        val useCase = createSyncUseCase()
        useCase(familyId)

        appliedSequences shouldBe listOf(1L, 2L, 3L)
    }
}) {
    companion object {
        private val familyId = UUID.fromString("fam00001-aaaa-bbbb-cccc-dddddddddddd")
        private val deviceId = UUID.fromString("aaaaaaaa-1111-2222-3333-444444444444")

        fun createOp(globalSequence: Long) = OpLogEntry(
            globalSequence = globalSequence,
            familyId = familyId,
            deviceId = deviceId,
            deviceSequence = globalSequence,
            entityType = EntityType.CustodySchedule,
            entityId = UUID.randomUUID(),
            operation = OperationType.CREATE,
            keyEpoch = 1,
            encryptedPayload = "enc-$globalSequence",
            devicePrevHash = "prev",
            currentHash = "curr-$globalSequence",
            clientTimestamp = Instant.now()
        )
    }
}

private inline fun <reified T> instanceOf(clazz: Class<T>): T? = null
