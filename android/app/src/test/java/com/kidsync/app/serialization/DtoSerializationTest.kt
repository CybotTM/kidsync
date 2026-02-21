package com.kidsync.app.serialization

import com.kidsync.app.data.remote.dto.*
import com.kidsync.app.domain.model.DecryptedPayload
import com.kidsync.app.ui.viewmodel.QrPairingPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/**
 * Serialization/deserialization tests for all DTOs:
 * - PullOpsResponse: roundtrip, hasMore field, empty ops
 * - AcceptedOp: roundtrip, field mapping
 * - BucketDevicesResponse: roundtrip, multiple devices
 * - QrPairingPayload: roundtrip, version field, compact keys
 * - DecryptedPayload: roundtrip with nested data
 * - OpsBatchRequest / OpsBatchResponse: roundtrip
 * - RegisterRequest / RegisterResponse: roundtrip
 * - ChallengeResponse / VerifyResponse: roundtrip
 * - ErrorResponse: with and without details
 * - CheckpointResponse: nullable fields
 * - WrappedKeyResponse: nullable crossSignature
 * - RecoveryBlobResponse: roundtrip
 * - LatestSnapshotResponse: all fields
 */
class DtoSerializationTest : FunSpec({

    val json = Json { ignoreUnknownKeys = true }

    // ── PullOpsResponse ───────────────────────────────────────────────────────

    test("PullOpsResponse roundtrip with multiple ops") {
        val original = PullOpsResponse(
            ops = listOf(
                OpResponse(
                    globalSequence = 1,
                    bucketId = "bucket-001",
                    deviceId = "device-001",
                    encryptedPayload = "base64data==",
                    prevHash = "genesis",
                    currentHash = "hash1",
                    keyEpoch = 1,
                    serverTimestamp = "2026-03-01T10:00:00Z"
                ),
                OpResponse(
                    globalSequence = 2,
                    bucketId = "bucket-001",
                    deviceId = "device-002",
                    encryptedPayload = "moredata==",
                    prevHash = "hash1",
                    currentHash = "hash2",
                    keyEpoch = 1,
                    serverTimestamp = "2026-03-01T10:01:00Z"
                )
            ),
            hasMore = true,
            latestSequence = 100
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PullOpsResponse>(encoded)

        decoded.ops.size shouldBe 2
        decoded.ops[0].globalSequence shouldBe 1
        decoded.ops[0].deviceId shouldBe "device-001"
        decoded.ops[1].globalSequence shouldBe 2
        decoded.hasMore shouldBe true
        decoded.latestSequence shouldBe 100
    }

    test("PullOpsResponse with empty ops list") {
        val original = PullOpsResponse(ops = emptyList(), hasMore = false, latestSequence = 0)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<PullOpsResponse>(encoded)

        decoded.ops shouldBe emptyList()
        decoded.hasMore shouldBe false
        decoded.latestSequence shouldBe 0
    }

    test("PullOpsResponse ignores unknown keys") {
        val jsonStr = """{"ops":[],"hasMore":false,"latestSequence":5,"extraField":"ignored"}"""
        val decoded = json.decodeFromString<PullOpsResponse>(jsonStr)
        decoded.latestSequence shouldBe 5
    }

    // ── AcceptedOp ────────────────────────────────────────────────────────────

    test("AcceptedOp roundtrip preserves all fields") {
        val original = AcceptedOp(index = 3, globalSequence = 42, serverTimestamp = "2026-03-01T12:00:00Z")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<AcceptedOp>(encoded)

        decoded.index shouldBe 3
        decoded.globalSequence shouldBe 42
        decoded.serverTimestamp shouldBe "2026-03-01T12:00:00Z"
    }

    // ── OpsBatchRequest / OpsBatchResponse ────────────────────────────────────

    test("OpsBatchRequest roundtrip") {
        val original = OpsBatchRequest(
            ops = listOf(
                OpInputDto(
                    deviceId = "device-abc",
                    keyEpoch = 1,
                    encryptedPayload = "enc-payload",
                    prevHash = "prev",
                    currentHash = "curr"
                )
            )
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<OpsBatchRequest>(encoded)

        decoded.ops.size shouldBe 1
        decoded.ops[0].deviceId shouldBe "device-abc"
        decoded.ops[0].keyEpoch shouldBe 1
    }

    test("OpsBatchResponse roundtrip") {
        val original = OpsBatchResponse(
            accepted = listOf(
                AcceptedOp(0, 10, "2026-03-01T10:00:00Z"),
                AcceptedOp(1, 11, "2026-03-01T10:01:00Z")
            ),
            latestSequence = 11
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<OpsBatchResponse>(encoded)

        decoded.accepted.size shouldBe 2
        decoded.latestSequence shouldBe 11
    }

    // ── BucketDevicesResponse ─────────────────────────────────────────────────

    test("BucketDevicesResponse roundtrip with multiple devices") {
        val original = BucketDevicesResponse(
            devices = listOf(
                DeviceInfo("d1", "sk1", "ek1", "2026-01-01T00:00:00Z"),
                DeviceInfo("d2", "sk2", "ek2", "2026-01-02T00:00:00Z")
            )
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<BucketDevicesResponse>(encoded)

        decoded.devices.size shouldBe 2
        decoded.devices[0].deviceId shouldBe "d1"
        decoded.devices[0].signingKey shouldBe "sk1"
        decoded.devices[1].grantedAt shouldBe "2026-01-02T00:00:00Z"
    }

    test("BucketDevicesResponse with empty devices") {
        val original = BucketDevicesResponse(devices = emptyList())
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<BucketDevicesResponse>(encoded)
        decoded.devices shouldBe emptyList()
    }

    // ── QrPairingPayload ──────────────────────────────────────────────────────

    test("QrPairingPayload roundtrip with all fields") {
        val original = QrPairingPayload(
            v = 1,
            s = "https://api.kidsync.dev",
            b = "bucket-qr-001",
            t = "invite-token-abc",
            f = "fp:sha256:abc123"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<QrPairingPayload>(encoded)

        decoded.v shouldBe 1
        decoded.s shouldBe "https://api.kidsync.dev"
        decoded.b shouldBe "bucket-qr-001"
        decoded.t shouldBe "invite-token-abc"
        decoded.f shouldBe "fp:sha256:abc123"
    }

    test("QrPairingPayload uses compact field names for small QR codes") {
        val payload = QrPairingPayload(v = 1, s = "https://s.co", b = "b1", t = "t1", f = "f1")
        val encoded = json.encodeToString(payload)

        // Should use short keys: v, s, b, t, f - not verbose names
        encoded shouldContain "\"v\":"
        encoded shouldContain "\"s\":"
        encoded shouldContain "\"b\":"
        encoded shouldContain "\"t\":"
        encoded shouldContain "\"f\":"
        encoded shouldNotContain "serverUrl"
        encoded shouldNotContain "bucketId"
        encoded shouldNotContain "inviteToken"
    }

    test("QrPairingPayload default version is 1") {
        val payload = QrPairingPayload(s = "url", b = "b", t = "t", f = "f")
        payload.v shouldBe 1
    }

    // ── DecryptedPayload ──────────────────────────────────────────────────────

    test("DecryptedPayload roundtrip with nested data") {
        val data = buildJsonObject {
            put("childId", "child-001")
            put("title", "Doctor visit")
            put("nested", buildJsonObject {
                put("key", "value")
            })
        }
        val original = DecryptedPayload(
            deviceSequence = 5,
            entityType = "CalendarEvent",
            entityId = "evt-001",
            operation = "CREATE",
            clientTimestamp = "2026-03-01T10:00:00Z",
            protocolVersion = 2,
            data = data
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<DecryptedPayload>(encoded)

        decoded.deviceSequence shouldBe 5
        decoded.entityType shouldBe "CalendarEvent"
        decoded.entityId shouldBe "evt-001"
        decoded.operation shouldBe "CREATE"
        decoded.protocolVersion shouldBe 2
        decoded.data["childId"]?.jsonPrimitive?.content shouldBe "child-001"
        decoded.data["nested"]?.jsonObject?.get("key")?.jsonPrimitive?.content shouldBe "value"
    }

    test("DecryptedPayload with empty data object") {
        val original = DecryptedPayload(
            deviceSequence = 1,
            entityType = "Expense",
            entityId = "exp-001",
            operation = "DELETE",
            clientTimestamp = "2026-03-01T12:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {}
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<DecryptedPayload>(encoded)

        decoded.data.size shouldBe 0
    }

    // ── Registration DTOs ─────────────────────────────────────────────────────

    test("RegisterRequest roundtrip") {
        val original = RegisterRequest(signingKey = "sk-base64", encryptionKey = "ek-base64")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<RegisterRequest>(encoded)

        decoded.signingKey shouldBe "sk-base64"
        decoded.encryptionKey shouldBe "ek-base64"
    }

    test("RegisterResponse roundtrip") {
        val original = RegisterResponse(deviceId = "device-new-001")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<RegisterResponse>(encoded)
        decoded.deviceId shouldBe "device-new-001"
    }

    // ── Challenge-Response DTOs ───────────────────────────────────────────────

    test("ChallengeResponse roundtrip") {
        val original = ChallengeResponse(nonce = "random-nonce-base64", expiresAt = "2026-03-01T10:05:00Z")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ChallengeResponse>(encoded)
        decoded.nonce shouldBe "random-nonce-base64"
        decoded.expiresAt shouldBe "2026-03-01T10:05:00Z"
    }

    test("VerifyResponse roundtrip") {
        val original = VerifyResponse(sessionToken = "jwt-token-here", expiresIn = 3600)
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<VerifyResponse>(encoded)
        decoded.sessionToken shouldBe "jwt-token-here"
        decoded.expiresIn shouldBe 3600
    }

    // ── ErrorResponse ─────────────────────────────────────────────────────────

    test("ErrorResponse with details") {
        val original = ErrorResponse(
            error = "VALIDATION_ERROR",
            message = "Invalid payload",
            details = mapOf("field" to "encryptedPayload", "reason" to "not base64")
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ErrorResponse>(encoded)

        decoded.error shouldBe "VALIDATION_ERROR"
        decoded.message shouldBe "Invalid payload"
        decoded.details shouldNotBe null
        decoded.details!!["field"] shouldBe "encryptedPayload"
    }

    test("ErrorResponse without details (null)") {
        val original = ErrorResponse(error = "NOT_FOUND", message = "Bucket not found")
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<ErrorResponse>(encoded)

        decoded.details shouldBe null
    }

    test("ErrorResponse deserialized from JSON without details key") {
        val jsonStr = """{"error":"AUTH_FAILED","message":"Invalid signature"}"""
        val decoded = json.decodeFromString<ErrorResponse>(jsonStr)
        decoded.error shouldBe "AUTH_FAILED"
        decoded.details shouldBe null
    }

    // ── CheckpointResponse ────────────────────────────────────────────────────

    test("CheckpointResponse with all fields") {
        val original = CheckpointResponse(
            startSequence = 1,
            endSequence = 100,
            hash = "sha256:checkpoint-hash",
            opCount = 100,
            timestamp = "2026-03-01T12:00:00Z",
            nextCheckpointAt = "2026-03-02T12:00:00Z"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<CheckpointResponse>(encoded)

        decoded.startSequence shouldBe 1
        decoded.endSequence shouldBe 100
        decoded.opCount shouldBe 100
        decoded.timestamp shouldBe "2026-03-01T12:00:00Z"
        decoded.nextCheckpointAt shouldBe "2026-03-02T12:00:00Z"
    }

    test("CheckpointResponse with null optional fields") {
        val original = CheckpointResponse(
            startSequence = 50, endSequence = 75, hash = "hash", opCount = 25
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<CheckpointResponse>(encoded)

        decoded.timestamp shouldBe null
        decoded.nextCheckpointAt shouldBe null
    }

    // ── WrappedKeyResponse ────────────────────────────────────────────────────

    test("WrappedKeyResponse with crossSignature") {
        val original = WrappedKeyResponse(
            wrappedDek = "wrapped-dek-base64",
            keyEpoch = 3,
            wrappedBy = "device-sender",
            crossSignature = "sig-base64"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<WrappedKeyResponse>(encoded)

        decoded.wrappedDek shouldBe "wrapped-dek-base64"
        decoded.keyEpoch shouldBe 3
        decoded.wrappedBy shouldBe "device-sender"
        decoded.crossSignature shouldBe "sig-base64"
    }

    test("WrappedKeyResponse without crossSignature") {
        val original = WrappedKeyResponse(
            wrappedDek = "dek", keyEpoch = 1, wrappedBy = "sender"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<WrappedKeyResponse>(encoded)
        decoded.crossSignature shouldBe null
    }

    // ── RecoveryBlobResponse ──────────────────────────────────────────────────

    test("RecoveryBlobResponse roundtrip") {
        val original = RecoveryBlobResponse(
            encryptedBlob = "encrypted-blob-data",
            createdAt = "2026-03-01T10:00:00Z"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<RecoveryBlobResponse>(encoded)
        decoded.encryptedBlob shouldBe "encrypted-blob-data"
        decoded.createdAt shouldBe "2026-03-01T10:00:00Z"
    }

    // ── LatestSnapshotResponse ────────────────────────────────────────────────

    test("LatestSnapshotResponse roundtrip with all fields") {
        val original = LatestSnapshotResponse(
            snapshotId = "snap-001",
            atSequence = 500,
            keyEpoch = 2,
            sizeBytes = 1048576,
            sha256Hash = "abc123def456",
            signature = "sig-base64",
            downloadUrl = "https://storage.example.com/snap-001",
            createdAt = "2026-03-01T14:00:00Z"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<LatestSnapshotResponse>(encoded)

        decoded.snapshotId shouldBe "snap-001"
        decoded.atSequence shouldBe 500
        decoded.keyEpoch shouldBe 2
        decoded.sizeBytes shouldBe 1048576
        decoded.sha256Hash shouldBe "abc123def456"
        decoded.signature shouldBe "sig-base64"
        decoded.downloadUrl shouldBe "https://storage.example.com/snap-001"
        decoded.createdAt shouldBe "2026-03-01T14:00:00Z"
    }

    // ── Blob DTOs ─────────────────────────────────────────────────────────────

    test("UploadBlobResponse roundtrip") {
        val original = UploadBlobResponse(
            blobId = "blob-001",
            sizeBytes = 2048,
            sha256Hash = "hash123"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<UploadBlobResponse>(encoded)

        decoded.blobId shouldBe "blob-001"
        decoded.sizeBytes shouldBe 2048
        decoded.sha256Hash shouldBe "hash123"
    }

    // ── Attestation DTOs ──────────────────────────────────────────────────────

    test("AttestationResponse roundtrip") {
        val original = AttestationResponse(
            signerDeviceId = "signer-001",
            attestedDeviceId = "attested-002",
            attestedKey = "key-base64",
            signature = "sig-base64",
            createdAt = "2026-03-01T10:00:00Z"
        )
        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString<AttestationResponse>(encoded)

        decoded.signerDeviceId shouldBe "signer-001"
        decoded.attestedDeviceId shouldBe "attested-002"
        decoded.attestedKey shouldBe "key-base64"
    }

    // ── Cross-DTO consistency ─────────────────────────────────────────────────

    test("OpResponse and OpInputDto share the same field semantics") {
        val input = OpInputDto(
            deviceId = "d1",
            keyEpoch = 1,
            encryptedPayload = "enc",
            prevHash = "prev",
            currentHash = "curr"
        )
        val response = OpResponse(
            globalSequence = 10,
            bucketId = "b1",
            deviceId = "d1",
            encryptedPayload = "enc",
            prevHash = "prev",
            currentHash = "curr",
            keyEpoch = 1,
            serverTimestamp = "2026-03-01T10:00:00Z"
        )

        input.deviceId shouldBe response.deviceId
        input.keyEpoch shouldBe response.keyEpoch
        input.encryptedPayload shouldBe response.encryptedPayload
        input.prevHash shouldBe response.prevHash
        input.currentHash shouldBe response.currentHash
    }
})
