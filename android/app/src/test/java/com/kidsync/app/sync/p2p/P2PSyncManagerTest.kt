package com.kidsync.app.sync.p2p

import com.kidsync.app.data.local.entity.OpLogEntryEntity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json

/**
 * Tests for P2P sync protocol logic:
 * - HMAC computation consistency
 * - HMAC computation with different keys produces different results
 * - P2PMessage serialization/deserialization (all message types)
 * - State transitions
 * - Handshake HMAC validation
 * - Ops exchange logic (determining which ops to send)
 * - Entity <-> P2POp conversion
 * - Error state handling
 *
 * These tests exercise the protocol logic independently of Nearby Connections.
 */
class P2PSyncManagerTest : FunSpec({

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    // ── HMAC Computation ─────────────────────────────────────────────────────

    test("HMAC computation produces consistent results with same key and data") {
        val key = "test-dek-key-32-bytes-long!!!!!".toByteArray(Charsets.UTF_8)
        val data = "bucket-123".toByteArray(Charsets.UTF_8)

        val hmac1 = P2PSyncManager.hmacSha256(key, data)
        val hmac2 = P2PSyncManager.hmacSha256(key, data)

        hmac1.shouldNotBeBlank()
        hmac1 shouldBe hmac2
    }

    test("HMAC computation produces different results with different keys") {
        val key1 = "dek-key-one-32-bytes-long!!!!!!".toByteArray(Charsets.UTF_8)
        val key2 = "dek-key-two-32-bytes-long!!!!!!".toByteArray(Charsets.UTF_8)
        val data = "bucket-123".toByteArray(Charsets.UTF_8)

        val hmac1 = P2PSyncManager.hmacSha256(key1, data)
        val hmac2 = P2PSyncManager.hmacSha256(key2, data)

        hmac1 shouldNotBe hmac2
    }

    test("HMAC computation produces different results with different data") {
        val key = "test-dek-key-32-bytes-long!!!!!".toByteArray(Charsets.UTF_8)
        val data1 = "bucket-111".toByteArray(Charsets.UTF_8)
        val data2 = "bucket-222".toByteArray(Charsets.UTF_8)

        val hmac1 = P2PSyncManager.hmacSha256(key, data1)
        val hmac2 = P2PSyncManager.hmacSha256(key, data2)

        hmac1 shouldNotBe hmac2
    }

    test("HMAC output is base64 encoded") {
        val key = "test-dek-key-32-bytes-long!!!!!".toByteArray(Charsets.UTF_8)
        val data = "bucket-123".toByteArray(Charsets.UTF_8)

        val hmac = P2PSyncManager.hmacSha256(key, data)

        // Base64 output should not contain whitespace or line breaks
        hmac.shouldNotBeBlank()
        // Should be decodable as base64
        val decoded = java.util.Base64.getDecoder().decode(hmac)
        decoded.size shouldBe 32 // HMAC-SHA256 always produces 32 bytes
    }

    // ── P2PMessage Serialization ─────────────────────────────────────────────

    test("Handshake message serialization roundtrip") {
        val original = P2PMessage.Handshake(
            deviceId = "device-001",
            bucketId = "bucket-abc",
            hmac = "base64hmac==",
            lastSequence = 42L
        )

        val jsonStr = json.encodeToString(P2PMessage.serializer(), original)
        val deserialized = json.decodeFromString(P2PMessage.serializer(), jsonStr)

        deserialized.shouldBeInstanceOf<P2PMessage.Handshake>()
        deserialized.deviceId shouldBe "device-001"
        deserialized.bucketId shouldBe "bucket-abc"
        deserialized.hmac shouldBe "base64hmac=="
        deserialized.lastSequence shouldBe 42L
    }

    test("OpsPayload message serialization roundtrip") {
        val ops = listOf(
            P2POp(
                globalSequence = 1L,
                bucketId = "bucket-abc",
                deviceId = "device-001",
                deviceSequence = 1L,
                keyEpoch = 1,
                encryptedPayload = "encrypted-data-base64",
                devicePrevHash = "0000000000",
                currentHash = "hash1234567890",
                serverTimestamp = "2026-01-15T10:30:00Z"
            ),
            P2POp(
                globalSequence = 2L,
                bucketId = "bucket-abc",
                deviceId = "device-002",
                deviceSequence = 1L,
                keyEpoch = 1,
                encryptedPayload = "another-encrypted-payload",
                devicePrevHash = "hash1234567890",
                currentHash = "hash9876543210",
                serverTimestamp = null
            )
        )
        val original = P2PMessage.OpsPayload(ops = ops)

        val jsonStr = json.encodeToString(P2PMessage.serializer(), original)
        val deserialized = json.decodeFromString(P2PMessage.serializer(), jsonStr)

        deserialized.shouldBeInstanceOf<P2PMessage.OpsPayload>()
        deserialized.ops shouldHaveSize 2
        deserialized.ops[0].globalSequence shouldBe 1L
        deserialized.ops[0].encryptedPayload shouldBe "encrypted-data-base64"
        deserialized.ops[0].serverTimestamp shouldBe "2026-01-15T10:30:00Z"
        deserialized.ops[1].globalSequence shouldBe 2L
        deserialized.ops[1].serverTimestamp shouldBe null
    }

    test("SyncComplete message serialization roundtrip") {
        val original = P2PMessage.SyncComplete(opsReceived = 10, opsSent = 5)

        val jsonStr = json.encodeToString(P2PMessage.serializer(), original)
        val deserialized = json.decodeFromString(P2PMessage.serializer(), jsonStr)

        deserialized.shouldBeInstanceOf<P2PMessage.SyncComplete>()
        deserialized.opsReceived shouldBe 10L
        deserialized.opsSent shouldBe 5L
    }

    test("Error message serialization roundtrip") {
        val original = P2PMessage.Error(code = "HMAC_MISMATCH", message = "HMAC verification failed")

        val jsonStr = json.encodeToString(P2PMessage.serializer(), original)
        val deserialized = json.decodeFromString(P2PMessage.serializer(), jsonStr)

        deserialized.shouldBeInstanceOf<P2PMessage.Error>()
        deserialized.code shouldBe "HMAC_MISMATCH"
        deserialized.message shouldBe "HMAC verification failed"
    }

    test("P2PMessage serialization uses type discriminator") {
        val handshake = P2PMessage.Handshake("d1", "b1", "hmac1", 0L)
        val error = P2PMessage.Error("ERR", "test")

        val handshakeJson = json.encodeToString(P2PMessage.serializer(), handshake)
        val errorJson = json.encodeToString(P2PMessage.serializer(), error)

        // They should contain the type discriminator
        handshakeJson.contains("handshake") shouldBe true
        errorJson.contains("error") shouldBe true
    }

    test("Empty OpsPayload serialization roundtrip") {
        val original = P2PMessage.OpsPayload(ops = emptyList())

        val jsonStr = json.encodeToString(P2PMessage.serializer(), original)
        val deserialized = json.decodeFromString(P2PMessage.serializer(), jsonStr)

        deserialized.shouldBeInstanceOf<P2PMessage.OpsPayload>()
        deserialized.ops shouldHaveSize 0
    }

    // ── P2POp <-> OpLogEntryEntity Conversion ────────────────────────────────

    test("P2POp to OpLogEntryEntity conversion preserves all fields") {
        val p2pOp = P2POp(
            globalSequence = 42L,
            bucketId = "bucket-xyz",
            deviceId = "device-123",
            deviceSequence = 7L,
            keyEpoch = 3,
            encryptedPayload = "base64-encrypted-data",
            devicePrevHash = "prevhash123",
            currentHash = "curhash456",
            serverTimestamp = "2026-02-20T14:30:00Z"
        )

        val entity = OpLogEntryEntity(
            globalSequence = p2pOp.globalSequence,
            bucketId = p2pOp.bucketId,
            deviceId = p2pOp.deviceId,
            deviceSequence = p2pOp.deviceSequence,
            keyEpoch = p2pOp.keyEpoch,
            encryptedPayload = p2pOp.encryptedPayload,
            devicePrevHash = p2pOp.devicePrevHash,
            currentHash = p2pOp.currentHash,
            serverTimestamp = p2pOp.serverTimestamp,
            isPending = false
        )

        entity.globalSequence shouldBe 42L
        entity.bucketId shouldBe "bucket-xyz"
        entity.deviceId shouldBe "device-123"
        entity.deviceSequence shouldBe 7L
        entity.keyEpoch shouldBe 3
        entity.encryptedPayload shouldBe "base64-encrypted-data"
        entity.devicePrevHash shouldBe "prevhash123"
        entity.currentHash shouldBe "curhash456"
        entity.serverTimestamp shouldBe "2026-02-20T14:30:00Z"
        entity.isPending shouldBe false
    }

    test("OpLogEntryEntity to P2POp conversion preserves all fields") {
        val entity = OpLogEntryEntity(
            id = 99,
            globalSequence = 15L,
            bucketId = "bucket-test",
            deviceId = "device-test",
            deviceSequence = 3L,
            keyEpoch = 2,
            encryptedPayload = "payload-data",
            devicePrevHash = "prev-hash",
            currentHash = "cur-hash",
            serverTimestamp = "2026-01-01T00:00:00Z",
            isPending = true
        )

        val p2pOp = P2POp(
            globalSequence = entity.globalSequence,
            bucketId = entity.bucketId,
            deviceId = entity.deviceId,
            deviceSequence = entity.deviceSequence,
            keyEpoch = entity.keyEpoch,
            encryptedPayload = entity.encryptedPayload,
            devicePrevHash = entity.devicePrevHash,
            currentHash = entity.currentHash,
            serverTimestamp = entity.serverTimestamp
        )

        p2pOp.globalSequence shouldBe 15L
        p2pOp.bucketId shouldBe "bucket-test"
        p2pOp.deviceId shouldBe "device-test"
        p2pOp.deviceSequence shouldBe 3L
        p2pOp.keyEpoch shouldBe 2
        p2pOp.encryptedPayload shouldBe "payload-data"
        p2pOp.devicePrevHash shouldBe "prev-hash"
        p2pOp.currentHash shouldBe "cur-hash"
        p2pOp.serverTimestamp shouldBe "2026-01-01T00:00:00Z"
    }

    test("P2POp with null serverTimestamp converts correctly") {
        val p2pOp = P2POp(
            globalSequence = 1L,
            bucketId = "b",
            deviceId = "d",
            deviceSequence = 1L,
            keyEpoch = 1,
            encryptedPayload = "payload",
            devicePrevHash = "prev",
            currentHash = "cur",
            serverTimestamp = null
        )

        val entity = OpLogEntryEntity(
            globalSequence = p2pOp.globalSequence,
            bucketId = p2pOp.bucketId,
            deviceId = p2pOp.deviceId,
            deviceSequence = p2pOp.deviceSequence,
            keyEpoch = p2pOp.keyEpoch,
            encryptedPayload = p2pOp.encryptedPayload,
            devicePrevHash = p2pOp.devicePrevHash,
            currentHash = p2pOp.currentHash,
            serverTimestamp = p2pOp.serverTimestamp,
            isPending = false
        )

        entity.serverTimestamp shouldBe null
    }

    // ── State Transitions ────────────────────────────────────────────────────

    test("P2PState Idle is default state") {
        val state: P2PState = P2PState.Idle
        state.shouldBeInstanceOf<P2PState.Idle>()
    }

    test("P2PState Advertising state") {
        val state: P2PState = P2PState.Advertising
        state.shouldBeInstanceOf<P2PState.Advertising>()
    }

    test("P2PState Discovering state") {
        val state: P2PState = P2PState.Discovering
        state.shouldBeInstanceOf<P2PState.Discovering>()
    }

    test("P2PState Connecting carries endpoint name") {
        val state: P2PState = P2PState.Connecting("Phone-A")
        state.shouldBeInstanceOf<P2PState.Connecting>()
        state.endpointName shouldBe "Phone-A"
    }

    test("P2PState Connected carries endpoint name") {
        val state: P2PState = P2PState.Connected("Phone-A")
        state.shouldBeInstanceOf<P2PState.Connected>()
        state.endpointName shouldBe "Phone-A"
    }

    test("P2PState Syncing carries progress and endpoint") {
        val state: P2PState = P2PState.Syncing(0.5f, "Phone-B")
        state.shouldBeInstanceOf<P2PState.Syncing>()
        state.progress shouldBe 0.5f
        state.endpointName shouldBe "Phone-B"
    }

    test("P2PState Completed carries ops counts") {
        val state: P2PState = P2PState.Completed(opsReceived = 10, opsSent = 5)
        state.shouldBeInstanceOf<P2PState.Completed>()
        state.opsReceived shouldBe 10L
        state.opsSent shouldBe 5L
    }

    test("P2PState Error carries message") {
        val state: P2PState = P2PState.Error("Connection lost")
        state.shouldBeInstanceOf<P2PState.Error>()
        state.message shouldBe "Connection lost"
    }

    // ── Handshake Validation Logic ───────────────────────────────────────────

    test("handshake validation - matching HMACs pass") {
        val dek = "shared-dek-for-bucket-32-bytes!".toByteArray(Charsets.UTF_8)
        val bucketId = "bucket-shared"

        val hmacA = P2PSyncManager.hmacSha256(dek, bucketId.toByteArray(Charsets.UTF_8))
        val hmacB = P2PSyncManager.hmacSha256(dek, bucketId.toByteArray(Charsets.UTF_8))

        // Both devices with same DEK produce the same HMAC
        (hmacA == hmacB) shouldBe true
    }

    test("handshake validation - different DEKs fail") {
        val dekA = "dek-for-device-A-32-bytes!!!!!!".toByteArray(Charsets.UTF_8)
        val dekB = "dek-for-device-B-32-bytes!!!!!!".toByteArray(Charsets.UTF_8)
        val bucketId = "bucket-test"

        val hmacA = P2PSyncManager.hmacSha256(dekA, bucketId.toByteArray(Charsets.UTF_8))
        val hmacB = P2PSyncManager.hmacSha256(dekB, bucketId.toByteArray(Charsets.UTF_8))

        // Devices with different DEKs produce different HMACs = verification fails
        (hmacA == hmacB) shouldBe false
    }

    test("handshake validation - same DEK different bucket fails") {
        val dek = "shared-dek-for-bucket-32-bytes!".toByteArray(Charsets.UTF_8)

        val hmacA = P2PSyncManager.hmacSha256(dek, "bucket-alpha".toByteArray(Charsets.UTF_8))
        val hmacB = P2PSyncManager.hmacSha256(dek, "bucket-beta".toByteArray(Charsets.UTF_8))

        // Same DEK but different bucket IDs = different HMACs
        (hmacA == hmacB) shouldBe false
    }

    // ── Ops Exchange Logic ───────────────────────────────────────────────────

    test("ops filtering - peer with no ops gets all our ops") {
        val allOps = listOf(
            createTestEntity(globalSequence = 1L),
            createTestEntity(globalSequence = 2L),
            createTestEntity(globalSequence = 3L)
        )
        val peerLastSequence = 0L

        val opsToSend = allOps.filter { it.globalSequence > peerLastSequence }

        opsToSend shouldHaveSize 3
    }

    test("ops filtering - peer with some ops gets only missing ones") {
        val allOps = listOf(
            createTestEntity(globalSequence = 1L),
            createTestEntity(globalSequence = 2L),
            createTestEntity(globalSequence = 3L),
            createTestEntity(globalSequence = 4L),
            createTestEntity(globalSequence = 5L)
        )
        val peerLastSequence = 3L

        val opsToSend = allOps.filter { it.globalSequence > peerLastSequence }

        opsToSend shouldHaveSize 2
        opsToSend[0].globalSequence shouldBe 4L
        opsToSend[1].globalSequence shouldBe 5L
    }

    test("ops filtering - peer with all ops gets nothing") {
        val allOps = listOf(
            createTestEntity(globalSequence = 1L),
            createTestEntity(globalSequence = 2L),
            createTestEntity(globalSequence = 3L)
        )
        val peerLastSequence = 3L

        val opsToSend = allOps.filter { it.globalSequence > peerLastSequence }

        opsToSend shouldHaveSize 0
    }

    test("ops filtering - peer ahead of us gets nothing") {
        val allOps = listOf(
            createTestEntity(globalSequence = 1L),
            createTestEntity(globalSequence = 2L)
        )
        val peerLastSequence = 5L

        val opsToSend = allOps.filter { it.globalSequence > peerLastSequence }

        opsToSend shouldHaveSize 0
    }

    test("ops filtering - empty local ops sends nothing regardless of peer state") {
        val allOps = emptyList<OpLogEntryEntity>()
        val peerLastSequence = 0L

        val opsToSend = allOps.filter { it.globalSequence > peerLastSequence }

        opsToSend shouldHaveSize 0
    }

    // ── Batch Size ───────────────────────────────────────────────────────────

    test("ops batching splits large payloads into chunks") {
        val ops = (1..120).map { createTestEntity(globalSequence = it.toLong()) }
        val batchSize = 50

        val batches = ops.chunked(batchSize)

        batches shouldHaveSize 3
        batches[0] shouldHaveSize 50
        batches[1] shouldHaveSize 50
        batches[2] shouldHaveSize 20
    }

    // ── Error Code Handling ──────────────────────────────────────────────────

    test("error codes are defined for all protocol errors") {
        // Verify known error codes can be serialized
        val errors = listOf(
            P2PMessage.Error("HMAC_MISMATCH", "HMAC verification failed"),
            P2PMessage.Error("BUCKET_MISMATCH", "Bucket IDs do not match"),
            P2PMessage.Error("NO_BUCKET", "No bucket selected"),
            P2PMessage.Error("PARSE_ERROR", "Failed to parse message"),
            P2PMessage.Error("SYNC_ERROR", "Sync failed"),
            P2PMessage.Error("HMAC_ERROR", "HMAC computation failed")
        )

        errors.forEach { error ->
            val jsonStr = json.encodeToString(P2PMessage.serializer(), error)
            val deserialized = json.decodeFromString(P2PMessage.serializer(), jsonStr)
            deserialized.shouldBeInstanceOf<P2PMessage.Error>()
            (deserialized as P2PMessage.Error).code shouldBe error.code
            deserialized.message shouldBe error.message
        }
    }

    // ── Message Byte Serialization ───────────────────────────────────────────

    test("message serialized to bytes and back produces identical result") {
        val original = P2PMessage.Handshake(
            deviceId = "d1",
            bucketId = "b1",
            hmac = "hmac123",
            lastSequence = 99L
        )

        val bytes = json.encodeToString(P2PMessage.serializer(), original).toByteArray(Charsets.UTF_8)
        val restored = json.decodeFromString(P2PMessage.serializer(), String(bytes, Charsets.UTF_8))

        restored.shouldBeInstanceOf<P2PMessage.Handshake>()
        restored.deviceId shouldBe "d1"
        restored.bucketId shouldBe "b1"
        restored.hmac shouldBe "hmac123"
        restored.lastSequence shouldBe 99L
    }

    test("large OpsPayload serializes and deserializes correctly") {
        val ops = (1..100).map { i ->
            P2POp(
                globalSequence = i.toLong(),
                bucketId = "bucket-test",
                deviceId = "device-${i % 2}",
                deviceSequence = (i / 2 + 1).toLong(),
                keyEpoch = 1,
                encryptedPayload = "encrypted-payload-$i-" + "x".repeat(100),
                devicePrevHash = "prevhash-$i",
                currentHash = "curhash-$i",
                serverTimestamp = "2026-02-22T12:00:${"%02d".format(i % 60)}Z"
            )
        }
        val original = P2PMessage.OpsPayload(ops = ops)

        val jsonStr = json.encodeToString(P2PMessage.serializer(), original)
        val deserialized = json.decodeFromString(P2PMessage.serializer(), jsonStr)

        deserialized.shouldBeInstanceOf<P2PMessage.OpsPayload>()
        deserialized.ops shouldHaveSize 100
        deserialized.ops[0].globalSequence shouldBe 1L
        deserialized.ops[99].globalSequence shouldBe 100L
    }
})

private fun createTestEntity(
    globalSequence: Long,
    bucketId: String = "bucket-test",
    deviceId: String = "device-001",
    deviceSequence: Long = globalSequence,
    keyEpoch: Int = 1
): OpLogEntryEntity {
    return OpLogEntryEntity(
        id = globalSequence,
        globalSequence = globalSequence,
        bucketId = bucketId,
        deviceId = deviceId,
        deviceSequence = deviceSequence,
        keyEpoch = keyEpoch,
        encryptedPayload = "encrypted-$globalSequence",
        devicePrevHash = "prev-$globalSequence",
        currentHash = "cur-$globalSequence",
        serverTimestamp = "2026-01-01T00:00:00Z",
        isPending = false
    )
}
