package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.uploadOpsChain
import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for SEC6-S-06: Per-bucket snapshot quota enforcement.
 */
class SnapshotQuotaTest {

    private val encoder = java.util.Base64.getEncoder()

    private fun createSnapshotData(): Triple<ByteArray, String, String> {
        val data = ByteArray(100) { it.toByte() }
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
    ): io.ktor.client.statement.HttpResponse {
        val bucketId = device.bucketId!!
        val (data, sha256, signature) = createSnapshotData()
        val metadata = Json.encodeToString(
            SnapshotMetadata.serializer(),
            SnapshotMetadata(atSequence = atSequence, keyEpoch = 1, sha256 = sha256, signature = signature)
        )

        return client.submitFormWithBinaryData(
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
    }

    @Test
    fun `upload within quota succeeds`() = testApplication {
        val config = testConfig().copy(maxSnapshotsPerBucket = 5)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        uploadOpsChain(client, device, 1)

        val response = uploadSnapshot(client, device, 1)
        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `upload at limit rejected with 409`() = testApplication {
        val config = testConfig().copy(maxSnapshotsPerBucket = 2)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        uploadOpsChain(client, device, 3)

        // Upload 2 snapshots (at limit)
        val resp1 = uploadSnapshot(client, device, 1)
        assertEquals(HttpStatusCode.Created, resp1.status)

        val resp2 = uploadSnapshot(client, device, 2)
        assertEquals(HttpStatusCode.Created, resp2.status)

        // Third should be rejected
        val resp3 = uploadSnapshot(client, device, 3)
        assertEquals(HttpStatusCode.Conflict, resp3.status)

        val body = resp3.body<ErrorResponse>()
        assertEquals("SNAPSHOT_QUOTA_EXCEEDED", body.error)
    }

    @Test
    fun `error message includes limit info`() = testApplication {
        val config = testConfig().copy(maxSnapshotsPerBucket = 1)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        uploadOpsChain(client, device, 2)

        uploadSnapshot(client, device, 1)

        val resp = uploadSnapshot(client, device, 2)
        assertEquals(HttpStatusCode.Conflict, resp.status)

        val body = resp.body<ErrorResponse>()
        assertTrue(body.message.contains("1"), "Error message should include the quota limit")
    }

    @Test
    fun `quota is per-bucket not global`() = testApplication {
        val config = testConfig().copy(maxSnapshotsPerBucket = 1)
        application { module(config) }
        val client = createJsonClient()

        // Create device with bucket 1
        val device = TestHelper.setupDeviceWithBucket(client)
        val bucket1Id = device.bucketId!!
        uploadOpsChain(client, device, 1)
        val resp1 = uploadSnapshot(client, device, 1)
        assertEquals(HttpStatusCode.Created, resp1.status)

        // Create bucket 2
        val bucket2Resp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, bucket2Resp.status)
        val bucket2Id = bucket2Resp.body<BucketResponse>().bucketId

        // Upload ops to bucket 2
        val deviceWithBucket2 = device.copy(bucketId = bucket2Id)
        uploadOpsChain(client, deviceWithBucket2, 1)

        // Uploading to bucket 2 should succeed even though bucket 1 is at quota
        val resp2 = uploadSnapshot(client, deviceWithBucket2, 1)
        assertEquals(HttpStatusCode.Created, resp2.status)
    }

    @Test
    fun `exactly at limit is rejected`() = testApplication {
        val config = testConfig().copy(maxSnapshotsPerBucket = 3)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        uploadOpsChain(client, device, 4)

        // Fill up to exactly the limit
        for (seq in 1L..3L) {
            val resp = uploadSnapshot(client, device, seq)
            assertEquals(HttpStatusCode.Created, resp.status, "Snapshot at sequence $seq should succeed")
        }

        // One more should be rejected
        val resp = uploadSnapshot(client, device, 4)
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }
}
