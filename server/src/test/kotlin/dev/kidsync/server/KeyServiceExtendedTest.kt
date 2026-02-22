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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Extended tests for KeyService: epoch validation, key wrapping, attestations, edge cases.
 */
class KeyServiceExtendedTest {

    private val encoder = Base64.getEncoder()

    @Test
    fun `upload wrapped key with valid epoch succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val resp = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = encoder.encodeToString(ByteArray(64) { it.toByte() }),
                keyEpoch = 1,
            ))
        }
        assertEquals(HttpStatusCode.Created, resp.status)
    }

    @Test
    fun `upload wrapped key with epoch zero rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val resp = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = encoder.encodeToString(ByteArray(64) { it.toByte() }),
                keyEpoch = 0,
            ))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `upload wrapped key with negative epoch rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val resp = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = encoder.encodeToString(ByteArray(64) { it.toByte() }),
                keyEpoch = -1,
            ))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `retrieve wrapped key returns correct data`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val wrappedDek = encoder.encodeToString(ByteArray(64) { it.toByte() })
        client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = wrappedDek,
                keyEpoch = 1,
            ))
        }

        val resp = client.get("/keys/wrapped?epoch=1") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<WrappedKeyResponse>()
        assertEquals(wrappedDek, body.wrappedDek)
        assertEquals(1, body.keyEpoch)
        assertEquals(deviceA.deviceId, body.wrappedBy)
    }

    @Test
    fun `overwrite same epoch returns 409`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val wrappedDek = encoder.encodeToString(ByteArray(64) { it.toByte() })
        client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = wrappedDek,
                keyEpoch = 1,
            ))
        }

        // Second upload for same epoch should conflict
        val resp = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = wrappedDek,
                keyEpoch = 1,
            ))
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `upload attestation succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val resp = client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceB.deviceId,
                attestedKey = deviceB.encryptionKeyBase64,
                signature = encoder.encodeToString("attestation-sig".toByteArray()),
            ))
        }
        assertEquals(HttpStatusCode.Created, resp.status)
    }

    @Test
    fun `get attestations returns uploaded attestation`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val attestedKey = deviceB.encryptionKeyBase64
        val signature = encoder.encodeToString("attestation-sig".toByteArray())
        client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceB.deviceId,
                attestedKey = attestedKey,
                signature = signature,
            ))
        }

        val resp = client.get("/keys/attestations/${deviceB.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<AttestationListResponse>()
        assertEquals(1, body.attestations.size)
        assertEquals(deviceA.deviceId, body.attestations[0].signerDevice)
        assertEquals(deviceB.deviceId, body.attestations[0].attestedDevice)
    }

    @Test
    fun `duplicate attestation returns 409`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        val request = KeyAttestationRequest(
            attestedDevice = deviceB.deviceId,
            attestedKey = deviceB.encryptionKeyBase64,
            signature = encoder.encodeToString("sig".toByteArray()),
        )

        client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(request)
        }

        val resp = client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(request)
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `wrapped key for missing device returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val fakeDeviceId = "00000000-0000-0000-0000-000000000099"

        val resp = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = fakeDeviceId,
                wrappedDek = encoder.encodeToString(ByteArray(64)),
                keyEpoch = 1,
            ))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `wrapped key without shared bucket returns 403`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)

        // Device C registers but doesn't share a bucket with device A
        val deviceC = TestHelper.registerDevice(client)
        val authedC = TestHelper.authenticateDevice(client, deviceC)

        val resp = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = authedC.deviceId,
                wrappedDek = encoder.encodeToString(ByteArray(64)),
                keyEpoch = 1,
            ))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `get wrapped key for device with no keys returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val resp = client.get("/keys/wrapped") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
}
