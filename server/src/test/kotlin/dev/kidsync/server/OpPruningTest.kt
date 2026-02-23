package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.uploadOpsBatch
import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for SEC5-S-14: Op table pruning after checkpoints.
 */
class OpPruningTest {

    /**
     * Helper: upload enough ops to create checkpoints, return checkpoint IDs.
     */
    private suspend fun uploadOpsAndGetCheckpoints(
        client: io.ktor.client.HttpClient,
        device: TestDevice,
        opsCount: Int,
    ): List<CheckpointData> {
        val bucketId = device.bucketId!!

        // Upload ops in batches of up to 100
        var prevHash = "0".repeat(64)
        var remaining = opsCount
        var batchNum = 0
        while (remaining > 0) {
            val batchSize = minOf(remaining, 100)
            batchNum++
            prevHash = uploadOpsBatch(client, device, batchSize, startPrevHash = prevHash, localIdPrefix = "batch$batchNum")
            remaining -= batchSize
        }

        // Get checkpoint info
        val cpResp = client.get("/buckets/$bucketId/checkpoint") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        if (cpResp.status == HttpStatusCode.NotFound) return emptyList()
        val cpBody = cpResp.body<CheckpointResponse>()
        return listOf(cpBody.checkpoint)
    }

    private suspend fun acknowledgeCheckpoint(
        client: io.ktor.client.HttpClient,
        device: TestDevice,
        checkpointId: Int,
    ): HttpResponse {
        val bucketId = device.bucketId!!
        return client.post("/buckets/$bucketId/checkpoints/acknowledge") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(AcknowledgeCheckpointRequest(checkpointId = checkpointId))
        }
    }

    private suspend fun getOpsCount(
        client: io.ktor.client.HttpClient,
        device: TestDevice,
    ): Int {
        val bucketId = device.bucketId!!
        val resp = client.get("/buckets/$bucketId/ops?since=0&limit=1000") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body<PullOpsResponse>().ops.size
    }

    @Test
    fun `acknowledge checkpoint returns 200`() = testApplication {
        // checkpointInterval=100 so 100 ops creates 1 checkpoint
        val config = testConfig().copy(checkpointInterval = 100)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        uploadOpsBatch(client, device, 100)

        // Get checkpoint
        val cpResp = client.get("/buckets/${device.bucketId}/checkpoint") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, cpResp.status)

        // The checkpoint ID is the auto-increment ID from the Checkpoints table.
        // We need to get it. We'll use checkpointId=1 since it's the first checkpoint.
        val ackResp = acknowledgeCheckpoint(client, device, 1)
        assertEquals(HttpStatusCode.OK, ackResp.status)
    }

    @Test
    fun `idempotent checkpoint acknowledgment`() = testApplication {
        val config = testConfig().copy(checkpointInterval = 100)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        uploadOpsBatch(client, device, 100)

        // Acknowledge twice
        val ack1 = acknowledgeCheckpoint(client, device, 1)
        assertEquals(HttpStatusCode.OK, ack1.status)

        val ack2 = acknowledgeCheckpoint(client, device, 1)
        assertEquals(HttpStatusCode.OK, ack2.status)
    }

    @Test
    fun `acknowledge nonexistent checkpoint returns 404`() = testApplication {
        val config = testConfig().copy(checkpointInterval = 100)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val ackResp = acknowledgeCheckpoint(client, device, 9999)
        assertEquals(HttpStatusCode.NotFound, ackResp.status)
    }

    @Test
    fun `all devices acknowledge - ops pruned after pruneAcknowledgedOps`() = testApplication {
        // Use small checkpoint interval for testing
        val config = testConfig().copy(checkpointInterval = 10)
        application { module(config) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Upload 30 ops to create 3 checkpoints (interval=10)
        var prevHash = "0".repeat(64)
        prevHash = uploadOpsBatch(client, deviceA, 10, startPrevHash = prevHash, localIdPrefix = "a1")
        prevHash = uploadOpsBatch(client, deviceA, 10, startPrevHash = prevHash, localIdPrefix = "a2")
        uploadOpsBatch(client, deviceA, 10, startPrevHash = prevHash, localIdPrefix = "a3")

        // Verify 30 ops exist
        assertEquals(30, getOpsCount(client, deviceA))

        // Both devices acknowledge checkpoints 1, 2, 3
        for (cpId in 1..3) {
            acknowledgeCheckpoint(client, deviceA, cpId)
            acknowledgeCheckpoint(client, deviceB, cpId)
        }

        // Trigger pruning via the sync service
        val syncService = dev.kidsync.server.services.SyncService(config)
        val pruned = syncService.pruneAcknowledgedOps(bucketId)

        // Should have pruned ops from checkpoints 1 and 2 (not 3, safety margin)
        // That's 20 ops pruned
        assertEquals(20, pruned)

        // Remaining ops should be 10
        assertEquals(10, getOpsCount(client, deviceA))
    }

    @Test
    fun `partial acknowledgment - no pruning`() = testApplication {
        val config = testConfig().copy(checkpointInterval = 10)
        application { module(config) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Upload 20 ops to create 2 checkpoints
        var prevHash = "0".repeat(64)
        prevHash = uploadOpsBatch(client, deviceA, 10, startPrevHash = prevHash, localIdPrefix = "a1")
        uploadOpsBatch(client, deviceA, 10, startPrevHash = prevHash, localIdPrefix = "a2")

        assertEquals(20, getOpsCount(client, deviceA))

        // Only device A acknowledges - device B hasn't acknowledged
        acknowledgeCheckpoint(client, deviceA, 1)
        acknowledgeCheckpoint(client, deviceA, 2)

        // Trigger pruning
        val syncService = dev.kidsync.server.services.SyncService(config)
        val pruned = syncService.pruneAcknowledgedOps(bucketId)

        // No pruning because B hasn't acknowledged
        assertEquals(0, pruned)
        assertEquals(20, getOpsCount(client, deviceA))
    }

    @Test
    fun `revoked device does not block pruning`() = testApplication {
        val config = testConfig().copy(checkpointInterval = 10)
        application { module(config) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Upload 20 ops to create 2 checkpoints
        var prevHash = "0".repeat(64)
        prevHash = uploadOpsBatch(client, deviceA, 10, startPrevHash = prevHash, localIdPrefix = "a1")
        uploadOpsBatch(client, deviceA, 10, startPrevHash = prevHash, localIdPrefix = "a2")

        // Revoke device B
        client.delete("/buckets/$bucketId/devices/${deviceB.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }

        // Only device A needs to acknowledge (B is revoked)
        acknowledgeCheckpoint(client, deviceA, 1)
        acknowledgeCheckpoint(client, deviceA, 2)

        // Trigger pruning
        val syncService = dev.kidsync.server.services.SyncService(config)
        val pruned = syncService.pruneAcknowledgedOps(bucketId)

        // Checkpoint 1 pruned (checkpoint 2 preserved as safety margin)
        assertEquals(10, pruned)
    }

    @Test
    fun `latest checkpoint ops preserved as safety margin`() = testApplication {
        val config = testConfig().copy(checkpointInterval = 10)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Upload 10 ops (1 checkpoint)
        uploadOpsBatch(client, device, 10)

        // Acknowledge the only checkpoint
        acknowledgeCheckpoint(client, device, 1)

        // Trigger pruning
        val syncService = dev.kidsync.server.services.SyncService(config)
        val pruned = syncService.pruneAcknowledgedOps(bucketId)

        // Should NOT prune - the latest fully-acknowledged checkpoint is preserved
        assertEquals(0, pruned)
        assertEquals(10, getOpsCount(client, device))
    }
}
