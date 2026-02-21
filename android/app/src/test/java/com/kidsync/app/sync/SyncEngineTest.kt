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
import kotlinx.serialization.json.Json
import java.time.Instant

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
    val json = Json { ignoreUnknownKeys = true }

    val bucketId = "bucket-aaaa-bbbb-cccc-dddddddddddd"
    val deviceId = "aaaaaaaa-1111-2222-3333-444444444444"

    beforeEach {
        clearAllMocks()
    }

    fun createSyncUseCase() = SyncOpsUseCase(
        syncRepository = syncRepository,
        cryptoManager = cryptoManager,
        keyManager = keyManager,
        hashChainVerifier = hashChainVerifier,
        conflictResolver = conflictResolver,
        opApplier = opApplier,
        json = json
    )

    test("sync with no new ops succeeds with zero counts") {
        coEvery { syncRepository.getSyncState(bucketId) } returns SyncState(
            bucketId = bucketId,
            lastGlobalSequence = 10,
            lastSyncTimestamp = Instant.now()
        )
        coEvery { syncRepository.pullOps(bucketId, afterSequence = 10) } returns Result.success(emptyList())
        every { hashChainVerifier.verifyChains(emptyList()) } returns Result.success(Unit)
        coEvery { opApplier.getPendingOps(bucketId) } returns emptyList()

        val useCase = createSyncUseCase()
        val result = useCase(bucketId)

        result.isSuccess shouldBe true
        val syncResult = result.getOrThrow()
        syncResult.pulled shouldBe 0
        syncResult.applied shouldBe 0
        syncResult.pushed shouldBe 0
        syncResult.conflictsResolved shouldBe 0
    }

    test("sync from scratch (no sync state) starts from sequence 0") {
        coEvery { syncRepository.getSyncState(bucketId) } returns null
        coEvery { syncRepository.pullOps(bucketId, afterSequence = 0) } returns Result.success(emptyList())
        every { hashChainVerifier.verifyChains(emptyList()) } returns Result.success(Unit)
        coEvery { opApplier.getPendingOps(bucketId) } returns emptyList()

        val useCase = createSyncUseCase()
        val result = useCase(bucketId)

        result.isSuccess shouldBe true
        coVerify { syncRepository.pullOps(bucketId, afterSequence = 0) }
    }

    test("sync fails when pull fails") {
        coEvery { syncRepository.getSyncState(bucketId) } returns null
        coEvery { syncRepository.pullOps(bucketId, afterSequence = 0) } returns
                Result.failure(RuntimeException("Network error"))

        val useCase = createSyncUseCase()
        val result = useCase(bucketId)

        result.isFailure shouldBe true
    }

    test("sync fails when hash chain verification fails") {
        val ops = listOf(
            OpLogEntry(
                globalSequence = 1,
                bucketId = bucketId,
                deviceId = deviceId,
                deviceSequence = 1,
                keyEpoch = 1,
                encryptedPayload = "dGVzdA==",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "invalid-hash",
                serverTimestamp = Instant.now()
            )
        )

        coEvery { syncRepository.getSyncState(bucketId) } returns null
        coEvery { syncRepository.pullOps(bucketId, afterSequence = 0) } returns Result.success(ops)
        every { hashChainVerifier.verifyChains(ops) } returns
                Result.failure(HashChainBreakException("device", 1, "expected", "actual"))

        val useCase = createSyncUseCase()
        val result = useCase(bucketId)

        result.isFailure shouldBe true
        assert(result.exceptionOrNull() is HashChainBreakException)
    }

    test("sync fails when DEK is missing for an epoch") {
        val ops = listOf(
            OpLogEntry(
                globalSequence = 1,
                bucketId = bucketId,
                deviceId = deviceId,
                deviceSequence = 1,
                keyEpoch = 5,
                encryptedPayload = "dGVzdA==",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "some-hash",
                serverTimestamp = Instant.now()
            )
        )

        coEvery { syncRepository.getSyncState(bucketId) } returns null
        coEvery { syncRepository.pullOps(bucketId, afterSequence = 0) } returns Result.success(ops)
        every { hashChainVerifier.verifyChains(ops) } returns Result.success(Unit)
        coEvery { keyManager.getDek(bucketId, 5) } returns null

        val useCase = createSyncUseCase()
        val result = useCase(bucketId)

        result.isFailure shouldBe true
    }

    test("sync with new ops decrypts, applies, and updates sync state") {
        val dek = ByteArray(32) { 1 }
        val ops = listOf(
            OpLogEntry(
                globalSequence = 5,
                bucketId = bucketId,
                deviceId = deviceId,
                deviceSequence = 1,
                keyEpoch = 1,
                encryptedPayload = "encrypted-data",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "hash-1",
                serverTimestamp = Instant.parse("2026-03-28T10:00:00Z")
            )
        )

        coEvery { syncRepository.getSyncState(bucketId) } returns null
        coEvery { syncRepository.pullOps(bucketId, afterSequence = 0) } returns Result.success(ops)
        every { hashChainVerifier.verifyChains(ops) } returns Result.success(Unit)
        coEvery { keyManager.getDek(bucketId, 1) } returns dek
        every { cryptoManager.decryptPayload("encrypted-data", dek, any()) } returns
                """{"deviceSequence":1,"entityType":"CustodySchedule","entityId":"test","operation":"CREATE","clientTimestamp":"2026-03-28T10:00:00Z","protocolVersion":2,"data":{}}"""
        coEvery { opApplier.apply(any(), any()) } returns OpApplier.ApplyResult(conflictResolved = false)
        coEvery { syncRepository.updateSyncState(any()) } just Runs
        coEvery { opApplier.getPendingOps(bucketId) } returns emptyList()

        val useCase = createSyncUseCase()
        val result = useCase(bucketId)

        result.isSuccess shouldBe true
        val syncResult = result.getOrThrow()
        syncResult.pulled shouldBe 1
        syncResult.applied shouldBe 1
        syncResult.conflictsResolved shouldBe 0

        coVerify {
            syncRepository.updateSyncState(match {
                it.lastGlobalSequence == 5L && it.bucketId == bucketId
            })
        }
    }

    test("sync pushes pending local ops after applying pulled ops") {
        val pendingOps = listOf(
            OpLogEntry(
                globalSequence = 0,
                bucketId = bucketId,
                deviceId = deviceId,
                deviceSequence = 1,
                keyEpoch = 1,
                encryptedPayload = "local-encrypted",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "local-hash",
                serverTimestamp = null
            )
        )

        coEvery { syncRepository.getSyncState(bucketId) } returns null
        coEvery { syncRepository.pullOps(bucketId, afterSequence = 0) } returns Result.success(emptyList())
        every { hashChainVerifier.verifyChains(emptyList()) } returns Result.success(Unit)
        coEvery { opApplier.getPendingOps(bucketId) } returns pendingOps
        coEvery { syncRepository.pushOps(bucketId, pendingOps) } returns Result.success(pendingOps)

        val useCase = createSyncUseCase()
        val result = useCase(bucketId)

        result.isSuccess shouldBe true
        result.getOrThrow().pushed shouldBe 1

        coVerify { syncRepository.pushOps(bucketId, pendingOps) }
    }

    test("sync counts conflicts resolved during apply") {
        val dek = ByteArray(32) { 1 }
        val ops = listOf(
            OpLogEntry(
                globalSequence = 1,
                bucketId = bucketId,
                deviceId = deviceId,
                deviceSequence = 1,
                keyEpoch = 1,
                encryptedPayload = "enc1",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "h1",
                serverTimestamp = Instant.now()
            ),
            OpLogEntry(
                globalSequence = 2,
                bucketId = bucketId,
                deviceId = "bbbbbbbb-1111-2222-3333-444444444444",
                deviceSequence = 1,
                keyEpoch = 1,
                encryptedPayload = "enc2",
                devicePrevHash = HashChainVerifier.GENESIS_HASH,
                currentHash = "h2",
                serverTimestamp = Instant.now()
            )
        )

        coEvery { syncRepository.getSyncState(bucketId) } returns null
        coEvery { syncRepository.pullOps(bucketId, afterSequence = 0) } returns Result.success(ops)
        every { hashChainVerifier.verifyChains(ops) } returns Result.success(Unit)
        coEvery { keyManager.getDek(bucketId, 1) } returns dek
        every { cryptoManager.decryptPayload(any(), dek, any()) } returns
                """{"deviceSequence":1,"entityType":"CustodySchedule","entityId":"test","operation":"CREATE","clientTimestamp":"2026-03-28T10:00:00Z","protocolVersion":2,"data":{}}"""
        coEvery { opApplier.apply(match { it.globalSequence == 1L }, any()) } returns
                OpApplier.ApplyResult(conflictResolved = false)
        coEvery { opApplier.apply(match { it.globalSequence == 2L }, any()) } returns
                OpApplier.ApplyResult(conflictResolved = true)
        coEvery { syncRepository.updateSyncState(any()) } just Runs
        coEvery { opApplier.getPendingOps(bucketId) } returns emptyList()

        val useCase = createSyncUseCase()
        val result = useCase(bucketId)

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

        coEvery { syncRepository.getSyncState(bucketId) } returns null
        coEvery { syncRepository.pullOps(bucketId, afterSequence = 0) } returns Result.success(ops)
        every { hashChainVerifier.verifyChains(ops) } returns Result.success(Unit)
        coEvery { keyManager.getDek(bucketId, 1) } returns dek
        every { cryptoManager.decryptPayload(any(), dek, any()) } returns
                """{"deviceSequence":1,"entityType":"CustodySchedule","entityId":"test","operation":"CREATE","clientTimestamp":"2026-03-28T10:00:00Z","protocolVersion":2,"data":{}}"""
        coEvery { opApplier.apply(any(), any()) } answers {
            val op = firstArg<OpLogEntry>()
            appliedSequences.add(op.globalSequence)
            OpApplier.ApplyResult()
        }
        coEvery { syncRepository.updateSyncState(any()) } just Runs
        coEvery { opApplier.getPendingOps(bucketId) } returns emptyList()

        val useCase = createSyncUseCase()
        useCase(bucketId)

        appliedSequences shouldBe listOf(1L, 2L, 3L)
    }
}) {
    companion object {
        private const val bucketId = "bucket-aaaa-bbbb-cccc-dddddddddddd"
        private const val deviceId = "aaaaaaaa-1111-2222-3333-444444444444"

        fun createOp(globalSequence: Long) = OpLogEntry(
            globalSequence = globalSequence,
            bucketId = bucketId,
            deviceId = deviceId,
            deviceSequence = globalSequence,
            keyEpoch = 1,
            encryptedPayload = "enc-$globalSequence",
            devicePrevHash = "prev",
            currentHash = "curr-$globalSequence",
            serverTimestamp = Instant.now()
        )
    }
}
