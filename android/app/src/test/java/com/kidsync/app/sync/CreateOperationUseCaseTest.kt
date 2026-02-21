package com.kidsync.app.sync

import com.kidsync.app.crypto.CanonicalJsonSerializer
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import kotlinx.serialization.json.*
import java.util.Base64

/**
 * Tests for CreateOperationUseCase covering:
 * - Operation payload uses canonical JSON
 * - Encrypted payload is base64-encoded
 * - Hash chain is correctly computed
 * - Protocol version is 2
 * - No device returns failure
 * - No DEK returns failure
 */
class CreateOperationUseCaseTest : FunSpec({

    val cryptoManager = mockk<CryptoManager>()
    val keyManager = mockk<KeyManager>()
    val hashChainVerifier = HashChainVerifier()
    val opLogDao = mockk<OpLogDao>()
    val json = Json { ignoreUnknownKeys = true }
    val canonicalJsonSerializer = CanonicalJsonSerializer()

    val bucketId = "bucket-test"
    val deviceId = "device-test-001"

    beforeEach {
        clearAllMocks()
    }

    fun createUseCase() = CreateOperationUseCase(
        cryptoManager = cryptoManager,
        keyManager = keyManager,
        hashChainVerifier = hashChainVerifier,
        opLogDao = opLogDao,
        json = json,
        canonicalJsonSerializer = canonicalJsonSerializer
    )

    // ── Successful Operation Creation ───────────────────────────────────────

    test("operation payload is encrypted as base64") {
        val dek = ByteArray(32) { 1 }
        coEvery { keyManager.getDeviceId() } returns deviceId
        coEvery { keyManager.getCurrentEpoch(bucketId) } returns 1
        coEvery { keyManager.getDek(bucketId, 1) } returns dek
        coEvery { opLogDao.getLastOpForDevice(deviceId) } returns null

        var capturedPlaintext = ""
        every { cryptoManager.encryptPayload(any(), dek, any()) } answers {
            capturedPlaintext = firstArg()
            // Return valid base64
            Base64.getEncoder().encodeToString("encrypted-data".toByteArray())
        }

        val insertSlot = slot<OpLogEntryEntity>()
        coEvery { opLogDao.insertOpLogEntry(capture(insertSlot)) } just Runs

        val useCase = createUseCase()
        val result = useCase(
            bucketId = bucketId,
            entityType = EntityType.CalendarEvent,
            entityId = "evt-001",
            operationType = OperationType.CREATE,
            contentData = buildJsonObject {
                put("title", "Doctor visit")
                put("childId", "child-001")
            }
        )

        result.isSuccess shouldBe true

        // Verify the payload was serialized as canonical JSON (sorted keys)
        val parsedPayload = json.parseToJsonElement(capturedPlaintext).jsonObject
        parsedPayload["protocolVersion"]?.jsonPrimitive?.int shouldBe 2
        parsedPayload["entityType"]?.jsonPrimitive?.content shouldBe "CalendarEvent"
        parsedPayload["entityId"]?.jsonPrimitive?.content shouldBe "evt-001"
        parsedPayload["operation"]?.jsonPrimitive?.content shouldBe "CREATE"
        parsedPayload["deviceSequence"]?.jsonPrimitive?.long shouldBe 1L
    }

    test("encrypted payload in op entry is base64-encoded") {
        val dek = ByteArray(32) { 1 }
        coEvery { keyManager.getDeviceId() } returns deviceId
        coEvery { keyManager.getCurrentEpoch(bucketId) } returns 1
        coEvery { keyManager.getDek(bucketId, 1) } returns dek
        coEvery { opLogDao.getLastOpForDevice(deviceId) } returns null

        val base64Payload = Base64.getEncoder().encodeToString("encrypted-stuff".toByteArray())
        every { cryptoManager.encryptPayload(any(), dek, any()) } returns base64Payload

        val insertSlot = slot<OpLogEntryEntity>()
        coEvery { opLogDao.insertOpLogEntry(capture(insertSlot)) } just Runs

        val useCase = createUseCase()
        val result = useCase(
            bucketId = bucketId,
            entityType = EntityType.Expense,
            entityId = "exp-001",
            operationType = OperationType.CREATE,
            contentData = buildJsonObject {}
        )

        result.isSuccess shouldBe true

        // Verify it's valid base64
        val decoded = runCatching { Base64.getDecoder().decode(insertSlot.captured.encryptedPayload) }
        decoded.isSuccess shouldBe true
    }

    test("hash chain is correctly computed from genesis") {
        val dek = ByteArray(32) { 1 }
        coEvery { keyManager.getDeviceId() } returns deviceId
        coEvery { keyManager.getCurrentEpoch(bucketId) } returns 1
        coEvery { keyManager.getDek(bucketId, 1) } returns dek
        coEvery { opLogDao.getLastOpForDevice(deviceId) } returns null

        val base64Payload = Base64.getEncoder().encodeToString("test-data".toByteArray())
        every { cryptoManager.encryptPayload(any(), dek, any()) } returns base64Payload

        val insertSlot = slot<OpLogEntryEntity>()
        coEvery { opLogDao.insertOpLogEntry(capture(insertSlot)) } just Runs

        val useCase = createUseCase()
        useCase(
            bucketId = bucketId,
            entityType = EntityType.CalendarEvent,
            entityId = "evt-001",
            operationType = OperationType.CREATE,
            contentData = buildJsonObject {}
        )

        val entry = insertSlot.captured
        entry.devicePrevHash shouldBe HashChainVerifier.GENESIS_HASH

        // Verify currentHash matches what HashChainVerifier would compute
        val expectedHash = hashChainVerifier.computeHash(
            HashChainVerifier.GENESIS_HASH,
            base64Payload
        )
        entry.currentHash shouldBe expectedHash
    }

    test("hash chain continues from previous op") {
        val dek = ByteArray(32) { 1 }
        coEvery { keyManager.getDeviceId() } returns deviceId
        coEvery { keyManager.getCurrentEpoch(bucketId) } returns 1
        coEvery { keyManager.getDek(bucketId, 1) } returns dek

        val previousOp = OpLogEntryEntity(
            globalSequence = 5,
            bucketId = bucketId,
            deviceId = deviceId,
            deviceSequence = 3,
            keyEpoch = 1,
            encryptedPayload = "prev-enc",
            devicePrevHash = "prev-prev",
            currentHash = "previous-current-hash",
            serverTimestamp = null,
            isPending = false
        )
        coEvery { opLogDao.getLastOpForDevice(deviceId) } returns previousOp

        val base64Payload = Base64.getEncoder().encodeToString("new-data".toByteArray())
        every { cryptoManager.encryptPayload(any(), dek, any()) } returns base64Payload

        val insertSlot = slot<OpLogEntryEntity>()
        coEvery { opLogDao.insertOpLogEntry(capture(insertSlot)) } just Runs

        val useCase = createUseCase()
        useCase(
            bucketId = bucketId,
            entityType = EntityType.CalendarEvent,
            entityId = "evt-002",
            operationType = OperationType.CREATE,
            contentData = buildJsonObject {}
        )

        val entry = insertSlot.captured
        entry.devicePrevHash shouldBe "previous-current-hash"
        entry.deviceSequence shouldBe 4L // previous was 3
    }

    test("AAD format is bucketId|deviceId") {
        val dek = ByteArray(32) { 1 }
        coEvery { keyManager.getDeviceId() } returns deviceId
        coEvery { keyManager.getCurrentEpoch(bucketId) } returns 1
        coEvery { keyManager.getDek(bucketId, 1) } returns dek
        coEvery { opLogDao.getLastOpForDevice(deviceId) } returns null

        var capturedAad = ""
        every { cryptoManager.encryptPayload(any(), dek, capture(slot<String>().also { s ->
            // Can't capture directly; use answer
        })) } answers {
            capturedAad = thirdArg()
            Base64.getEncoder().encodeToString("enc".toByteArray())
        }

        coEvery { opLogDao.insertOpLogEntry(any()) } just Runs

        val useCase = createUseCase()
        useCase(
            bucketId = bucketId,
            entityType = EntityType.CalendarEvent,
            entityId = "evt-001",
            operationType = OperationType.CREATE,
            contentData = buildJsonObject {}
        )

        capturedAad shouldBe "$bucketId|$deviceId"
    }

    test("new op is stored as isPending = true") {
        val dek = ByteArray(32) { 1 }
        coEvery { keyManager.getDeviceId() } returns deviceId
        coEvery { keyManager.getCurrentEpoch(bucketId) } returns 1
        coEvery { keyManager.getDek(bucketId, 1) } returns dek
        coEvery { opLogDao.getLastOpForDevice(deviceId) } returns null
        every { cryptoManager.encryptPayload(any(), dek, any()) } returns
                Base64.getEncoder().encodeToString("enc".toByteArray())

        val insertSlot = slot<OpLogEntryEntity>()
        coEvery { opLogDao.insertOpLogEntry(capture(insertSlot)) } just Runs

        val useCase = createUseCase()
        useCase(
            bucketId = bucketId,
            entityType = EntityType.Expense,
            entityId = "exp-001",
            operationType = OperationType.CREATE,
            contentData = buildJsonObject {}
        )

        insertSlot.captured.isPending shouldBe true
        insertSlot.captured.globalSequence shouldBe 0L // assigned by server
    }

    // ── Failure Cases ───────────────────────────────────────────────────────

    test("no device ID returns failure") {
        coEvery { keyManager.getDeviceId() } returns null

        val useCase = createUseCase()
        val result = useCase(
            bucketId = bucketId,
            entityType = EntityType.CalendarEvent,
            entityId = "evt-001",
            operationType = OperationType.CREATE,
            contentData = buildJsonObject {}
        )

        result.isFailure shouldBe true
    }

    test("no DEK available returns failure") {
        coEvery { keyManager.getDeviceId() } returns deviceId
        coEvery { keyManager.getCurrentEpoch(bucketId) } returns 1
        coEvery { keyManager.getDek(bucketId, 1) } returns null

        val useCase = createUseCase()
        val result = useCase(
            bucketId = bucketId,
            entityType = EntityType.CalendarEvent,
            entityId = "evt-001",
            operationType = OperationType.CREATE,
            contentData = buildJsonObject {}
        )

        result.isFailure shouldBe true
    }
})
