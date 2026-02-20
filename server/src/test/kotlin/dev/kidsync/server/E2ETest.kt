package dev.kidsync.server

import dev.kidsync.server.TestHelper.computeHash
import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 7 End-to-End test suite covering full co-parenting workflows.
 */
class E2ETest {

    // ================================================================
    // Test 1: Full co-parent lifecycle
    // ================================================================

    @Test
    fun `full co-parent lifecycle`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // --- Parent A registers, creates family, creates invite ---
        val emailA = "e2e-lifecycleA-${System.nanoTime()}@example.com"
        val emailB = "e2e-lifecycleB-${System.nanoTime()}@example.com"

        val regA = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = emailA, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<RegisterResponse>()

        val family = client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${regA.token}")
            setBody(CreateFamilyRequest(name = "Lifecycle Family"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<CreateFamilyResponse>()

        val invite = client.post("/families/${family.familyId}/invite") {
            header(HttpHeaders.Authorization, "Bearer ${regA.token}")
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<InviteResponse>()
        assertNotNull(invite.inviteToken)

        // --- Parent B registers, joins family with invite ---
        val regB = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = emailB, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<RegisterResponse>()

        val joinResp = client.post("/families/${family.familyId}/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${regB.token}")
            setBody(JoinFamilyRequest(inviteToken = invite.inviteToken, devicePublicKey = "parentB-pub-key"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<JoinFamilyResponse>()
        assertEquals(2, joinResp.members.size)

        // --- Both re-login to get updated JWT with familyIds ---
        val loginA = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = emailA, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<LoginResponse>()

        val loginB = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = emailB, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<LoginResponse>()

        val parentA = TestUser(loginA.token, regA.userId, regA.deviceId, family.familyId)
        val parentB = TestUser(loginB.token, regB.userId, regB.deviceId, family.familyId)

        // --- Parent A uploads a schedule op (valid hash chain) ---
        val prevHash0 = "0".repeat(64)
        val schedulePayload = Base64.getEncoder().encodeToString("schedule-create".toByteArray())
        val scheduleHash = computeHash(prevHash0, schedulePayload)

        val scheduleUpload = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${parentA.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = parentA.deviceId,
                            entityType = "CustodySchedule",
                            entityId = "sched-001",
                            operation = "CREATE",
                            encryptedPayload = schedulePayload,
                            devicePrevHash = prevHash0,
                            currentHash = scheduleHash,
                            keyEpoch = 1,
                            localId = "schedule-op",
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, scheduleUpload.status)
        val scheduleResult = scheduleUpload.body<UploadOpsResponse>()
        assertEquals(1, scheduleResult.accepted.size)

        // --- Parent A uploads a swap request op ---
        val swapPayload = Base64.getEncoder().encodeToString("swap-request".toByteArray())
        val swapHash = computeHash(scheduleHash, swapPayload)

        val swapUpload = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${parentA.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = parentA.deviceId,
                            entityType = "ScheduleOverride",
                            entityId = "swap-001",
                            operation = "CREATE",
                            encryptedPayload = swapPayload,
                            devicePrevHash = scheduleHash,
                            currentHash = swapHash,
                            keyEpoch = 1,
                            localId = "swap-op",
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, swapUpload.status)

        // --- Parent A uploads an expense op ---
        val expensePayload = Base64.getEncoder().encodeToString("expense-create".toByteArray())
        val expenseHash = computeHash(swapHash, expensePayload)

        val expenseUpload = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${parentA.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = parentA.deviceId,
                            entityType = "Expense",
                            entityId = "exp-001",
                            operation = "CREATE",
                            encryptedPayload = expensePayload,
                            devicePrevHash = swapHash,
                            currentHash = expenseHash,
                            keyEpoch = 1,
                            localId = "expense-op",
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, expenseUpload.status)

        // --- Parent B pulls all ops, verifies all 3 received ---
        val pullB = client.get("/sync/ops?since=0&limit=100") {
            header(HttpHeaders.Authorization, "Bearer ${parentB.token}")
        }
        assertEquals(HttpStatusCode.OK, pullB.status)
        val pullBBody = pullB.body<PullOpsResponse>()
        assertEquals(3, pullBBody.ops.size, "Parent B should see all 3 ops from Parent A")
        assertFalse(pullBBody.hasMore)

        // Verify entity types
        assertEquals("CustodySchedule", pullBBody.ops[0].entityType)
        assertEquals("ScheduleOverride", pullBBody.ops[1].entityType)
        assertEquals("Expense", pullBBody.ops[2].entityType)

        // --- Parent B uploads an expense acknowledgment op ---
        val bPrevHash = "0".repeat(64) // Parent B's device has its own hash chain
        val ackPayload = Base64.getEncoder().encodeToString("expense-ack".toByteArray())
        val ackHash = computeHash(bPrevHash, ackPayload)

        val ackUpload = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${parentB.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = parentB.deviceId,
                            entityType = "ExpenseStatus",
                            entityId = "exp-001-ack",
                            operation = "CREATE",
                            encryptedPayload = ackPayload,
                            devicePrevHash = bPrevHash,
                            currentHash = ackHash,
                            keyEpoch = 1,
                            localId = "ack-op",
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, ackUpload.status)

        // --- Parent A pulls since their last sequence, verifies they get Parent B's op ---
        val lastSeqA = pullBBody.ops.last().globalSequence
        val pullA = client.get("/sync/ops?since=$lastSeqA&limit=100") {
            header(HttpHeaders.Authorization, "Bearer ${parentA.token}")
        }
        assertEquals(HttpStatusCode.OK, pullA.status)
        val pullABody = pullA.body<PullOpsResponse>()
        assertEquals(1, pullABody.ops.size, "Parent A should see 1 new op from Parent B")
        assertEquals("ExpenseStatus", pullABody.ops[0].entityType)
        assertEquals(parentB.deviceId, pullABody.ops[0].deviceId)
    }

    // ================================================================
    // Test 2: Multi-device sync consistency
    // ================================================================

    @Test
    fun `multi-device sync consistency`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val email = "e2e-multidev-${System.nanoTime()}@example.com"

        // Register user and create family
        val reg = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<RegisterResponse>()

        val device1Id = reg.deviceId

        client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(CreateFamilyRequest(name = "Multi-Device Family"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // Register a second device
        val device2 = client.post("/devices") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(RegisterDeviceRequest(deviceName = "Tablet", publicKey = "device2-pub-key"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<RegisterDeviceResponse>()
        val device2Id = device2.deviceId

        // Re-login to get fresh token with familyIds
        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<LoginResponse>()

        val token = login.token

        // Upload ops from device 1
        var prevHash = "0".repeat(64)
        val device1Ops = 3
        for (i in 1..device1Ops) {
            val payload = Base64.getEncoder().encodeToString("dev1-payload-$i".toByteArray())
            val curHash = computeHash(prevHash, payload)
            client.post("/sync/ops") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(
                    UploadOpsRequest(
                        ops = listOf(
                            OpInput(
                                deviceId = device1Id,
                                encryptedPayload = payload,
                                devicePrevHash = prevHash,
                                currentHash = curHash,
                                keyEpoch = 1,
                                localId = "dev1-op-$i",
                            )
                        )
                    )
                )
            }.also { assertEquals(HttpStatusCode.OK, it.status) }
            prevHash = curHash
        }

        // Pull ops and verify all ops are visible
        val pullResp = client.get("/sync/ops?since=0&limit=100") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, pullResp.status)
        val pullBody = pullResp.body<PullOpsResponse>()
        assertEquals(device1Ops, pullBody.ops.size)

        // Verify ops contain correct deviceId
        for (op in pullBody.ops) {
            assertEquals(device1Id, op.deviceId, "All ops should be from device 1")
        }
    }

    // ================================================================
    // Test 3: Hash chain break detection
    // ================================================================

    @Test
    fun `server rejects broken hash chain`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = TestHelper.setupUserWithFamily(client)

        // Upload first op with correct hash chain
        val prevHash1 = "0".repeat(64)
        val payload1 = Base64.getEncoder().encodeToString("first-op".toByteArray())
        val hash1 = computeHash(prevHash1, payload1)

        val firstUpload = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user.deviceId,
                            encryptedPayload = payload1,
                            devicePrevHash = prevHash1,
                            currentHash = hash1,
                            keyEpoch = 1,
                            localId = "first-op",
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, firstUpload.status)

        // Upload second op with WRONG devicePrevHash (not matching first op's currentHash)
        val wrongPrevHash = "a".repeat(64) // This should be hash1, not "aaa...a"
        val payload2 = Base64.getEncoder().encodeToString("second-op".toByteArray())
        val hash2 = computeHash(wrongPrevHash, payload2) // Hash computed with wrong prev, but that's fine for the hash itself

        val secondUpload = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = user.deviceId,
                            encryptedPayload = payload2,
                            devicePrevHash = wrongPrevHash,
                            currentHash = hash2,
                            keyEpoch = 1,
                            localId = "bad-chain-op",
                        )
                    )
                )
            )
        }

        // Server should reject with 409 Conflict (HASH_CHAIN_BREAK)
        assertEquals(HttpStatusCode.Conflict, secondUpload.status,
            "Expected 409 for broken hash chain, got ${secondUpload.status}")
    }

    // ================================================================
    // Test 4: Device revocation blocks ops
    // ================================================================

    @Test
    fun `revoked device cannot upload ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val email = "e2e-revoke-${System.nanoTime()}@example.com"

        // Register user, create family
        val reg = client.post("/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(email = email, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<RegisterResponse>()

        client.post("/families") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(CreateFamilyRequest(name = "Revoke Test Family"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // Register second device
        val device2 = client.post("/devices") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${reg.token}")
            setBody(RegisterDeviceRequest(deviceName = "To Be Revoked", publicKey = "revoke-key"))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }
            .body<RegisterDeviceResponse>()

        // Re-login to get token with familyIds
        val login = client.post("/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = email, password = "strong-password-12345"))
        }.also { assertEquals(HttpStatusCode.OK, it.status) }
            .body<LoginResponse>()

        // Revoke the second device
        val revokeResp = client.delete("/devices/${device2.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${login.token}")
        }
        assertEquals(HttpStatusCode.NoContent, revokeResp.status)

        // Try to upload ops claiming to be from the revoked device
        val prevHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("from-revoked".toByteArray())
        val hash = computeHash(prevHash, payload)

        val uploadResp = client.post("/sync/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${login.token}")
            setBody(
                UploadOpsRequest(
                    ops = listOf(
                        OpInput(
                            deviceId = device2.deviceId,
                            encryptedPayload = payload,
                            devicePrevHash = prevHash,
                            currentHash = hash,
                            keyEpoch = 1,
                            localId = "revoked-op",
                        )
                    )
                )
            )
        }

        // Verify rejection with 403 Forbidden
        assertEquals(HttpStatusCode.Forbidden, uploadResp.status,
            "Expected 403 for revoked device, got ${uploadResp.status}")
    }

    // ================================================================
    // Test 5: Concurrent ops from two users
    // ================================================================

    @Test
    fun `two users sync ops correctly`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (userA, userB) = TestHelper.setupTwoUserFamily(client)

        // User A uploads 5 ops with valid hash chain
        TestHelper.uploadOpsChain(client, userA, 5, localIdPrefix = "userA")

        // User B uploads 5 ops with valid hash chain (different deviceId, own chain)
        TestHelper.uploadOpsChain(client, userB, 5, localIdPrefix = "userB")

        // Both users pull all ops
        val pullA = client.get("/sync/ops?since=0&limit=100") {
            header(HttpHeaders.Authorization, "Bearer ${userA.token}")
        }.body<PullOpsResponse>()

        val pullB = client.get("/sync/ops?since=0&limit=100") {
            header(HttpHeaders.Authorization, "Bearer ${userB.token}")
        }.body<PullOpsResponse>()

        // Verify each user sees all 10 ops
        assertEquals(10, pullA.ops.size, "User A should see 10 ops total")
        assertEquals(10, pullB.ops.size, "User B should see 10 ops total")

        // Verify global sequences are contiguous (1..10)
        val seqsA = pullA.ops.map { it.globalSequence }
        for (i in 1 until seqsA.size) {
            assertEquals(seqsA[i - 1] + 1, seqsA[i],
                "Global sequences should be contiguous, gap at index $i")
        }

        // Verify both users see the same ops in the same order
        assertEquals(
            pullA.ops.map { it.globalSequence },
            pullB.ops.map { it.globalSequence },
            "Both users should see ops in the same global sequence order"
        )

        // Verify we see ops from both devices
        val deviceIds = pullA.ops.map { it.deviceId }.toSet()
        assertEquals(2, deviceIds.size, "Should see ops from both devices")
        assertTrue(deviceIds.contains(userA.deviceId))
        assertTrue(deviceIds.contains(userB.deviceId))
    }

    // ================================================================
    // Test 6: Checkpoint verification
    // ================================================================

    @Test
    fun `checkpoint hash is consistent`() = testApplication {
        // Use a small checkpoint interval for testing
        val config = testConfig().copy(checkpointInterval = 5)
        application { module(config) }
        val client = createJsonClient()

        val user = TestHelper.setupUserWithFamily(client, familyName = "Checkpoint Family")

        // Upload 10 ops (should trigger at least 1 checkpoint at interval=5)
        TestHelper.uploadOpsChain(client, user, 10, localIdPrefix = "chk")

        // GET /sync/checkpoint
        val checkpointResp = client.get("/sync/checkpoint") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
        }
        assertEquals(HttpStatusCode.OK, checkpointResp.status)
        val checkpoint = checkpointResp.body<CheckpointResponse>()

        // Verify latestSequence matches
        assertTrue(checkpoint.latestSequence >= 10, "latestSequence should be at least 10, was ${checkpoint.latestSequence}")

        // Verify checkpoint is non-null and references valid sequence range
        assertNotNull(checkpoint.checkpoint, "Checkpoint should exist after 10 ops with interval=5")
        val cp = checkpoint.checkpoint!!
        assertTrue(cp.startSequence >= 1, "Checkpoint start should be >= 1")
        assertTrue(cp.endSequence >= cp.startSequence, "Checkpoint end >= start")
        assertTrue(cp.opCount > 0, "Checkpoint should cover at least 1 op")
        assertTrue(cp.hash.isNotEmpty(), "Checkpoint hash should not be empty")
    }

    // ================================================================
    // Test 7: High volume ops with pagination
    // ================================================================

    @Test
    fun `handles 100 ops with pagination`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = TestHelper.setupUserWithFamily(client, familyName = "Pagination Family")

        // Upload 100 ops in batches of 10 (maintain hash chain)
        var prevHash = "0".repeat(64)
        for (batch in 1..10) {
            prevHash = TestHelper.uploadOpsBatch(
                client, user,
                count = 10,
                startPrevHash = prevHash,
                localIdPrefix = "batch$batch",
            )
        }

        // Pull all ops with limit=25 (should require 4 pages)
        val allOps = mutableListOf<OpOutput>()
        var since = 0L
        var pageCount = 0

        while (true) {
            val page = client.get("/sync/ops?since=$since&limit=25") {
                header(HttpHeaders.Authorization, "Bearer ${user.token}")
            }.body<PullOpsResponse>()

            allOps.addAll(page.ops)
            pageCount++

            if (!page.hasMore) break
            since = page.ops.last().globalSequence

            // Safety: prevent infinite loops
            assertTrue(pageCount <= 10, "Too many pages, possible infinite loop")
        }

        // Verify all 100 ops received
        assertEquals(100, allOps.size, "Should receive all 100 ops across pages")

        // Verify pagination required 4 pages
        assertEquals(4, pageCount, "100 ops / limit 25 = 4 pages")

        // Verify global sequences are contiguous
        val sequences = allOps.map { it.globalSequence }
        for (i in 1 until sequences.size) {
            assertEquals(sequences[i - 1] + 1, sequences[i],
                "Global sequences should be contiguous, gap at index $i: ${sequences[i - 1]} -> ${sequences[i]}")
        }
    }

    // ================================================================
    // Test 8: Snapshot upload and retrieval
    // ================================================================

    @Test
    fun `snapshot lifecycle`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val user = TestHelper.setupUserWithFamily(client, familyName = "Snapshot Family")

        // Upload some ops first
        TestHelper.uploadOpsChain(client, user, 5, localIdPrefix = "snap")

        // Upload a snapshot via multipart
        val snapshotData = "encrypted-snapshot-data-for-testing".toByteArray()
        val snapshotSha256 = HashUtil.sha256Hex(snapshotData)
        val metadata = SnapshotMetadata(
            deviceId = user.deviceId,
            atSequence = 5,
            keyEpoch = 1,
            sizeBytes = snapshotData.size.toLong(),
            sha256 = snapshotSha256,
            signature = "test-signature",
            createdAt = "2026-02-20T12:00:00Z",
        )

        val uploadResp = client.post("/sync/snapshot") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("metadata", Json.encodeToString(metadata))
                        append("snapshot", snapshotData, Headers.build {
                            append(HttpHeaders.ContentType, "application/octet-stream")
                            append(HttpHeaders.ContentDisposition, "filename=\"snapshot.bin\"")
                        })
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.Created, uploadResp.status,
            "Snapshot upload failed: ${uploadResp.bodyAsText()}")
        val uploadBody = uploadResp.body<UploadSnapshotResponse>()
        assertNotNull(uploadBody.snapshotId)
        assertEquals(5L, uploadBody.sequence)

        // GET /sync/snapshot/latest
        val latestResp = client.get("/sync/snapshot/latest") {
            header(HttpHeaders.Authorization, "Bearer ${user.token}")
        }
        assertEquals(HttpStatusCode.OK, latestResp.status)
        val latest = latestResp.body<LatestSnapshotResponse>()

        // Verify snapshot metadata matches
        assertEquals(uploadBody.snapshotId, latest.snapshotId)
        assertEquals(user.deviceId, latest.deviceId)
        assertEquals(5L, latest.atSequence)
        assertEquals(5L, latest.sequence)
        assertEquals(1, latest.keyEpoch)
        assertEquals(snapshotData.size.toLong(), latest.sizeBytes)
        assertEquals(snapshotSha256, latest.sha256)
        assertEquals("test-signature", latest.signature)
        assertNotNull(latest.downloadUrl)
        assertNotNull(latest.createdAt)
    }
}
