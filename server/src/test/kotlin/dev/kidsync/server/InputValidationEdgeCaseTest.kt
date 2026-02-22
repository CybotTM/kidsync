package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Additional input validation edge cases covering UUID format enforcement,
 * keyEpoch bounds, content-length enforcement, and multipart limits.
 */
class InputValidationEdgeCaseTest {

    // ================================================================
    // UUID validation on path parameters
    // ================================================================

    @Test
    fun `bucket operations with non-UUID bucket ID returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.get("/buckets/not-a-uuid/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `bucket delete with non-UUID bucket ID returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.delete("/buckets/not-a-uuid") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invite with non-UUID bucket ID returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/buckets/invalid-uuid/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(InviteRequest(tokenHash = "a".repeat(64)))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // keyEpoch bounds
    // ================================================================

    @Test
    fun `ops with keyEpoch 0 returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val payload = Base64.getEncoder().encodeToString("test".toByteArray())
        val prevHash = "0".repeat(64)
        val curHash = TestHelper.computeHash(prevHash, payload)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 0, // Invalid: must be >= 1
                    encryptedPayload = payload,
                    prevHash = prevHash,
                    currentHash = curHash,
                ))
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertTrue(body.message.contains("keyEpoch"), "Error should mention keyEpoch")
    }

    @Test
    fun `ops with negative keyEpoch returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val payload = Base64.getEncoder().encodeToString("test".toByteArray())
        val prevHash = "0".repeat(64)
        val curHash = TestHelper.computeHash(prevHash, payload)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = -1,
                    encryptedPayload = payload,
                    prevHash = prevHash,
                    currentHash = curHash,
                ))
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `wrapped key with keyEpoch 0 returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val response = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "some-dek-padded-to-meet-minimum-length-requirement",
                keyEpoch = 0, // Invalid: must be >= 1
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Content-Length enforcement
    // ================================================================

    @Test
    fun `oversized encrypted payload returns 413`() = testApplication {
        // Use small maxPayloadSizeBytes to test the enforcement
        val config = testConfig().copy(maxPayloadSizeBytes = 100)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Create a payload that exceeds 100 bytes when decoded
        val largePayload = Base64.getEncoder().encodeToString(ByteArray(200))
        val prevHash = "0".repeat(64)
        val curHash = TestHelper.computeHash(prevHash, largePayload)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 1,
                    encryptedPayload = largePayload,
                    prevHash = prevHash,
                    currentHash = curHash,
                ))
            ))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
    }

    // ================================================================
    // wrappedDek length bounds
    // ================================================================

    @Test
    fun `wrapped key with wrappedDek too short returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val response = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "short", // < 44 chars minimum
                keyEpoch = 1,
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `wrapped key with wrappedDek exactly 44 chars succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val response = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "x".repeat(44), // Exact minimum
                keyEpoch = 1,
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `wrapped key with wrappedDek exactly 8192 chars succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val response = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "x".repeat(8192), // Exact maximum
                keyEpoch = 1,
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    // ================================================================
    // Empty ops batch
    // ================================================================

    @Test
    fun `empty ops array returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(ops = emptyList()))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertTrue(body.message.contains("at least 1"))
    }

    // ================================================================
    // Ops batch size limit
    // ================================================================

    @Test
    fun `ops batch with 101 ops returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val ops = (1..101).map { i ->
            val payload = Base64.getEncoder().encodeToString("p-$i".toByteArray())
            OpInput(
                deviceId = device.deviceId,
                keyEpoch = 1,
                encryptedPayload = payload,
                prevHash = "0".repeat(64),
                currentHash = "a".repeat(64),
            )
        }

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(ops = ops))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("BATCH_TOO_LARGE", body.error)
    }

    // ================================================================
    // Pull ops negative since parameter
    // ================================================================

    @Test
    fun `pull ops with negative since returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.get("/buckets/$bucketId/ops?since=-1") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `pull ops with non-numeric since returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.get("/buckets/$bucketId/ops?since=abc") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `pull ops with non-numeric limit returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.get("/buckets/$bucketId/ops?since=0&limit=abc") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Push token validation
    // ================================================================

    @Test
    fun `push token with invalid platform returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/push/token") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(PushTokenRequest(token = "test-token", platform = "INVALID"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `push token with empty token returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/push/token") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(PushTokenRequest(token = "", platform = "FCM"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // attestedDevice UUID validation
    // ================================================================

    @Test
    fun `attestation with non-UUID attestedDevice returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = "not-a-uuid",
                attestedKey = "some-key",
                signature = "some-sig",
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `wrapped key with non-UUID targetDevice returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = "not-a-uuid",
                wrappedDek = "some-dek-padded-to-meet-minimum-length-requirement",
                keyEpoch = 1,
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Token hash validation on invite
    // ================================================================

    @Test
    fun `invite with non-hex tokenHash returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(InviteRequest(tokenHash = "not-a-valid-sha256-hex-string-at-all-needs-64-chars-but-not-hex!"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `invite with tokenHash exceeding 128 chars returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(InviteRequest(tokenHash = "a".repeat(129)))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Timestamp drift in auth/verify
    // ================================================================

    @Test
    fun `auth verify with timestamp too far in the past returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authedDevice = TestHelper.authenticateDevice(client, device)

        // Get a fresh challenge
        val challengeResp = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        // Sign with a timestamp from 2 minutes ago
        val oldTimestamp = java.time.Instant.now().minusSeconds(120).toString()
        val (signature, _) = TestHelper.signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challengeResp.nonce,
            signingKeyBase64 = device.signingKeyBase64,
            timestamp = oldTimestamp,
        )

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challengeResp.nonce,
                signature = signature,
                timestamp = oldTimestamp,
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("TIMESTAMP_DRIFT", body.error)
    }

}
