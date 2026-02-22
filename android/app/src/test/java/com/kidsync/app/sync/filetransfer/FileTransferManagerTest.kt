package com.kidsync.app.sync.filetransfer

import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.crypto.KeyManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FileTransferManagerTest : FunSpec({

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val testBucketId = "bucket-test-1234-5678-abcdefabcdef"
    val testDeviceId = "device-test-aaaa-bbbb-ccccddddeeee"

    fun createTestOps(count: Int): List<OpLogEntryEntity> = (1..count).map { i ->
        OpLogEntryEntity(
            id = i.toLong(),
            globalSequence = i.toLong(),
            bucketId = testBucketId,
            deviceId = testDeviceId,
            deviceSequence = i.toLong(),
            keyEpoch = 1,
            encryptedPayload = "dGVzdC1wYXlsb2FkLSR7aX0=", // base64 "test-payload-${i}"
            devicePrevHash = if (i == 1) "0".repeat(64)
                else "hash-${i - 1}".padEnd(64, '0'),
            currentHash = "hash-$i".padEnd(64, '0'),
            serverTimestamp = "2026-01-01T00:00:0${i}Z",
            isPending = false
        )
    }

    fun createMockDeps(
        ops: List<OpLogEntryEntity> = emptyList()
    ): Pair<OpLogDao, KeyManager> {
        val opLogDao = mockk<OpLogDao>(relaxed = true)
        val keyManager = mockk<KeyManager>(relaxed = true)

        coEvery { keyManager.getDeviceId() } returns testDeviceId
        coEvery { opLogDao.getAllOpsForBucket(testBucketId) } returns ops
        coEvery { opLogDao.getOpsCountForBucket(testBucketId) } returns ops.size.toLong()
        coEvery { opLogDao.getLastOpForBucket(testBucketId) } returns ops.lastOrNull()

        return Pair(opLogDao, keyManager)
    }

    fun buildZipBundle(manifest: ExportManifest, opsJsonl: String): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zipOut ->
            zipOut.putNextEntry(ZipEntry(FileTransferManager.MANIFEST_FILENAME))
            zipOut.write(json.encodeToString(ExportManifest.serializer(), manifest).toByteArray())
            zipOut.closeEntry()

            zipOut.putNextEntry(ZipEntry(FileTransferManager.OPS_FILENAME))
            zipOut.write(opsJsonl.toByteArray())
            zipOut.closeEntry()
        }
        return baos.toByteArray()
    }

    test("export creates valid ZIP with manifest.json and ops.jsonl") {
        val ops = createTestOps(3)
        val (opLogDao, keyManager) = createMockDeps(ops)
        val manager = FileTransferManager(opLogDao, keyManager)

        val outputStream = ByteArrayOutputStream()
        val result = manager.exportBucket(testBucketId, outputStream)

        result.isSuccess shouldBe true
        val manifest = result.getOrThrow()
        manifest.formatVersion shouldBe 1
        manifest.bucketId shouldBe testBucketId
        manifest.deviceId shouldBe testDeviceId
        manifest.opCount shouldBe 3L
        manifest.hashChainTip shouldBe ops.last().currentHash

        // Verify ZIP contents
        val zipBytes = outputStream.toByteArray()
        zipBytes.size.toLong() shouldBeGreaterThan 0L

        val zipIn = ZipInputStream(ByteArrayInputStream(zipBytes))
        val entries = mutableMapOf<String, String>()
        var entry = zipIn.nextEntry
        while (entry != null) {
            entries[entry.name] = zipIn.readBytes().toString(Charsets.UTF_8)
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        zipIn.close()

        entries.containsKey("manifest.json") shouldBe true
        entries.containsKey("ops.jsonl") shouldBe true

        // Verify manifest JSON is parseable
        val parsedManifest = json.decodeFromString<ExportManifest>(entries["manifest.json"]!!)
        parsedManifest.opCount shouldBe 3L
        parsedManifest.bucketId shouldBe testBucketId

        // Verify ops.jsonl has 3 lines
        val opsLines = entries["ops.jsonl"]!!.trim().lines()
        opsLines.size shouldBe 3

        // Verify first op is parseable
        val firstOp = json.decodeFromString<ExportedOp>(opsLines[0])
        firstOp.globalSequence shouldBe 1L
        firstOp.deviceId shouldBe testDeviceId
    }

    test("import reads ZIP and returns correct ImportResult") {
        val (opLogDao, keyManager) = createMockDeps()
        // No existing ops - all should be new
        coEvery { opLogDao.findOp(any(), any(), any()) } returns null

        val manifest = ExportManifest(
            formatVersion = 1,
            exportedAt = "2026-01-01T00:00:00Z",
            bucketId = testBucketId,
            deviceId = testDeviceId,
            opCount = 2,
            hashChainTip = "hash-2".padEnd(64, '0')
        )

        val opsJsonl = buildString {
            appendLine(json.encodeToString(ExportedOp(1, testDeviceId, 1, 1, "payload1", "0".repeat(64), "hash1", "2026-01-01T00:00:01Z")))
            appendLine(json.encodeToString(ExportedOp(2, testDeviceId, 2, 1, "payload2", "hash1", "hash2", "2026-01-01T00:00:02Z")))
        }

        val zipBytes = buildZipBundle(manifest, opsJsonl)
        val manager = FileTransferManager(opLogDao, keyManager)

        val result = manager.importBundle(ByteArrayInputStream(zipBytes))

        result.isSuccess shouldBe true
        val importResult = result.getOrThrow()
        importResult.bucketId shouldBe testBucketId
        importResult.totalOps shouldBe 2L
        importResult.newOps shouldBe 2L
        importResult.skippedDuplicates shouldBe 0L

        coVerify(exactly = 2) { opLogDao.insertOpLogEntry(any()) }
    }

    test("roundtrip: export then import produces same data") {
        val ops = createTestOps(5)
        val (opLogDao, keyManager) = createMockDeps(ops)
        val manager = FileTransferManager(opLogDao, keyManager)

        // Export
        val exportStream = ByteArrayOutputStream()
        val exportResult = manager.exportBucket(testBucketId, exportStream)
        exportResult.isSuccess shouldBe true

        // Import (all ops should be new since findOp returns null)
        coEvery { opLogDao.findOp(any(), any(), any()) } returns null
        val capturedEntities = mutableListOf<OpLogEntryEntity>()
        coEvery { opLogDao.insertOpLogEntry(capture(capturedEntities)) } returns Unit

        val importResult = manager.importBundle(ByteArrayInputStream(exportStream.toByteArray()))

        importResult.isSuccess shouldBe true
        val result = importResult.getOrThrow()
        result.totalOps shouldBe 5L
        result.newOps shouldBe 5L
        result.skippedDuplicates shouldBe 0L

        // Verify all ops were imported with correct data
        capturedEntities.size shouldBe 5
        capturedEntities.forEachIndexed { index, entity ->
            val original = ops[index]
            entity.globalSequence shouldBe original.globalSequence
            entity.deviceId shouldBe original.deviceId
            entity.deviceSequence shouldBe original.deviceSequence
            entity.keyEpoch shouldBe original.keyEpoch
            entity.encryptedPayload shouldBe original.encryptedPayload
            entity.devicePrevHash shouldBe original.devicePrevHash
            entity.currentHash shouldBe original.currentHash
            entity.serverTimestamp shouldBe original.serverTimestamp
            entity.bucketId shouldBe testBucketId
            entity.isPending shouldBe false
        }
    }

    test("import skips duplicates") {
        val (opLogDao, keyManager) = createMockDeps()

        // First op exists, second does not
        val existingEntity = OpLogEntryEntity(
            id = 1,
            globalSequence = 1,
            bucketId = testBucketId,
            deviceId = testDeviceId,
            deviceSequence = 1,
            keyEpoch = 1,
            encryptedPayload = "payload1",
            devicePrevHash = "0".repeat(64),
            currentHash = "hash1",
            serverTimestamp = "2026-01-01T00:00:01Z"
        )
        coEvery { opLogDao.findOp(testBucketId, testDeviceId, 1L) } returns existingEntity
        coEvery { opLogDao.findOp(testBucketId, testDeviceId, 2L) } returns null

        val manifest = ExportManifest(
            formatVersion = 1,
            exportedAt = "2026-01-01T00:00:00Z",
            bucketId = testBucketId,
            deviceId = testDeviceId,
            opCount = 2,
            hashChainTip = "hash2"
        )

        val opsJsonl = buildString {
            appendLine(json.encodeToString(ExportedOp(1, testDeviceId, 1, 1, "payload1", "0".repeat(64), "hash1", "2026-01-01T00:00:01Z")))
            appendLine(json.encodeToString(ExportedOp(2, testDeviceId, 2, 1, "payload2", "hash1", "hash2", "2026-01-01T00:00:02Z")))
        }

        val zipBytes = buildZipBundle(manifest, opsJsonl)
        val manager = FileTransferManager(opLogDao, keyManager)

        val result = manager.importBundle(ByteArrayInputStream(zipBytes))

        result.isSuccess shouldBe true
        val importResult = result.getOrThrow()
        importResult.totalOps shouldBe 2L
        importResult.newOps shouldBe 1L
        importResult.skippedDuplicates shouldBe 1L

        coVerify(exactly = 1) { opLogDao.insertOpLogEntry(any()) }
    }

    test("import with empty bundle (no ops)") {
        val (opLogDao, keyManager) = createMockDeps()

        val manifest = ExportManifest(
            formatVersion = 1,
            exportedAt = "2026-01-01T00:00:00Z",
            bucketId = testBucketId,
            deviceId = testDeviceId,
            opCount = 0,
            hashChainTip = ""
        )

        val zipBytes = buildZipBundle(manifest, "")
        val manager = FileTransferManager(opLogDao, keyManager)

        val result = manager.importBundle(ByteArrayInputStream(zipBytes))

        result.isSuccess shouldBe true
        val importResult = result.getOrThrow()
        importResult.totalOps shouldBe 0L
        importResult.newOps shouldBe 0L
        importResult.skippedDuplicates shouldBe 0L

        coVerify(exactly = 0) { opLogDao.insertOpLogEntry(any()) }
    }

    test("import rejects unknown formatVersion") {
        val (opLogDao, keyManager) = createMockDeps()

        val manifest = ExportManifest(
            formatVersion = 99,
            exportedAt = "2026-01-01T00:00:00Z",
            bucketId = testBucketId,
            deviceId = testDeviceId,
            opCount = 0,
            hashChainTip = ""
        )

        val zipBytes = buildZipBundle(manifest, "")
        val manager = FileTransferManager(opLogDao, keyManager)

        val result = manager.importBundle(ByteArrayInputStream(zipBytes))

        result.isFailure shouldBe true
        val error = result.exceptionOrNull()
        error shouldNotBe null
        error!!.message shouldContain "Unsupported format version: 99"
    }

    test("export with empty bucket produces valid ZIP with zero ops") {
        val (opLogDao, keyManager) = createMockDeps(emptyList())
        val manager = FileTransferManager(opLogDao, keyManager)

        val outputStream = ByteArrayOutputStream()
        val result = manager.exportBucket(testBucketId, outputStream)

        result.isSuccess shouldBe true
        val manifest = result.getOrThrow()
        manifest.opCount shouldBe 0L
        manifest.hashChainTip shouldBe ""

        // Verify ZIP is valid and contains both files
        val zipIn = ZipInputStream(ByteArrayInputStream(outputStream.toByteArray()))
        val entryNames = mutableListOf<String>()
        var entry = zipIn.nextEntry
        while (entry != null) {
            entryNames.add(entry.name)
            zipIn.closeEntry()
            entry = zipIn.nextEntry
        }
        zipIn.close()

        entryNames shouldBe listOf("manifest.json", "ops.jsonl")
    }

    test("export fails when device ID is not available") {
        val opLogDao = mockk<OpLogDao>(relaxed = true)
        val keyManager = mockk<KeyManager>(relaxed = true)
        coEvery { keyManager.getDeviceId() } returns null

        val manager = FileTransferManager(opLogDao, keyManager)
        val outputStream = ByteArrayOutputStream()

        val result = manager.exportBucket(testBucketId, outputStream)

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldContain "Device ID not available"
    }

    test("import fails when manifest.json is missing") {
        val (opLogDao, keyManager) = createMockDeps()

        // Build a ZIP with only ops.jsonl, no manifest
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zipOut ->
            zipOut.putNextEntry(ZipEntry(FileTransferManager.OPS_FILENAME))
            zipOut.write("{}".toByteArray())
            zipOut.closeEntry()
        }

        val manager = FileTransferManager(opLogDao, keyManager)
        val result = manager.importBundle(ByteArrayInputStream(baos.toByteArray()))

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldContain "missing manifest.json"
    }
})
