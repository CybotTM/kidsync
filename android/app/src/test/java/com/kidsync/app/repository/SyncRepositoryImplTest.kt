package com.kidsync.app.repository

import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.dao.SyncStateDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.data.local.entity.SyncStateEntity
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.AcceptedOp
import com.kidsync.app.data.remote.dto.OpsBatchResponse
import com.kidsync.app.data.remote.dto.PullOpsResponse
import com.kidsync.app.data.remote.dto.OpResponse
import com.kidsync.app.data.remote.dto.CheckpointResponse
import com.kidsync.app.data.repository.SyncRepositoryImpl
import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.model.SyncState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import java.time.Instant

class SyncRepositoryImplTest : FunSpec({

    val apiService = mockk<ApiService>()
    val opLogDao = mockk<OpLogDao>(relaxed = true)
    val syncStateDao = mockk<SyncStateDao>(relaxed = true)

    fun createRepo() = SyncRepositoryImpl(apiService, opLogDao, syncStateDao)

    beforeEach {
        clearAllMocks()
    }

    val bucketId = "bucket-test-sync"
    val deviceId = "device-test-sync"

    fun makeOp(seq: Long): OpLogEntry = OpLogEntry(
        globalSequence = 0,
        bucketId = bucketId,
        deviceId = deviceId,
        deviceSequence = seq,
        keyEpoch = 1,
        encryptedPayload = "dGVzdA==",
        devicePrevHash = "0".repeat(64),
        currentHash = "a".repeat(64),
        serverTimestamp = null
    )

    // ── Push Ops ─────────────────────────────────────────────────────────────

    test("pushOps success returns updated ops with server sequences") {
        val ops = listOf(makeOp(1))
        coEvery { apiService.uploadOps(bucketId, any()) } returns OpsBatchResponse(
            accepted = listOf(
                AcceptedOp(index = 0, globalSequence = 100, serverTimestamp = "2026-01-01T00:00:00Z")
            ),
            latestSequence = 100
        )
        coEvery { opLogDao.getPendingOps(bucketId) } returns listOf(
            OpLogEntryEntity(
                id = 1, globalSequence = 0, bucketId = bucketId, deviceId = deviceId,
                deviceSequence = 1, keyEpoch = 1, encryptedPayload = "dGVzdA==",
                devicePrevHash = "0".repeat(64), currentHash = "a".repeat(64),
                isPending = true
            )
        )

        val repo = createRepo()
        val result = repo.pushOps(bucketId, ops)

        result.isSuccess shouldBe true
        result.getOrNull()!![0].globalSequence shouldBe 100
    }

    test("pushOps failure returns failure result") {
        coEvery { apiService.uploadOps(any(), any()) } throws RuntimeException("Server error")

        val repo = createRepo()
        val result = repo.pushOps(bucketId, listOf(makeOp(1)))

        result.isFailure shouldBe true
    }

    test("pushOps marks local ops as synced") {
        val entity = OpLogEntryEntity(
            id = 5, globalSequence = 0, bucketId = bucketId, deviceId = deviceId,
            deviceSequence = 1, keyEpoch = 1, encryptedPayload = "dGVzdA==",
            devicePrevHash = "0".repeat(64), currentHash = "a".repeat(64),
            isPending = true
        )
        coEvery { opLogDao.getPendingOps(bucketId) } returns listOf(entity)
        coEvery { apiService.uploadOps(bucketId, any()) } returns OpsBatchResponse(
            accepted = listOf(
                AcceptedOp(index = 0, globalSequence = 200, serverTimestamp = "2026-01-01T00:00:00Z")
            ),
            latestSequence = 200
        )

        val repo = createRepo()
        repo.pushOps(bucketId, listOf(makeOp(1)))

        coVerify { opLogDao.markAsSynced(5, 200, "2026-01-01T00:00:00Z") }
    }

    // ── Pull Ops ─────────────────────────────────────────────────────────────

    test("pullOps success returns ops and inserts into DB") {
        coEvery { apiService.pullOps(bucketId, since = 0, limit = 100) } returns PullOpsResponse(
            ops = listOf(
                OpResponse(
                    globalSequence = 10, deviceId = deviceId, keyEpoch = 1,
                    encryptedPayload = "dGVzdA==", prevHash = "0".repeat(64),
                    currentHash = "b".repeat(64), serverTimestamp = "2026-01-01T00:00:00Z"
                )
            ),
            hasMore = false,
            latestSequence = 10
        )

        val repo = createRepo()
        val result = repo.pullOps(bucketId, afterSequence = 0)

        result.isSuccess shouldBe true
        result.getOrNull()!!.size shouldBe 1
        result.getOrNull()!![0].globalSequence shouldBe 10
        coVerify { opLogDao.insertOpLogEntries(any()) }
    }

    test("pullOps with no new ops returns empty list") {
        coEvery { apiService.pullOps(bucketId, since = 100, limit = 100) } returns PullOpsResponse(
            ops = emptyList(),
            hasMore = false,
            latestSequence = 100
        )

        val repo = createRepo()
        val result = repo.pullOps(bucketId, afterSequence = 100)

        result.isSuccess shouldBe true
        result.getOrNull()!!.size shouldBe 0
    }

    test("pullOps paginates when hasMore is true") {
        coEvery { apiService.pullOps(bucketId, since = 0, limit = 100) } returns PullOpsResponse(
            ops = listOf(
                OpResponse(
                    globalSequence = 5, deviceId = deviceId, keyEpoch = 1,
                    encryptedPayload = "dGVzdA==", prevHash = "0".repeat(64),
                    currentHash = "c".repeat(64), serverTimestamp = "2026-01-01T00:00:00Z"
                )
            ),
            hasMore = true,
            latestSequence = 10
        )
        coEvery { apiService.pullOps(bucketId, since = 5, limit = 100) } returns PullOpsResponse(
            ops = listOf(
                OpResponse(
                    globalSequence = 10, deviceId = deviceId, keyEpoch = 1,
                    encryptedPayload = "dGVzdA==", prevHash = "c".repeat(64),
                    currentHash = "d".repeat(64), serverTimestamp = "2026-01-01T00:00:01Z"
                )
            ),
            hasMore = false,
            latestSequence = 10
        )

        val repo = createRepo()
        val result = repo.pullOps(bucketId, afterSequence = 0)

        result.isSuccess shouldBe true
        result.getOrNull()!!.size shouldBe 2
    }

    test("pullOps network error returns failure") {
        coEvery { apiService.pullOps(any(), any(), any()) } throws RuntimeException("Network timeout")

        val repo = createRepo()
        val result = repo.pullOps(bucketId, afterSequence = 0)

        result.isFailure shouldBe true
    }

    // ── Sync State ───────────────────────────────────────────────────────────

    test("getSyncState returns null when no state") {
        coEvery { syncStateDao.getSyncState(bucketId) } returns null

        val repo = createRepo()
        val state = repo.getSyncState(bucketId)

        state shouldBe null
    }

    test("getSyncState returns domain model") {
        coEvery { syncStateDao.getSyncState(bucketId) } returns SyncStateEntity(
            bucketId = bucketId,
            lastGlobalSequence = 42,
            lastSyncTimestamp = "2026-01-01T00:00:00Z"
        )

        val repo = createRepo()
        val state = repo.getSyncState(bucketId)

        state shouldNotBe null
        state!!.bucketId shouldBe bucketId
        state.lastGlobalSequence shouldBe 42
    }

    test("updateSyncState upserts entity") {
        val syncState = SyncState(
            bucketId = bucketId,
            lastGlobalSequence = 50,
            lastSyncTimestamp = Instant.parse("2026-01-01T00:00:00Z")
        )

        val repo = createRepo()
        repo.updateSyncState(syncState)

        coVerify { syncStateDao.upsertSyncState(any()) }
    }

    // ── Checkpoint ───────────────────────────────────────────────────────────

    test("getCheckpoint success returns server checkpoint") {
        coEvery { apiService.getCheckpoint(bucketId) } returns CheckpointResponse(
            startSequence = 0,
            endSequence = 100,
            hash = "abcdef",
            opCount = 100,
            timestamp = "2026-01-01T00:00:00Z"
        )

        val repo = createRepo()
        val result = repo.getCheckpoint(bucketId)

        result.isSuccess shouldBe true
        result.getOrNull()!!.globalSequence shouldBe 100
        result.getOrNull()!!.checkpointHash shouldBe "abcdef"
    }

    test("getCheckpoint failure returns failure result") {
        coEvery { apiService.getCheckpoint(any()) } throws RuntimeException("Not found")

        val repo = createRepo()
        val result = repo.getCheckpoint(bucketId)

        result.isFailure shouldBe true
    }
})
