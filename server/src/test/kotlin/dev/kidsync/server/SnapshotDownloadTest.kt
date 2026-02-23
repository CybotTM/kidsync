package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.uploadOpsChain
import dev.kidsync.server.models.BucketResponse
import dev.kidsync.server.models.CreateBucketRequest
import dev.kidsync.server.models.SnapshotMetadata
import dev.kidsync.server.models.UploadSnapshotResponse
import io.ktor.client.call.body
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for SEC3-S-06: Snapshot download endpoint.
 */
class SnapshotDownloadTest {

    private val encoder = java.util.Base64.getEncoder()

    private fun createSnapshotData(content: String = "snapshot-content-${System.nanoTime()}"): Triple<ByteArray, String, String> {
        val data = content.toByteArray()
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(data)
            .joinToString("") { "%02x".format(it) }
        val signature = encoder.encodeToString("test-signature".toByteArray())
        return Triple(data, sha256, signature)
    }

    private suspend fun uploadSnapshot(
        client: io.ktor.client.HttpClient,
        device: TestDevice,
        atSequence: Long,
        snapshotData: Triple<ByteArray, String, String>? = null,
    ): Pair<io.ktor.client.statement.HttpResponse, Triple<ByteArray, String, String>> {
        val bucketId = device.bucketId!!
        val dataTriple = snapshotData ?: createSnapshotData()
        val (data, sha256, signature) = dataTriple
        val metadata = Json.encodeToString(
            SnapshotMetadata.serializer(),
            SnapshotMetadata(atSequence = atSequence, keyEpoch = 1, sha256 = sha256, signature = signature)
        )

        val response = client.submitFormWithBinaryData(
            url = "/buckets/$bucketId/snapshots",
            formData = formData {
                append("metadata", metadata)
                append("snapshot", data, Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"snapshot.bin\"")
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        return Pair(response, dataTriple)
    }

    @Test
    fun `download matches upload content`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        uploadOpsChain(client, device, 1)

        val snapshotData = createSnapshotData("test-snapshot-binary-data")
        val (uploadResp, _) = uploadSnapshot(client, device, 1, snapshotData)
        assertEquals(HttpStatusCode.Created, uploadResp.status)
        val uploadBody = uploadResp.body<UploadSnapshotResponse>()
        val snapshotId = uploadBody.snapshotId

        // Download
        val downloadResp = client.get("/buckets/${device.bucketId}/snapshots/$snapshotId/download") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, downloadResp.status)

        val downloadedBytes = downloadResp.readRawBytes()
        assertTrue(snapshotData.first.contentEquals(downloadedBytes),
            "Downloaded content should match uploaded content")

        // Verify SHA-256 header
        val sha256Header = downloadResp.headers["X-Snapshot-SHA256"]
        assertNotNull(sha256Header)
        assertEquals(snapshotData.second, sha256Header)
    }

    @Test
    fun `wrong bucket returns 403`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // Device A creates bucket and uploads snapshot
        val deviceA = TestHelper.setupDeviceWithBucket(client)
        uploadOpsChain(client, deviceA, 1)
        val (uploadResp, _) = uploadSnapshot(client, deviceA, 1)
        assertEquals(HttpStatusCode.Created, uploadResp.status)
        val snapshotId = uploadResp.body<UploadSnapshotResponse>().snapshotId

        // Device A creates a second bucket
        val bucket2Resp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, bucket2Resp.status)
        val bucket2Id = bucket2Resp.body<BucketResponse>().bucketId

        // Try to download snapshot from wrong bucket
        val downloadResp = client.get("/buckets/$bucket2Id/snapshots/$snapshotId/download") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.Forbidden, downloadResp.status)
    }

    @Test
    fun `non-existent snapshot returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val fakeSnapshotId = java.util.UUID.randomUUID().toString()

        val downloadResp = client.get("/buckets/${device.bucketId}/snapshots/$fakeSnapshotId/download") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.NotFound, downloadResp.status)
    }

    @Test
    fun `device without bucket access gets 403`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        uploadOpsChain(client, device, 1)
        val (uploadResp, _) = uploadSnapshot(client, device, 1)
        assertEquals(HttpStatusCode.Created, uploadResp.status)
        val snapshotId = uploadResp.body<UploadSnapshotResponse>().snapshotId

        // Create an outsider device
        val outsiderReg = TestHelper.registerDevice(client)
        val outsider = TestHelper.authenticateDevice(client, outsiderReg)

        val downloadResp = client.get("/buckets/${device.bucketId}/snapshots/$snapshotId/download") {
            header(HttpHeaders.Authorization, "Bearer ${outsider.sessionToken}")
        }
        assertEquals(HttpStatusCode.Forbidden, downloadResp.status)
    }
}
