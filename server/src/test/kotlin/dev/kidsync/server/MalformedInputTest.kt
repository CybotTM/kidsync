package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
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
 * Tests for malformed inputs: invalid JSON, missing fields, wrong types.
 */
class MalformedInputTest {

    // ================================================================
    // Empty / Missing JSON Bodies
    // ================================================================

    @Test
    fun `register with empty JSON body returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register with no content-type returns 400 or 415`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/register") {
            setBody("not json")
        }

        assertTrue(
            response.status == HttpStatusCode.BadRequest ||
                response.status == HttpStatusCode.UnsupportedMediaType,
            "Expected 400 or 415, got ${response.status}"
        )
    }

    @Test
    fun `register with invalid JSON syntax returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("{invalid json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `ops upload with invalid JSON returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody("{bad json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Missing Required Fields
    // ================================================================

    @Test
    fun `register with only signingKey missing encryptionKey returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"signingKey": "abc123"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `challenge request with missing signingKey returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `verify request with missing fields returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"signingKey": "abc"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `ops upload with missing ops field returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody("{}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Wrong Types
    // ================================================================

    @Test
    fun `ops upload with string where int expected returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            val prevHash = "0".repeat(64)
            val currentHash = "a".repeat(64)
            setBody(
                """{"ops": [{"deviceId": "test", "keyEpoch": "not-a-number", """ +
                    """"encryptedPayload": "dGVzdA==", "prevHash": "$prevHash", """ +
                    """"currentHash": "$currentHash"}]}"""
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register with int where string expected returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody("""{"signingKey": 12345, "encryptionKey": 67890}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Extra Unknown Fields (should be ignored per test client config)
    // ================================================================

    @Test
    fun `register with extra unknown fields succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val signingKP = TestHelper.generateSigningKeyPair()
        val encryptionKP = TestHelper.generateEncryptionKeyPair()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            val signingKey = TestHelper.encodePublicKey(signingKP.public)
            val encryptionKey = TestHelper.encodePublicKey(encryptionKP.public)
            setBody(
                """{"signingKey": "$signingKey", "encryptionKey": "$encryptionKey", """ +
                    """"extraField": "should-be-ignored"}"""
            )
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    // ================================================================
    // Invalid base64 payload
    // ================================================================

    @Test
    fun `ops with non-base64 payload returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 1,
                    encryptedPayload = "!!!not-base64!!!",
                    prevHash = "0".repeat(64),
                    currentHash = "a".repeat(64),
                ))
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Empty encrypted payload
    // ================================================================

    @Test
    fun `ops with blank payload returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 1,
                    encryptedPayload = "   ",
                    prevHash = "0".repeat(64),
                    currentHash = "a".repeat(64),
                ))
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Invite with empty token hash
    // ================================================================

    @Test
    fun `invite with blank token hash returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(InviteRequest(tokenHash = ""))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Wrapped key with very long payload
    // ================================================================

    @Test
    fun `wrapped key exceeding 8KB returns 413`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val response = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "x".repeat(8193), // > 8192
                keyEpoch = 1,
            ))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    // ================================================================
    // Attestation with very long signature
    // ================================================================

    @Test
    fun `attestation signature exceeding 4KB returns 413`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val response = client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceB.deviceId,
                attestedKey = deviceB.encryptionKeyBase64,
                signature = "x".repeat(4097), // > 4096
            ))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    // ================================================================
    // Recovery blob exceeding 1MB
    // ================================================================

    @Test
    fun `recovery blob exceeding 1MB returns 413`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/recovery") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(RecoveryBlobRequest(encryptedBlob = "x".repeat(1_048_577))) // > 1MB
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }
}
