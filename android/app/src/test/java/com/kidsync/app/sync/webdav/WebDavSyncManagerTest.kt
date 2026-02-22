package com.kidsync.app.sync.webdav

import android.util.Log
import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.dao.SyncStateDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Instant

/**
 * Tests for [WebDavSyncManager].
 *
 * Uses OkHttp MockWebServer to verify WebDAV operations:
 * - Connection testing
 * - Directory creation (MKCOL)
 * - File upload (PUT) / download (GET)
 * - PROPFIND directory listing and response parsing
 * - Push/pull ops with Lamport timestamps
 * - Checkpoint upload/download
 * - Error handling (network errors, auth failures)
 */
class WebDavSyncManagerTest : FunSpec({

    val opLogDao = mockk<OpLogDao>(relaxed = true)
    val syncStateDao = mockk<SyncStateDao>(relaxed = true)
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    // Mock HashChainVerifier that always passes (test data uses dummy hashes)
    val hashChainVerifier = mockk<HashChainVerifier>().apply {
        every { verifyChains(any(), any()) } returns Result.success(Unit)
    }

    val bucketId = "bucket-aaaa-bbbb-cccc-dddddddddddd"
    val deviceId = "aaaaaaaa-1111-2222-3333-444444444444"

    lateinit var server: MockWebServer
    lateinit var manager: WebDavSyncManager

    beforeEach {
        clearAllMocks()

        // Mock android.util.Log which is unavailable in JVM unit tests.
        // The XmlPullParser fallback path in parsePropfindResponse calls Log.e().
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.d(any(), any<String>()) } returns 0

        // Re-stub after clearAllMocks
        every { hashChainVerifier.verifyChains(any(), any()) } returns Result.success(Unit)

        server = MockWebServer()
        server.start()

        manager = WebDavSyncManager(opLogDao, syncStateDao, json, hashChainVerifier)
        val config = WebDavConfig(
            serverUrl = server.url("/remote.php/dav/files/user/").toString(),
            username = "testuser",
            password = "testpass",
            basePath = "kidsync"
        )
        manager.configure(config)
    }

    afterEach {
        server.shutdown()
        unmockkStatic(Log::class)
    }

    test("configure sets up OkHttp client with basic auth") {
        // Enqueue a simple response to test auth header
        server.enqueue(MockResponse().setResponseCode(207).setBody("<multistatus/>"))

        val result = manager.testConnection()
        result.isSuccess shouldBe true

        // Verify the request had auth header
        val request = server.takeRequest()
        request.getHeader("Authorization").shouldNotBeNull()
        request.getHeader("Authorization")!!.startsWith("Basic ") shouldBe true
    }

    test("testConnection succeeds with 207 Multi-Status") {
        server.enqueue(MockResponse().setResponseCode(207).setBody("<multistatus/>"))

        val result = manager.testConnection()
        result.isSuccess shouldBe true
    }

    test("testConnection succeeds with 200 OK") {
        server.enqueue(MockResponse().setResponseCode(200).setBody("OK"))

        val result = manager.testConnection()
        result.isSuccess shouldBe true
    }

    test("testConnection fails with 401 Unauthorized") {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = manager.testConnection()
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<WebDavAuthException>()
    }

    test("testConnection fails with 403 Forbidden") {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = manager.testConnection()
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<WebDavAuthException>()
    }

    test("testConnection fails with 500 Server Error") {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = manager.testConnection()
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<WebDavException>()
    }

    test("ensureDirectoryStructure creates MKCOL requests for all directories") {
        // 5 MKCOL calls for: kidsync/, buckets/, {bucketId}/, ops/, meta/
        repeat(5) {
            server.enqueue(MockResponse().setResponseCode(201))
        }

        val result = manager.ensureDirectoryStructure(bucketId)
        result.isSuccess shouldBe true
        server.requestCount shouldBe 5

        // Verify all requests are MKCOL
        repeat(5) {
            val request = server.takeRequest()
            request.method shouldBe "MKCOL"
        }
    }

    test("ensureDirectoryStructure succeeds when directories already exist (405)") {
        repeat(5) {
            server.enqueue(MockResponse().setResponseCode(405)) // Already exists
        }

        val result = manager.ensureDirectoryStructure(bucketId)
        result.isSuccess shouldBe true
    }

    test("pushOps formats files correctly with Lamport timestamps") {
        val ops = listOf(
            OpLogEntry(
                globalSequence = 0,
                bucketId = bucketId,
                deviceId = deviceId,
                deviceSequence = 1,
                keyEpoch = 1,
                encryptedPayload = "dGVzdC1wYXlsb2Fk",
                devicePrevHash = "genesis",
                currentHash = "hash-1",
                serverTimestamp = null
            )
        )

        // GET lamport.txt returns 404 (first time)
        server.enqueue(MockResponse().setResponseCode(404))
        // PUT for the op file
        server.enqueue(MockResponse().setResponseCode(201))
        // PUT for lamport.txt
        server.enqueue(MockResponse().setResponseCode(201))

        val result = manager.pushOps(bucketId, ops)
        result.isSuccess shouldBe true
        result.getOrThrow() shouldBe 1

        // Verify GET for lamport.txt
        val lamportGet = server.takeRequest()
        lamportGet.method shouldBe "GET"
        lamportGet.path!!.contains("lamport.txt") shouldBe true

        // Verify PUT for op file
        val opPut = server.takeRequest()
        opPut.method shouldBe "PUT"
        val opBody = opPut.body.readUtf8()
        val parsedOp = json.decodeFromString<WebDavOp>(opBody)
        parsedOp.lamportTimestamp shouldBe 1L
        parsedOp.deviceId shouldBe deviceId
        parsedOp.encryptedPayload shouldBe "dGVzdC1wYXlsb2Fk"
        parsedOp.bucketId shouldBe bucketId

        // Verify PUT for lamport.txt
        val lamportPut = server.takeRequest()
        lamportPut.method shouldBe "PUT"
        lamportPut.body.readUtf8() shouldBe "1"
    }

    test("pushOps increments Lamport from existing value") {
        val ops = listOf(
            createTestOp(deviceSequence = 1),
            createTestOp(deviceSequence = 2)
        )

        // GET lamport.txt returns existing value
        server.enqueue(MockResponse().setResponseCode(200).setBody("42"))
        // PUT for op 1, op 2, lamport.txt
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))

        val result = manager.pushOps(bucketId, ops)
        result.isSuccess shouldBe true
        result.getOrThrow() shouldBe 2

        // Skip the GET
        server.takeRequest()

        // Check first op has lamport 43
        val req1 = server.takeRequest()
        val op1 = json.decodeFromString<WebDavOp>(req1.body.readUtf8())
        op1.lamportTimestamp shouldBe 43L

        // Check second op has lamport 44
        val req2 = server.takeRequest()
        val op2 = json.decodeFromString<WebDavOp>(req2.body.readUtf8())
        op2.lamportTimestamp shouldBe 44L

        // Check lamport.txt updated to 44
        val lamportReq = server.takeRequest()
        lamportReq.body.readUtf8() shouldBe "44"
    }

    test("pushOps with empty list returns 0") {
        val result = manager.pushOps(bucketId, emptyList())
        result.isSuccess shouldBe true
        result.getOrThrow() shouldBe 0
        server.requestCount shouldBe 0
    }

    test("pullOps parses PROPFIND response and downloads new ops") {
        val propfindResponse = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/remote.php/dav/files/user/kidsync/buckets/$bucketId/ops/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/kidsync/buckets/$bucketId/ops/5-$deviceId.json</d:href>
    <d:propstat>
      <d:prop><d:displayname>5-$deviceId.json</d:displayname></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/kidsync/buckets/$bucketId/ops/10-$deviceId.json</d:href>
    <d:propstat>
      <d:prop><d:displayname>10-$deviceId.json</d:displayname></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""

        val op10 = WebDavOp(
            globalSequence = 0,
            bucketId = bucketId,
            deviceId = deviceId,
            deviceSequence = 2,
            keyEpoch = 1,
            encryptedPayload = "enc-data-10",
            devicePrevHash = "prev-10",
            currentHash = "hash-10",
            lamportTimestamp = 10,
            serverTimestamp = null
        )

        // PROPFIND for directory listing
        server.enqueue(MockResponse().setResponseCode(207).setBody(propfindResponse))
        // GET for op 10 (op 5 is skipped because afterSequence=5 means > 5)
        server.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(WebDavOp.serializer(), op10)))

        val result = manager.pullOps(bucketId, afterSequence = 5)
        result.isSuccess shouldBe true

        val ops = result.getOrThrow()
        ops shouldHaveSize 1
        ops[0].deviceSequence shouldBe 2L
        ops[0].encryptedPayload shouldBe "enc-data-10"

        // Verify op was inserted into the database
        coVerify {
            opLogDao.insertOpLogEntry(match {
                it.globalSequence == 10L && it.encryptedPayload == "enc-data-10"
            })
        }

        // Verify sync state was updated
        coVerify {
            syncStateDao.upsertSyncState(match {
                it.bucketId == bucketId && it.lastGlobalSequence == 10L
            })
        }
    }

    test("pullOps with no new ops returns empty list") {
        val propfindResponse = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/remote.php/dav/files/user/kidsync/buckets/$bucketId/ops/</d:href>
    <d:propstat>
      <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/kidsync/buckets/$bucketId/ops/5-device.json</d:href>
    <d:propstat>
      <d:prop><d:displayname>5-device.json</d:displayname></d:prop>
      <d:status>HTTP/1.1 200 OK</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""

        server.enqueue(MockResponse().setResponseCode(207).setBody(propfindResponse))

        // afterSequence=10, so op 5 is skipped
        val result = manager.pullOps(bucketId, afterSequence = 10)
        result.isSuccess shouldBe true
        result.getOrThrow() shouldHaveSize 0
    }

    test("pullOps handles 404 empty directory") {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = manager.pullOps(bucketId, afterSequence = 0)
        result.isSuccess shouldBe true
        result.getOrThrow() shouldHaveSize 0
    }

    test("checkpoint upload and download roundtrip") {
        val checkpoint = WebDavCheckpoint(
            lastSequence = 42,
            lamportTimestamp = 42,
            updatedAt = "2026-02-22T10:00:00Z"
        )

        // PUT for upload
        server.enqueue(MockResponse().setResponseCode(201))

        val uploadResult = manager.uploadCheckpoint(bucketId, checkpoint)
        uploadResult.isSuccess shouldBe true

        // Verify PUT content
        val putRequest = server.takeRequest()
        putRequest.method shouldBe "PUT"
        val savedCheckpoint = json.decodeFromString<WebDavCheckpoint>(putRequest.body.readUtf8())
        savedCheckpoint.lastSequence shouldBe 42L
        savedCheckpoint.lamportTimestamp shouldBe 42L

        // GET for download
        server.enqueue(MockResponse().setResponseCode(200).setBody(json.encodeToString(WebDavCheckpoint.serializer(), checkpoint)))

        val downloaded = manager.downloadCheckpoint(bucketId)
        downloaded.shouldNotBeNull()
        downloaded.lastSequence shouldBe 42L
        downloaded.lamportTimestamp shouldBe 42L
        downloaded.updatedAt shouldBe "2026-02-22T10:00:00Z"
    }

    test("downloadCheckpoint returns null when no checkpoint exists") {
        server.enqueue(MockResponse().setResponseCode(404))

        val downloaded = manager.downloadCheckpoint(bucketId)
        downloaded.shouldBeNull()
    }

    test("readLamportTimestamp returns 0 when file does not exist") {
        server.enqueue(MockResponse().setResponseCode(404))

        val lamport = manager.readLamportTimestamp(bucketId)
        lamport shouldBe 0L
    }

    test("readLamportTimestamp returns parsed value") {
        server.enqueue(MockResponse().setResponseCode(200).setBody("99"))

        val lamport = manager.readLamportTimestamp(bucketId)
        lamport shouldBe 99L
    }

    test("parsePropfindResponse extracts filenames correctly") {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/remote.php/dav/files/user/kidsync/ops/</d:href>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/kidsync/ops/1-device1.json</d:href>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/kidsync/ops/2-device2.json</d:href>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/kidsync/ops/3-device1.json</d:href>
  </d:response>
</d:multistatus>"""

        val parentUrl = "http://example.com/remote.php/dav/files/user/kidsync/ops/"
        val result = manager.parsePropfindResponse(xml, parentUrl)

        result shouldContainExactly listOf("1-device1.json", "2-device2.json", "3-device1.json")
    }

    test("parsePropfindResponse handles empty directory") {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/remote.php/dav/files/user/kidsync/ops/</d:href>
  </d:response>
</d:multistatus>"""

        val parentUrl = "http://example.com/remote.php/dav/files/user/kidsync/ops/"
        val result = manager.parsePropfindResponse(xml, parentUrl)

        result shouldHaveSize 0
    }

    test("extractLamportFromFilename parses valid filenames") {
        manager.extractLamportFromFilename("1-device-id.json") shouldBe 1L
        manager.extractLamportFromFilename("42-aaaaaaaa-1111-2222-3333-444444444444.json") shouldBe 42L
        manager.extractLamportFromFilename("100-dev.json") shouldBe 100L
    }

    test("extractLamportFromFilename returns null for invalid filenames") {
        manager.extractLamportFromFilename("invalid.json").shouldBeNull()
        manager.extractLamportFromFilename("no-number.json").shouldBeNull()
        manager.extractLamportFromFilename(".json").shouldBeNull()
        manager.extractLamportFromFilename("abc-device.json").shouldBeNull()
    }

    test("Lamport timestamp increments correctly across multiple pushes") {
        // First push: lamport starts at 0
        server.enqueue(MockResponse().setResponseCode(404)) // GET lamport.txt
        server.enqueue(MockResponse().setResponseCode(201)) // PUT op
        server.enqueue(MockResponse().setResponseCode(201)) // PUT lamport.txt

        val ops1 = listOf(createTestOp(deviceSequence = 1))
        val result1 = manager.pushOps(bucketId, ops1)
        result1.isSuccess shouldBe true

        // Second push: lamport continues from 1
        server.enqueue(MockResponse().setResponseCode(200).setBody("1")) // GET lamport.txt
        server.enqueue(MockResponse().setResponseCode(201)) // PUT op
        server.enqueue(MockResponse().setResponseCode(201)) // PUT lamport.txt

        val ops2 = listOf(createTestOp(deviceSequence = 2))
        val result2 = manager.pushOps(bucketId, ops2)
        result2.isSuccess shouldBe true

        // Verify second push had lamport=2
        // Skip first 3 requests (from first push)
        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        // Second push requests
        server.takeRequest() // GET lamport.txt
        val op2Put = server.takeRequest() // PUT op
        val op2Body = json.decodeFromString<WebDavOp>(op2Put.body.readUtf8())
        op2Body.lamportTimestamp shouldBe 2L
    }

    test("getPendingOpsForPush converts entities to domain model") {
        val entities = listOf(
            OpLogEntryEntity(
                id = 1,
                globalSequence = 0,
                bucketId = bucketId,
                deviceId = deviceId,
                deviceSequence = 1,
                keyEpoch = 1,
                encryptedPayload = "encrypted-data",
                devicePrevHash = "genesis",
                currentHash = "hash-1",
                serverTimestamp = "2026-02-22T10:00:00Z",
                isPending = true
            )
        )

        coEvery { opLogDao.getPendingOps(bucketId) } returns entities

        val result = manager.getPendingOpsForPush(bucketId)
        result shouldHaveSize 1
        result[0].bucketId shouldBe bucketId
        result[0].deviceId shouldBe deviceId
        result[0].encryptedPayload shouldBe "encrypted-data"
        result[0].serverTimestamp shouldBe Instant.parse("2026-02-22T10:00:00Z")
    }

    test("error handling: push fails on server error") {
        val ops = listOf(createTestOp(deviceSequence = 1))

        // GET lamport.txt succeeds
        server.enqueue(MockResponse().setResponseCode(200).setBody("0"))
        // PUT fails with server error
        server.enqueue(MockResponse().setResponseCode(500))

        val result = manager.pushOps(bucketId, ops)
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<WebDavException>()
    }

    test("error handling: pull fails on server error during PROPFIND") {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = manager.pullOps(bucketId, afterSequence = 0)
        result.isFailure shouldBe true
    }

    test("WebDavOp.toOpLogEntry converts correctly") {
        val webDavOp = WebDavOp(
            globalSequence = 5,
            bucketId = bucketId,
            deviceId = deviceId,
            deviceSequence = 3,
            keyEpoch = 2,
            encryptedPayload = "payload-data",
            devicePrevHash = "prev",
            currentHash = "curr",
            lamportTimestamp = 10,
            serverTimestamp = "2026-02-22T12:00:00Z"
        )

        val entry = webDavOp.toOpLogEntry()
        // Uses lamportTimestamp as globalSequence
        entry.globalSequence shouldBe 10L
        entry.bucketId shouldBe bucketId
        entry.deviceId shouldBe deviceId
        entry.deviceSequence shouldBe 3L
        entry.keyEpoch shouldBe 2
        entry.encryptedPayload shouldBe "payload-data"
        entry.serverTimestamp shouldBe Instant.parse("2026-02-22T12:00:00Z")
    }

    test("WebDavOp.toOpLogEntry handles null serverTimestamp") {
        val webDavOp = WebDavOp(
            globalSequence = 5,
            bucketId = bucketId,
            deviceId = deviceId,
            deviceSequence = 1,
            keyEpoch = 1,
            encryptedPayload = "data",
            devicePrevHash = "prev",
            currentHash = "curr",
            lamportTimestamp = 5,
            serverTimestamp = null
        )

        val entry = webDavOp.toOpLogEntry()
        entry.serverTimestamp.shouldBeNull()
    }

    test("WebDavConfig.resolvedBaseUrl formats correctly") {
        val config1 = WebDavConfig(
            serverUrl = "https://cloud.example.com/remote.php/dav/files/user/",
            username = "u", password = "p"
        )
        config1.resolvedBaseUrl() shouldBe "https://cloud.example.com/remote.php/dav/files/user/kidsync/"

        val config2 = WebDavConfig(
            serverUrl = "https://cloud.example.com/remote.php/dav/files/user",
            username = "u", password = "p", basePath = "myapp"
        )
        config2.resolvedBaseUrl() shouldBe "https://cloud.example.com/remote.php/dav/files/user/myapp/"
    }

    test("unconfigured manager throws IllegalStateException") {
        val unconfigured = WebDavSyncManager(opLogDao, syncStateDao, json, hashChainVerifier)
        val result = unconfigured.testConnection()
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
    }
}) {
    companion object {
        private const val bucketId = "bucket-aaaa-bbbb-cccc-dddddddddddd"
        private const val deviceId = "aaaaaaaa-1111-2222-3333-444444444444"

        fun createTestOp(deviceSequence: Long) = OpLogEntry(
            globalSequence = 0,
            bucketId = bucketId,
            deviceId = deviceId,
            deviceSequence = deviceSequence,
            keyEpoch = 1,
            encryptedPayload = "enc-$deviceSequence",
            devicePrevHash = "prev-$deviceSequence",
            currentHash = "hash-$deviceSequence",
            serverTimestamp = null
        )
    }
}
