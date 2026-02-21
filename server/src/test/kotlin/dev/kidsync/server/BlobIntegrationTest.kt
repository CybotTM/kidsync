package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for blob upload and download endpoints.
 */
class BlobIntegrationTest {

    private fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    // ================================================================
    // Blob Upload
    // ================================================================

    @Test
    fun `upload blob with correct SHA-256 succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val fileContent = "encrypted-blob-content".toByteArray()
        val sha256 = computeSha256(fileContent)

        val response = client.submitFormWithBinaryData(
            url = "/buckets/$bucketId/blobs",
            formData = formData {
                append("sha256", sha256)
                append("file", fileContent, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"test.bin\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<BlobResponse>()
        assertNotNull(body.blobId)
        assertEquals(fileContent.size.toLong(), body.sizeBytes)
        assertEquals(sha256, body.sha256)
        assertNotNull(body.uploadedAt)
    }

    @Test
    fun `upload blob with wrong SHA-256 returns HASH_MISMATCH`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val fileContent = "encrypted-blob-content".toByteArray()
        val wrongSha256 = "a".repeat(64) // Wrong hash

        val response = client.submitFormWithBinaryData(
            url = "/buckets/$bucketId/blobs",
            formData = formData {
                append("sha256", wrongSha256)
                append("file", fileContent, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"test.bin\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("HASH_MISMATCH", body.error)
    }

    @Test
    fun `upload blob exceeding size limit returns PAYLOAD_TOO_LARGE`() = testApplication {
        // Use a config with very small blob limit for testing
        val config = testConfig().copy(maxBlobSizeBytes = 100)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val fileContent = ByteArray(101) { 0x42 }
        val sha256 = computeSha256(fileContent)

        val response = client.submitFormWithBinaryData(
            url = "/buckets/$bucketId/blobs",
            formData = formData {
                append("sha256", sha256)
                append("file", fileContent, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"large.bin\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    // ================================================================
    // Blob Download
    // ================================================================

    @Test
    fun `download uploaded blob returns same content`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val fileContent = "download-test-content-12345".toByteArray()
        val sha256 = computeSha256(fileContent)

        // Upload
        val uploadResp = client.submitFormWithBinaryData(
            url = "/buckets/$bucketId/blobs",
            formData = formData {
                append("sha256", sha256)
                append("file", fileContent, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"dl.bin\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.Created, uploadResp.status)
        val blobId = uploadResp.body<BlobResponse>().blobId

        // Download
        val dlResp = client.get("/buckets/$bucketId/blobs/$blobId") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, dlResp.status)

        val downloadedBytes = dlResp.readRawBytes()
        assertTrue(fileContent.contentEquals(downloadedBytes), "Downloaded content should match uploaded")

        // Verify SHA-256 header
        val sha256Header = dlResp.headers["X-Blob-SHA256"]
        assertEquals(sha256, sha256Header)
    }

    @Test
    fun `download nonexistent blob returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.get("/buckets/$bucketId/blobs/00000000-0000-0000-0000-000000000000") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ================================================================
    // Blob Access Control
    // ================================================================

    @Test
    fun `device without bucket access cannot upload blob`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        val outsiderReg = TestHelper.registerDevice(client)
        val outsider = TestHelper.authenticateDevice(client, outsiderReg)

        val fileContent = "unauthorized-content".toByteArray()
        val sha256 = computeSha256(fileContent)

        val response = client.submitFormWithBinaryData(
            url = "/buckets/$bucketId/blobs",
            formData = formData {
                append("sha256", sha256)
                append("file", fileContent, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"unauth.bin\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${outsider.sessionToken}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `device without bucket access cannot download blob`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Upload a blob
        val fileContent = "secret-content".toByteArray()
        val sha256 = computeSha256(fileContent)
        val uploadResp = client.submitFormWithBinaryData(
            url = "/buckets/$bucketId/blobs",
            formData = formData {
                append("sha256", sha256)
                append("file", fileContent, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"sec.bin\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        val blobId = uploadResp.body<BlobResponse>().blobId

        // Outsider tries to download
        val outsiderReg = TestHelper.registerDevice(client)
        val outsider = TestHelper.authenticateDevice(client, outsiderReg)

        val response = client.get("/buckets/$bucketId/blobs/$blobId") {
            header(HttpHeaders.Authorization, "Bearer ${outsider.sessionToken}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ================================================================
    // Missing parts
    // ================================================================

    @Test
    fun `upload blob without file part returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.submitFormWithBinaryData(
            url = "/buckets/$bucketId/blobs",
            formData = formData {
                append("sha256", "abc123")
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `upload blob without sha256 part returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val fileContent = "test".toByteArray()
        val response = client.submitFormWithBinaryData(
            url = "/buckets/$bucketId/blobs",
            formData = formData {
                append("file", fileContent, Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"test.bin\"")
                    append(HttpHeaders.ContentType, "application/octet-stream")
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
